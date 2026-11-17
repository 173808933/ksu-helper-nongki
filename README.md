# KSU Helper — 非 GKI 设备的一键 KernelSU 装载

在 **不支持 GKI 的老内核**（4.19 / 5.4 的 OPPO 机型）上，把设备从普通 shell 抬到 KernelSU root，并尽量让官方 KernelSU 管理器能正常工作。

> 只在自己有处置权的设备上使用。会改内核内存，任何一步对不上都会重启。

---

## 一、这个项目解决什么问题

KernelSU 从 v1.0 起**删除了对非 GKI 设备的支持**，官方管理器看到 `4.19.157-perf+` 这类内核版本会直接进「不支持」页——那是终止页，后面没有任何功能入口。

但设备本身是能跑 KSU 的：KernelSU 的 `kernel/` 目录一直保留着 4.14+ 的兼容代码，只要按目标机型标定好内核符号偏移、编出 `kernelsu.ko`，再配合一个能绕过「版本/内核代际」检查的管理器，就能用。

本项目做的就是这件事，包含三部分：

| 部分 | 作用 |
|---|---|
| **偏移标定** | 从 OTA 的 `boot.img` 反汇编，算出 p7 / ghostlock54 需要的 7~8 个内核地址 |
| **Android App** | 一键提权 + 自动装载 KSU 模块 + 部署 ksud + 写 allowlist |
| **gl54 适配** | 改造 ghostlock54 的硬编码偏移，让它认识新的 5.4 内核 |

---

## 二、支持的机型与当前状态

**⚠️ 状态如实标注，未验证的就是未验证。**

| 机型 | 型号 | 内核 | 提权 | KSU 生态 | 备注 |
|---|---|---|---|---|---|
| OPPO Pad | OPD2101 | 4.19.157-perf+ | ✅ 可用 | ⚠️ 受限 | 官方 Manager 不支持非 GKI，`su` 通但 Manager 界面进不去 |
| Reno5 Pro | PDSM00 | 4.19.191+ | ⚠️ 待验证 | — | 8 个偏移已标定，未在真机跑通 |
| Reno6 Pro | PEPM00 | 4.19.191+ | ⚠️ 待验证 | — | 偏移已标定；perf 采样命中率仅 6/4095，加了 `GL_TMODE=2` 待复测 |
| Reno7 | PFJM10 | 5.4.254-qgki | ✅ 可用 | ✅ 可用 | ghostlock54 原生支持目标机型 |
| A93 | PEHM00 | 5.4.254-qgki-g9ef4 | ❌ 未通过 | — | gl54 已适配 7 个偏移，卡在 `OFF_FUTEX_WAIT_REQUEUE_PI` 无对应窗口 |

### 各机型的具体卡点

**OPPO Pad（能提权，但 Manager 用不了）**

- 提权链路完全通了：`raw_uid=0`、模块 `Live`、`su` 可用
- `allowlist` 双条（Manager + 本 App）写入成功
- **卡在**：官方 Manager 3.3.1 内部的 GKI-only 判断，这不是模块侧能改的
- 可行路线：换 SukiSU-Ultra 这类保留非 GKI 支持的社区 fork（需换模块源码重编）

**Reno6 Pro（perf 采样率过低）**

```
[P] pick=0xffffff98d5e80000 hits=6/4095    ← 4095 次采样只命中 6 次
[W] base=0x0                                ← punch 没有目标
[main] CMP_REQUEUE_PI ret=-1 errno=35       ← EAGAIN，槽位拿不到
```

`errno=35` 是结果不是原因。`hits` 太低导致 `base` 推不出来。已加 `GL_TMODE=2`（拉长采样窗口）待验证。

**A93（gl54 的 futex 窗口无解）**

A93 的 `futex_unlock_pi` 只有 **0x660** 字节，Reno7 是 0x6f8+。差值 0x98 不是偶然——A93 把 `futex_wait_requeue_pi` 的 `rt_mutex_waiter` 栈操作前移到了 `__arm64_sys_futex` 内部，`futex_unlock_pi` 里根本没有那个窗口。

这不是偏移能糊过去的，需要重写采样点。

---

## 三、关键发现（供同类项目参考）

### 3.1 init_cred 的引用计数只有 4

`init_cred.usage` 初值 **4**，而 exploit 成功后那个进程会被系统 `SIGKILL`：

```
init: Untracked pid ... received signal 9
```

每被杀一次就 `atomic_dec(init_cred.usage)`。扣到 0 时 `__put_cred` 把静态 `init_cred` 当 slab 对象释放，内核在 RCU 回调里 panic——**表现为「提权完一分钟左右重启」**。

**同一次开机最多跑一次漏洞**。本项目用 `/data/local/tmp/.glhold` 做闸门。

### 3.2 `GL_PARANOID` 不是 `mmap_min_addr`

一开始误以为它指 `mmap_min_addr`，导致 perf 采样全程失败。实际是 **`sysctl_perf_event_paranoid`**，从 `__arm64_sys_perf_event_open` 里的 `ldr w8, [x8, #0x1ac]` 定位，初值读出 **2**。

### 3.3 insmod 必须带 `kln=` 参数

4.19 不导出 `kallsyms_lookup_name`，模块必须通过 `kln=0x...` 拿到它。**不带这个参数，模块内部符号解析走 `/proc/kallsyms` 被 `kptr_restrict` 挡住，结果 `GET_INFO` 返回的 flags 全零，Manager 判定「内核不支持」。**

