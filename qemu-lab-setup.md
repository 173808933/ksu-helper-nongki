# QEMU 内核靶场搭建手册（5.4 / 4.19 实测，可移植到 6.1）

> 目标：在没有 root、不能刷机的真机上，先在一个**能反复重启、崩溃有完整日志**的环境里，验证内核侧的提权链路（punch 几何、结构体偏移、vermagic、模块加载）。
>
> 本手册的两份实测来源：**OPPO sm7325 / 5.4.254-qgki**（Reno7）与**主线 4.19.157**（Find X3 的替代靶场）。

---

## 0. 一句话结论

能用，而且值得。核心收益不是"能跑起来"，而是**崩溃时能拿到完整 oops 栈 + 可以 2 分钟迭代一次**。真机上一次崩溃是"重启 + 日志回滚 + 全靠猜"，靶场里是同一条命令重跑。

---

## 1. 环境准备（WSL2 / Ubuntu 22.04）

```bash
# QEMU（注意用 root，普通用户走 sudo 会卡密码）
wsl -u root -e bash -c 'apt-get install -y qemu-system-arm'

# 交叉工具链
wsl -u root -e bash -c 'apt-get install -y binutils-aarch64-linux-gnu gcc-aarch64-linux-gnu dwarves gdb'
```

**`binutils-aarch64-linux-gnu` 是关键**。缺它的时候，内核 Makefile 里的 `-no-integrated-as` 会让 clang 找不到 `aarch64-linux-gnu-as`：

- 症状：编译到 `arch/arm64/mm/*.o`、`crypto/*.o` 时 **`Segmentation fault (core dumped)`**
- 极易误判成"clang 版本不兼容"，实际是外部汇编器缺失导致的调用链断裂

装完验证：

```bash
aarch64-linux-gnu-as --version   # GNU assembler (GNU Binutils for Ubuntu) 2.38
pahole --version                 # pahole 用于读结构体布局
```

---

## 2. 三条路线，按目标选

| 路线 | 适用 | 优点 | 代价 |
|---|---|---|---|
| **A. 厂商源码树 + 真机 config** | 需要与真机完全一致的布局 | 结构体偏移最准 | 厂商没开源的 vendor 代码要一路补桩，见 §4 |
| **B. 主线源码 + defconfig** | 验证机制是否成立（推荐起步） | 零阻碍，一次编过 | 布局与真机有差异（见 §6） |
| **C. 主线源码 + 真机 config** | 折中 | 结构接近真机 | 缺的 vendor 驱动要一个个关 |

**建议**：先用 B 把靶场跑起来、把流程和脚本调通；再用 A 做精确偏移验证。

---

## 3. 通用 7 步

### 3.1 取源码

```bash
# 主线（推荐起步）
curl -L -o /home/dev/dl/linux-4.19.157.tar.xz \
  https://cdn.kernel.org/pub/linux/kernel/v4.x/linux-4.19.157.tar.xz
cd /home/dev && tar -xf dl/linux-4.19.157.tar.xz

# 厂商树：解压到 /home/dev（**不要放 /mnt/c**，见 §5.1）
```

### 3.2 配置

```bash
cd /home/dev/linux-4.19.157
make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- CC=aarch64-linux-gnu-gcc defconfig

# 靶场必需项（缺一个就起不来或没输出）
for o in NET_9P NET_9P_VIRTIO 9P_FS KALLSYMS_ALL DEBUG_INFO \
         DEVTMPFS DEVTMPFS_MOUNT BLK_DEV_INITRD RD_GZIP; do
  bash scripts/config --file .config -e $o
done
make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- CC=aarch64-linux-gnu-gcc \
     olddefconfig < /dev/null
```

用真机 config 时要补的驱动（真机 config 通常全关）：

```bash
for o in SERIAL_CORE SERIAL_CORE_CONSOLE SERIAL_AMBA_PL011 SERIAL_AMBA_PL011_CONSOLE \
         VIRTIO VIRTIO_PCI VIRTIO_MMIO VIRTIO_BLK PCI_HOST_GENERIC PCI_MSI; do
  bash scripts/config --file .config -e $o
done
bash scripts/config --file .config -d QCOM_EARLY_RANDOM   # 高通 smc 相关，QEMU 无 TZ
```

### 3.3 编译

```bash
make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- CC=aarch64-linux-gnu-gcc \
  LD=aarch64-linux-gnu-ld AR=aarch64-linux-gnu-ar NM=aarch64-linux-gnu-nm \
  HOSTCC=gcc -j8 Image < /dev/null
```

用 clang 编（Android 树）时：

```bash
C=/home/dev/toolchain/clang-r383902b1/bin
make ARCH=arm64 CC="$C/clang --target=aarch64-linux-gnu" LD=$C/ld.lld AR=ar \
  NM=$C/llvm-nm OBJCOPY=$C/llvm-objcopy OBJDUMP=$C/llvm-objdump STRIP=$C/llvm-strip \
  CROSS_COMPILE=aarch64-linux-gnu- HOSTCC=gcc -j8 Image < /dev/null
```

产物：`arch/arm64/boot/Image`（验证 `xxd -s 0x38 -l 4` = `4152 4d64`）和 `vmlinux`（读 DWARF 用）。

### 3.4 造 initramfs

需要一个 **aarch64 静态 busybox**（系统自带的是 x86）：

```bash
# Debian 的 arm64 静态包（apt 的 arm64 源通常不可用，直接抓 deb）
cd /tmp && curl -O http://ftp.debian.org/debian/pool/main/b/busybox/busybox-static_1.35.0-4+deb12u1+b1_arm64.deb
dpkg-deb -x busybox-static_*.deb x && file x/bin/busybox   # 应为 "ARM aarch64, statically linked"
```

打包：

```bash
R=/home/dev/qemu-root
mkdir -p $R/{bin,proc,sys,dev,mnt,etc,tmp}
cp /tmp/x/bin/busybox $R/bin/ && chmod 755 $R/bin/busybox
for a in sh ls cat mount echo dmesg grep sleep insmod lsmod id uname; do ln -sf busybox $R/bin/$a; done

cat > $R/init <<'EOF'
#!/bin/busybox sh
/bin/busybox mount -t proc none /proc
/bin/busybox mount -t sysfs none /sys
/bin/busybox mount -t devtmpfs none /dev 2>/dev/null
/bin/busybox mount -t tmpfs none /tmp 2>/dev/null
echo "=== [GUEST] init ==="
/bin/busybox cat /proc/version
mkdir -p /mnt
/bin/busybox mount -t 9p -o trans=virtio,version=9p2000.L host0 /mnt && echo "9p OK"
cd /mnt || exec /bin/busybox sh
exec /bin/busybox sh
EOF
chmod 755 $R/init

cd $R && find . -print0 | cpio --null -o -H newc 2>/dev/null | gzip -9 > /home/dev/initramfs.cpio.gz
```

### 3.5 起机

```bash
qemu-system-aarch64 -M virt,secure=on,virtualization=on -cpu cortex-a57 \
  -smp 1 -m 1024 -nographic -no-reboot \
  -kernel arch/arm64/boot/Image \
  -initrd /home/dev/initramfs.cpio.gz \
  -virtfs local,path=/home/dev/qshare,mount_tag=host0,security_model=none \
  -append "console=ttyAMA0,115200 earlycon rdinit=/init nokaslr panic=-1" < /dev/null
```

参数逐条理由：

| 参数 | 理由 |
|---|---|
| `-M virt,secure=on,virtualization=on` | 补 EL3。缺了厂商 `smc` 是未定义指令 |
| `-cpu cortex-a57` | 提供 EL3，兼容厂商 erratum workaround |
| `-smp 1` | 厂商 SMP bring-up 依赖 QCOM，多核最容易卡死在半路 |
| `-m 1024` | 够用；加大不解决 SMEM 类问题 |
| `-no-reboot` + `panic=-1` | 崩了保住现场，不重启 |
| `rdinit=/init` | 直接跑 initramfs 的 init |
| `nokaslr` | 地址确定，方便对照 System.map / gdb |
| `earlycon` | console 驱动 probe 之前就有输出 |