```sh
insmod kernelsu.ko kln=0x$(grep ' kallsyms_lookup_name$' /proc/kallsyms | cut -d' ' -f1) level=3
```

### 3.4 allowlist 要授权 Manager 自己

KSU 生态的前提是 **Manager 有 su 权限**——它要能读写白名单、管模块。只授权自己的 App 是不够的：

```
key='me.weishu.kernelsu' uid=<Manager的uid> allow_su=1 use_default=1 domain='u:r:ksu:s0'
```

字段偏移：`version@0`、`key@4`(256B)、`curr_uid@260`、`allow_su@264`、`union@272`、`domain@704`，每条 **784 字节**，文件头 8 字节（magic `0x7f4b5355` + version `4`）。

**空域会被 `profile_valid` 丢弃**，`domain` 必须非空。

### 3.5 `OFF_TASK_STATE` 是 `proc_pid_status + 0x794`

不是调度函数。gl54 读 `/proc/self/status` 时采样 `proc_pid_status` 的 IP，在 `+0x794` 起的 0x720 窗口内从 `x3`/`x26` 抓 `task_struct`。

验证方法：Reno7 的 `proc_pid_status = 0x592B90`，`+0x794 = 0x593324`，正好等于 gl54 里硬编码的值。

### 3.6 编译坑（WSL 上编 Android 内核模块）

| 现象 | 原因 |
|---|---|
| `set: -: invalid option` | 脚本是 CRLF 换行 |
| `invalid value 'sysreg'` | clang < 15 不支持 per-task 栈保护 |
| `unknown register name 'x0'` | 缺 `--target=aarch64-linux-gnu` |
| `'uapi/app_profile.h' file not found` | 缺 `-I$SRC/src` |
| `mcontext_t has no member '__reserved'` | glibc 无此字段，按 bionic 布局取 `+0x100` |
| `MODPOST 0 modules` | 没传 `CONFIG_KSU=m` |

---

## 四、目录结构

```
.
├── ksu-helper/              Android App（Kotlin + Compose）
│   └── app/src/main/
│       ├── assets/
│       │   ├── profiles.json    各机型的偏移档案
│       │   ├── p7                4.19 用的提权器
│       │   ├── ghostlock54       5.4 用的提权器（含内嵌 ko）
│       │   ├── rksh / rkshd      提权后的 root shell
│       │   └── rk9*.ko           root shell 的内核模块
│       └── java/.../MainActivity.kt
├── _gl54/                   ghostlock54 源码（含 A93 适配改动）
│   ├── ghostlock54.c
│   └── kernelsu/            KSU 模块源码（5.4 适配）
├── docs/
│   ├── p7-params.md         p7 的参数说明与关键地址
│   └── qemu-lab-setup.md    QEMU 内核靶场搭建手册
└── tools/
    ├── fetch-boot.py        从 OTA 直链按需提取 boot.img（HTTP Range）
    ├── ota_extract.py       本地 OTA 提取
    └── calc.py / calc54.py  从 Image 反算 8 个偏移
```

---

## 五、偏移标定流程

```sh
# 1. 从 OTA 提取 boot.img（6GB 全量包不落地，只取需要的字节）
python fetch-boot.py "<ota_url>" --part boot.img --out boot.img

# 2. 解出内核并恢复符号表
python -c "..."   # 见 tools/ 里的脚本
vmlinux-to-elf Image kernel.elf

# 3. 算偏移
python calc.py Image kernel.elf      # 4.19
python calc54.py Image kernel.elf    # 5.4

# 4. 写进 profiles.json
```

**每个偏移都要独立验证**，不能只靠反汇编猜：

- `init_cred`：读前 4 字节应为 **4**（`ATOMIC_INIT(4)`），且 `init_task+0x768`（4.19）或 `+0x808`（5.4）指向它
- `sig_enforce`：初值应为 **1**（`CONFIG_MODULE_SIG_FORCE=y`）
- `perf_event_paranoid`：初值应为 **2**
- `selinux_state`：从 `selinux_avc_init` 或符号表取

---

## 六、已知限制

1. **官方 Manager 不支持非 GKI**——这是 KernelSU 上游的决定，改 versionCode / UAPI 都绕不过，只能换 fork
2. **A93 的 gl54 适配未完成**——`OFF_FUTEX_WAIT_REQUEUE_PI` 在 A93 上无对应编译产物
3. **Reno5/Reno6 Pro 未真机验证**——偏移是从 OTA 反汇编算出来的，理论正确但未跑通
4. **每次开机只能提权一次**——`init_cred` 预算限制，跑多了必崩

---

## 七、致谢

- [KernelSU](https://github.com/tiann/KernelSU) — 内核侧实现与 4.14+ 兼容代码
- [ghostlock-pju110-4.19-ksu](https://github.com/awjdxcy/ghostlock-pju110-4.19-ksu) — ghostlock54 的参考实现与详细文档，本项目的 `insmod kln=` 和 allowlist 格式都源自它
- 部分偏移标定方法参考 CVE-2026-43499 的公开分析

## 许可

代码仅供学习研究。`_gl54/kernelsu/` 源自 KernelSU，遵循 **GPL-3.0**。