### 3.6 卡住时怎么定位

```bash
# 加这两项，能看到每个 initcall 的进入和返回
-append "... ignore_loglevel initcall_debug"
```

日志停在 `calling  XXX_init` 且无 `returned`，就是它卡住了。把它丢进黑名单：

```bash
-append "... initcall_blacklist=proc_app_info_init,msm_restart_init,scm_mem_protection_init"
```

### 3.7 读结构体（pahole）

```bash
pahole -C rt_mutex_waiter vmlinux
pahole -C task_struct vmlinux | grep -E "\b(real_cred|cred|comm)\b"
pahole -C rt_sigframe vmlinux
pahole -C fpsimd_context vmlinux      # vregs 的偏移就是 VREGS_OFF
```

**这是靶场最大的价值之一**：结构体偏移不用反汇编猜，编译完直接读。

---

## 4. 厂商树的补桩（路线 A 必踩）

厂商开源包普遍只有一半（`vendor/oplus/` 整个不给），表现为**悬空 symlink** 和**缺 Kconfig/头文件**。按出现频率排序：

### 4.1 CRLF

```bash
python3 - <<'PY'
import os
n=0
for dp,dn,fn in os.walk("."):
    if "/.git" in dp: continue
    for f in fn:
        # 注意：必须按前缀匹配 Kconfig，扩展名列表会漏掉 Kconfig.preempt 这类
        if not (f.startswith("Kconfig") or f in ("Makefile","Kbuild","Kbuild.include")
                or os.path.splitext(f)[1] in (".c",".h",".S",".s",".mk",".sh",".conf")):
            continue
        p=os.path.join(dp,f)
        try:
            d=open(p,"rb").read()
            if b"\r\n" in d:
                open(p,"wb").write(d.replace(b"\r\n",b"\n")); n+=1
        except OSError: pass
print("CRLF->LF:",n)
PY
```

症状：`Kconfig.preempt:57: unexpected end statement` / `ignoring unsupported character`。

### 4.2 缺失的 Kconfig（含**无引号**写法）

```bash
python3 - <<'PY'
import os,re
root=os.getcwd(); missing=set()
for dp,dn,fn in os.walk(root):
    if "/.git" in dp: continue
    for f in fn:
        if f.startswith("Kconfig"):
            p=os.path.join(dp,f)
            try: txt=open(p,encoding="utf-8",errors="ignore").read()
            except: continue
            # 关键：正则要允许 source path 不带引号（OPPO 树里大量这种）
            for m in re.finditer(r'^\s*(?:o|r)?source\s+"?([^"\s]+)"?\s*$', txt, re.M):
                t=m.group(1)
                if "$" in t: continue
                if not os.path.exists(os.path.join(root,t)): missing.add(t)
for t in sorted(missing):
    full=os.path.join(root,t)
    os.makedirs(os.path.dirname(full),exist_ok=True)
    if not os.path.exists(full): open(full,"w").write("# stub\n")
    mk=os.path.join(os.path.dirname(full),"Makefile")
    if not os.path.exists(mk): open(mk,"w").write("# stub\n")
print("补齐:",len(missing))
PY
```

只允许带引号的写法会漏掉 `source block/uxio_first/Kconfig`，导致 Kconfig 阶段反复失败。

### 4.3 悬空 symlink

```bash
# 列出
find . -type l ! -exec test -e {} \; -print

# 目录型 → 建空 Makefile；文件型 → 建空文件
```

### 4.4 缺头文件

```bash
# 循环：编译 → 抓 fatal error → 建桩 → 重编
miss=$(grep -oP "fatal error: .[^\x27\"]+. file not found" log \
  | sed "s/.*fatal error: .//;s/. file not found//" | sort -u)
for h in $miss; do
  rm -f "include/$h"                       # 先删悬空 symlink
  p="include/$(dirname "$h")"
  while [ "$p" != "include" ]; do [ -L "$p" ] && rm -f "$p"; p=$(dirname "$p"); done
  mkdir -p "include/$(dirname "$h")"; printf "/* stub */\n" > "include/$h"
done
```

注意两点：
- 路径上的**目录级** symlink 也要删，否则 `mkdir` 报 `File exists`
- 空桩会带出 `implicit declaration`（配合 `-Werror` 直接就挂），要在桩头里补 `static inline` 假实现

### 4.5 高频符号桩

| 符号 | 桩法 |
|---|---|
| `is_critial_process` / `is_key_process` | `static inline bool f(struct task_struct *t){return false;}` |
| `get_boot_mode` + `MSM_BOOT_MODE__*` | 枚举全补（NORMAL/FASTBOOT/RECOVERY/SILENCE/CHARGER/CHARGE/SAU/FACTORY/RF/WLAN/AGING/MOS/SAFE），函数返 NORMAL |
| `phx_set_boot_stage` | `static void (*f)(int) __attribute__((unused)) = 0;`（源码是 `if(f)` 才调用，等于特性关闭） |
| `MAX_BLKS_PER_CLUSTER` | 在 `struct decompress_io_ctx` 前插 `#ifndef ... #define ... (1<<3) #endif` |

### 4.6 该砍就砍（比补桩快得多）

厂商特性是 Makefile 里的 `-D` 宏，不是 CONFIG 项，`scripts/config -d` 对它们**无效**。真正决定编译的是 `obj-y += xxx/`。两种处理：

```bash
# ① 掐掉宏注入源头（推荐，一刀切）
cp OplusKernelEnvConfig.mk /home/dev/OplusKernelEnvConfig.mk.bak
echo "# disabled for QEMU bring-up" > OplusKernelEnvConfig.mk

# ② 关 CONFIG（只对 obj-$(CONFIG_xxx) 形式有效）
python3 -c "
import re
p='.config'; L=open(p).read().splitlines(); out=[]
for l in L:
    m=re.match(r'^(CONFIG_OPLUS[A-Z0-9_]*)=(y|m)$', l)
    out.append('# %s is not set'%m.group(1) if m else l)
open(p,'w').write('\n'.join(out)+'\n')"
```

### 4.7 `checksyscalls` 假失败

症状：`<stdin>:239: error: missing ')' after 'defined'`

这是 CRLF 破坏 `scripts/checksyscalls.sh` 导致的，它只是个"新增 syscall 是否实现"的检查，不影响编译：

```bash
sed -i "2i exit 0   # QEMU bring-up: 跳过 syscall 检查" scripts/checksyscalls.sh
```

---

## 5. 已确认的坑（按坑人程度排序）

### 5.1 别把内核树放 `/mnt/c`

实测数据（同一台机器）：

| 场景 | `/home` (ext4) | `/mnt/c` (drvfs) | 倍数 |
|---|---|---|---|
| 顺序写 200 MB | 462 MB/s | 77.5 MB/s | 6× |
| 写 400 个小文件 | 0.05 s | 3.69 s | **74×** |

内核编译产生几千个小文件，放 `/mnt/c` 上是灾难。**源码树、输出目录、initramfs 全部放 `/home`**。

### 5.2 `make ... < /dev/null` 不能省

老 kbuild 的 `conf --syncconfig` 会等 stdin，表现为"make 像卡死、CPU 时间 0"。

### 5.3 `AR` 必须用 GNU `ar`

```bash
AR=ar          # ✅
# AR=llvm-ar   # ❌ llvm-ar: error: unknown option E（kbuild 会传 rcsE）
```

### 5.4 编译器 SIGSEGV 先查工具链，再怀疑代码

顺序：`aarch64-linux-gnu-as` 是否存在 → `-no-integrated-as` → `ulimit -s` → 宏数量。本机上三种编译器（clang 11、clang 14、gcc 11）在特定文件崩过，换工具链组合不一定有用，见 §6.3。

---

## 6. 跨版本移植（6.1 需要改的地方）

### 6.1 大概率不用改的

- QEMU 参数（`virt,secure=on` + `cortex-a57` + 单核 + `nokaslr`）
- initramfs 结构、busybox
- 9p 共享挂载
- `initcall_debug` / `initcall_blacklist` 排查法
- `pahole` 读结构
- vermagic 等长替换 + insmod 验证（在 5.4 真机上实测通过）

### 6.2 要改的

| 项 | 5.4 | 6.1 |
|---|---|---|
| 编译器 | clang 11 / 14，`CC=clang` | 需要 `LLVM=1`（6.1 支持），或显式 `CC/LD/AR/NM/OBJCOPY/STRIP` 全套 |
| `-no-integrated-as` | Makefile 里写死 | 已改为 `LLVM_IAS` 控制，一般不用管 |
| kallsyms 布局 | `offsets(int32) → relative_base(u64) → num_syms → names` | ≤6.1 同；**≥6.4 变成 `num_syms → names → markers → token → offsets`**，解析器要分支 |
| CFI | 有，但 type hash 跨 clang 版本**稳定**（已实验验证） | 同，但 `CONFIG_CFI_CLANG` 更强（`-fsanitize=cfi` + 完整性校验） |
| `sigframe` 布局 | `rt_sigframe` 4688，`fpsimd.vregs` off=16 | 6.1 起用 `rt_sigframe_user_layout`，**SVE 会改变 frame 大小**，需 `CONFIG_ARM64_SVE` 与当前线程 TIF_SVE 状态确认 |
| 模块 vermagic | `5.4.254-qgki-<hash>` | 6.1 GKI 有 KMI，vermagic 含 `5.10/6.1` 前缀，替换时注意长度必须相同 |

### 6.3 一个未解决的坑（记录下来免得重复踩）

OPPO 的 4.19 树（Find X3）在 WSL 上，`arch/arm64/mm/{ioremap,dma-mapping}.o`、`crypto/sha256-glue.o`、`kernel/events`、`certs/system_keyring.o` 编译时 SIGSEGV。

已排除：并发（`-j1` 单文件照样崩）、内存（14 GB 空闲）、栈限制（`ulimit -s unlimited` 无效）、外部汇编器（装了 binutils 仍崩）、LSE（关 `AS_LSE` / 加 `-march=armv8-a+lse` 都试）、宏污染（清空 `OplusKernelEnvConfig.mk` 仍崩）、编译器版本（clang 11/14、gcc 11 全崩）。

**结论**：这棵树上不值得继续投入，换主线源码（路线 B）十分钟就能编出来。

---

## 7. 脚本清单（都在 `/home/dev`）

| 文件 | 作用 |
|---|---|
| `qemu-boot.sh` | 5.4 靶场启动（含 `initcall_blacklist`） |
| `qemu-root/` + `qemu-*.cpio.gz` | initramfs 源与打包产物 |
| `qshare/` | 与 guest 的 9p 共享目录（放 p7、.ko） |
| `linux-4.19.157/` | 主线 4.19 靶场 |
| `kernel-sm7325-qemu/.../out-qemu/` | 5.4 真机 config 靶场 |
| `logs/` | 所有编译与起机日志 |

---

## 8. 验收清单

起机成功的标志：

```text
Booting Linux on physical CPU 0x0000000000
Machine model: linux,dummy-virt
=== [GUEST] init ===
Linux version ...
9p OK
BusyBox v1.35.0 ... built-in shell (ash)
/ #
```

结构体读取成功的标志：

```text
pahole -C rt_mutex_waiter vmlinux
→ 能打印字段偏移与 sizeof
```

模块验证成功的标志（真机，5.4 一加 9 实测）：

```text
insmod hello.ko → RC=0
/proc/modules → hello ... Live
dmesg → hello54: loaded
```

---

## 9. 附：vermagic 等长替换（真机验证用）

模块的 vermagic 在 `.modinfo` 段，是普通字符串。**只要目标串与源串长度相同**，可以直接改字节：

```python
d = open("mod.ko","rb").read()
assert len(b"gaa96eae94d32") == len(b"gacce45beef5d")   # 必须先确认等长
open("mod-patched.ko","wb").write(d.replace(b"gaa96eae94d32", b"gacce45beef5d"))
```

验证：

```bash
llvm-readelf -p .modinfo mod-patched.ko | grep vermagic
```

在 5.4.254-qgki 真机上实测：改完直接 `insmod` 成功，MODVERSIONS 未拦截，CFI 未拦截。
