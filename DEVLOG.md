# 开发日志

> **规则**：每次改动追加在顶部，写清「改了什么 / 为什么改 / 怎么验证的 / 结论对不对」。
> 特别是**错误的判断**必须记下来 —— 那是花时间最多的地方。
> 版本号对应 `app/build.gradle.kts` 的 `versionCode`。

---

## 2026-11-18

### 拿到对方的 4.19 适配源码，按 Pad 编出 ko（metadata 全对，但仍不通）

**关键发现：加载参数是 `kln=0x… level=3`**

从对方 `ksu` 载荷里抽出 `lt/gl_v112`，它的字符串表里写着：

```
init param: %s
kln=0x%lx level=3
```

`level` 是他们的核心参数：源码里 `ksu54_level` 控制三档
- `>=1` → `ksu_syscall_hook_init()` 装 dispatcher（否则打印 `skip dispatcher install` 直接返回）
- `>=2` → `ksu54_install_table_hooks()` + setuid/sucompat
- `>=3` → 额外 hook `__NR_read`/`__NR_fstat` + 注册 `input_event_kprobe`

我们之前一直只传 `kln`，等于 level=0，**根本没装 hook**。

**源码与构建**

仓库 `awjdxcy/ghostlock-pju110-4.19-ksu` 的 `src/ksu/` 就是 4.19 适配副本（GPL-3.0），
`BUILD.md` 明确三条：`-ffixed-x18`、空 `Module.symvers`、未定义符号表要和上一版一致。
用 clang + ld.lld 对 Pad 树（`/home/dev/sm8250-kern`）编译，只需补一个 `strscpy_pad`
（4.19.157 没有，4.19.191 的 vendor 树回移了）。

产物 `modules/pad-4.19.157-ksu.ko`：

```
vermagic = 4.19.157-perf+ SMP preempt mod_unload modversions aarch64   ✓ 与设备逐字一致
__this_module = 0x340 = 832                                          ✓ 与探针实测一致
参数 = allow_shell block_modules bundled enforce_after force_feature kln level norc
```

**但 supercall 仍然不通**

部署对方同款 `libksud.so`（md5 `b7b87ee9…`，uapi 5）到 `/data/adb/ksud`，
`insmod d.ko kln=0xffffff924679e718 level=3` → `RC=0`、`Live`，
`ksud feature check adb_root` 仍报 `could not retrieve kernelsu driver fd`。

**最反常的一点：加载后内核一条日志都没有**

`dmesg -C` 清空 → insmod → 等 3 秒 → 只有 OPPO 充电日志，**零条模块相关记录**。
连 `kernelsu: loading out-of-tree module taints kernel` 这种内核自己打的都没有。
把 `pr_info` 改成 `pr_err` 重编也一样。所以**不能靠 dmesg 定位**。

**下一步最可疑的方向：rk9 与 kernelsu 抢 syscall 表**

Pad 上的流程是 p7 → rk9.ko（`rk9(OE)` 在模块列表里），rk9 自己就 patch 系统调用表；
而对方的 A2x 环境用的是 `gl_v112`，**没有 rk9**。
两个模块同时改 syscall 表，后装的那个可能被覆盖或直接失败——
这也解释了「模块 Live、无日志、supercall 不通」。

验证办法（有代价）：`rmmod rk9` 后再装 kernelsu。但 rkshd 依赖 rk9，卸载后
root 通道可能直接失效，而本次开机的 `init_cred` 预算已用掉，**必须重启才能重来**。
所以这次没有擅自做，等确认。

---

### OPPO Pad 实机验证（20:00~20:40）— 模块是空壳，管理器版本也不对

**现场结果**

| 项 | 值 |
|---|---|
| 隧道 | VPS 27790 单监听（清掉两个僵尸会话后恢复正常） |
| 设备 | OPD2101 / Android 14 / 内核 `4.19.157-perf+` |
| 提权 | p7 成功：`scontext=u:r:kernel:s0`、`Uid: 0`、`selinux enforce=0` |
| root 通道 | `/data/local/tmp/rksh -c 'id'` → `uid=0(root)`，`Seccomp: 0`（无过滤器） |
| ko 加载 | `insmod` / `ksud insmod` 都 `RC=0`，`kernelsu 143360 0 - Live` |

**但 supercall 完全不通**

```
$ ksud feature check adb_root
Caused by: could not retrieve kernelsu driver fd
$ ksud feature get su_compat
Caused by: could not retrieve kernelsu driver fd
```

跑这个的是 **uid 0 + Seccomp 0** 的进程，所以不是权限、不是 seccomp。

**排除了的错误方向**

| 假设 | 实测 |
|---|---|
| `sizeof(struct module)` 不匹配 | **错**。用 Pad 的树编探针实测 = **832**，与 ko 完全一致 |
| vermagic 不匹配 | **错**。逐字相同 |
| kln 传错地址 | **错**。传 `0xffffff924679e718` = 实际 `T kallsyms_lookup_name` |
| 模块参数名不对 | **错**。`llvm-nm | grep __param` 显示确有 `kln`（还有 allow_shell/block_modules/bundled/enforce_after/force_feature/level/norc） |
| 这棵树编出来的模块跑不起来 | **错**。同 config 编的 `probe.ko`（`char[sizeof(struct module)]`）`insmod RC=0`，`probe 16384 0 - Live` |

**关键线索：ko 里带 ksu54 代码**

```
KernelSU: ksu54: level=%d, skip dispatcher install
KernelSU: patch syscall %d failed
```

这个 ko 来自 5.4 的移植分支（`ksu54`），只是拿去用 4.19 头文件编了一遍。
它的 hook 靠「patch syscall 表 / 装 dispatcher」，在 4.19.157 上这一步显然失败了，
所以模块 `Live` 但没有任何入口。

**管理器侧另一个问题**

用户提供的「他们的管理器」APK，实测 sha256 与本机已装的那份**完全相同**：
`me.weishu.kernelsu` 32715 / `v3.3.1-35-gbec9c134`。

但用户截图里能用的那台（OPPO A2x，4.19.191+）显示：

```
工作中 [越狱模式]
版本：32715-6
管理器版本 v3.3.1-47-gdbb73665 (32727-6)
```

**管理器是 32727，不是 32715。** 32715 打开只显示「不支持 / KernelSU 现在只支持 GKI 内核」——
它对非 GKI 的 LKM 模式（越狱模式）没有处理逻辑。

**结论**

要复现别人的成功，两样都得对：
1. **ko 必须是为这颗内核真正移植过的**（不是 5.4 分支套 4.19 头）
2. **管理器要 32727 或更新的**（32715 不识别越狱模式）

**顺带清掉的残留**
`/data/local/tmp/kernelsu.ko` 是 13:19 那个 238,760 字节的 strip 版，已确认不是这次加载的文件；
这次实际用的是 `/sdcard/Download/kernelsu.ko`（4,195,232 字节原始版）。

---

### v8.0（70）— 5.4 引擎按完整内核 release 分版，加入 Reno7 / PFJM10 引擎

**改了什么**
- 新增资源 `assets/ghostlock54-r7`（360,192 B，md5 `953a8449b1d8ad2c897ef2085c97e499`），
  即上游 `54gay/ghostlock54-OPPO-Reno7-5G` v12.4 的单文件二进制
- `MainActivity.kt` 新增 `fiveFourEngineAsset()`，按**完整内核 release** 选 5.4 引擎：
  `5.4.254-qgki-gaa96eae94d32` → `ghostlock54-r7`；`5.4.254-qgki-g9ef4a332d496` → `ghostlock54`；
  其它 5.4 build → 返回空串，`runGhostlock54()` 直接拒绝执行
- `missingPackagedAssets()` / `extractAssets()` 改用这个选择器，不再无条件要 `ghostlock54`
- 版本 7.9(69) → 8.0(70)

**为什么**
之前 5.4 分支只按 `kernelVer.startsWith("5.4")` 二选一，包内只有 A93（g9ef4）那一份引擎。
拿它跑 Reno7（gaa96）不是"跑不通"这么温和 —— 越界写原语会把 8 字节 0 写到按 A93 算出来的
内核地址上，轻则 Oops，重则 panic 重启。两个 build 的 `init_cred`（0x2caf850 vs 实测一致）
和 `futex_wait_requeue_pi` 等地址虽然这次核对下来相同，但**不能靠这个巧合过日子**：
只要任何一次 OTA 换了内核，两机型就会分叉，必须靠 release 串严格区分。

**怎么验证的**
- 从 `PFJM10_11.F.74` 全量包只取 boot（Range，192 MB），`IKCFG` 解出真实 config 7108 行，
  `vmlinux-to-elf` 从 Image 重建 189,534 个 kallsyms 符号，逐条核对上游 `ghostlock54.c` 的 8 个常量：
  `_text` / `init_task` / `init_cred` / `selinux_state` / `futex_wait_requeue_pi` /
  `sel_read_enforce` 全部 0 偏差，`OFF_LOG_BUF` 落 `.bss`、`OFF_TASK_STATE` 落 `.text`
  ⇒ 新固件的内核与上游 v12.4 的目标内核是同一份，二进制可直接用
- `aapt2 dump badging`：versionCode 70 / versionName 8.0；APK 内 `assets/ghostlock54-r7`
  md5 与校验过的上游产物一致

**没验证的**
- 没有 PFJM10 真机，没跑过一次完整提权；APK 只验证了编译、打包、资源完整性。
- 用户态唯一风险点仍是 `perf_event_paranoid`（需 ≤2），`probe.sh` 里已采集，上机先看这一项。

---

### Reno5 Pro（MTK 4.19.191）按设备树核对 — 量出 sizeof(struct module) = 960

**做了什么**
- 从用户给的 tarball 解出 MTK 内核树：`/home/dev/mt6889-kern`（1.4 GB，`VERSION=4 PATCHLEVEL=19 SUBLEVEL=191`）
  - 仓库：`oppo-source/android_kernel_oppo_mt6889`，分支 `oppo/mt6889_t_13.1.0_reno5_pro`
  - git clone 试了两次都失败（13min `early EOF` / 28min 超时），最终由用户手动下 tarball
- 从 `ota/ren5p-Image` 的 `IKCFG` 段挖出设备 config（6117 行）
- **实测 `sizeof(struct module) = 0x3c0 = 960`**（编译探针模块读出，不是猜的）

**这是本次最重要的结论**

| 对象 | sizeof(struct module) |
|---|---|
| **Reno5 Pro 实机（量出来的）** | **960 (0x3c0)** |
| `kernelsu-mtk419-uapi5.ko`（现在的通用件） | **1024 (0x400)** |
| `kernelsu-reno6pro-4.19.191.ko` | 1024 (0x400) |

内核 `setup_load_info()` 里有一条硬检查：
`sechdrs[index.mod].sh_size != sizeof(struct module)` → 直接 `-ENOEXEC`。
**1024 ≠ 960，所以现有的通用 ko 在 Reno5 Pro 上必然装不上**，报错就是
`insmod: failed to load ...: Exec format error`，且 dmesg 里什么都没有
（还没走到 check_modinfo 就拒了）。

**Reno5 Pro 的两个额外约束**
1. `# CONFIG_KALLSYMS_ALL is not set` —— kallsyms 里只有函数，没有数据符号。
   `ren5p.elf` 里实测：`selinux_state` / `init_task` / `init_cred` 全部 **0 命中**，
   `kallsyms_lookup_name` / `module_layout` 命中。
   → `kln` 那套只能查函数，改 `init_cred` 这类变量必须用档案里的硬编码偏移：
   `GL_INITCRED=0x2109bb0`、`GL_SELINUX=0x277e218`、`GL_SIGENFORCE=0x2115fe0`
2. `CONFIG_MODULE_SIG_FORCE=y`（Pad 也是）。但 Pad 上 22:15 那次 insmod 成功过，
   而档案里有 `GL_SIGENFORCE` —— 说明 **p7 提权时会把 sig_enforce 清零**，
   所以签名不是绝对阻塞，前提是 p7 先跑通。

**交付：probe-ren5p-4.19.191.ko**（76 KB）
一个只有 `char[sizeof(struct module)]` 的探针模块，vermagic 与设备**逐字一致**：
`4.19.191+ SMP preempt mod_unload modversions aarch64`，0 个未定义符号。
把它 insmod 上去，能加载就同时证明了三件事：vermagic 对、struct 大小对、p7 确实关了签名。

**KSU 3.3.1 在 4.19 上编不过（已知）**
卡在 `infra/file_wrapper.c`：`file_operations.remap_file_range`（4.20+）、
`.iopoll`（5.1+）、`security_inode_init_security_anon`（5.8+）、`selinux_inode()`。
这不是几处 `#if` 能绕的，属于要真实移植的量。已修掉前置的
`nofault`/`pgtable`/`MODULE_IMPORT_NS`/`version.h` 顺序/`naked` 函数返回类型五处。

**构建环境的坑（MTK 树比 sm8250 更多）**
- 缺失 Kconfig 引用 **53 处**才收敛（sm8250 是 11 处），`.config` 6117 → 5916 行
- `include/linux/sched_assist` 是个**指向 `vendor/oplus/kernel/...` 的断链**，
  该目录在 `android_kernel_modules_and_devicetree_oppo_mt6889` 仓库里。
  建实体目录 + 6 个空桩头解决
- `CONFIG_LOCKING_PROTECT=y` 会让 `percpu-rwsem.h` 引用 `record_locking_info`，关掉
- `flask.h` 不在 top-level make 的目标里，要手动编 `scripts/selinux/genheaders/genheaders`：
  `classmap.h` 有 `#error New capability defined` 守卫（宿主头文件比 4.19 新），
  得复制一份去掉那行再过 `-I` 指过去
- `CONFIG_MODVERSIONS=y` 要求先 `make scripts` 生成 `genksyms`
- tarball 没有 git 元数据，`utsrelease.h` 缺末尾的 `+`，
  要手写 `include/config/kernel.release` 和 `UTS_RELEASE`

---

### v7.9（69）— 新增 ADB 命令行接口

**改了什么**
- 新文件 `AdbReceiver.kt`：有序广播接收器，用 `setResultData` 把结果同步回给 adb
- manifest 注册 `com.xxl.ksuhelper.CMD`，exported、不加权限
- `docs/ADB.md` 写清用法

**为什么**
原来只能用 `am start --es run root` 触发，结果只在界面日志里，脚本没法读。
现在短命令走广播同步返回，适合写进批处理。

**命令集**
| 命令 | 作用 |
|---|---|
| `status` | 设备/内核/Shizuku/模块/rksh/.glhold/ko 一览 |
| `appid` | 包名与 uid（写 allowlist 用） |
| `shell` | 执行命令，rksh 存在时自动 root |
| `kos` | 列出 Download 下 ko 及 vermagic |
| `clean` | 删 .glhold + ko 缓存 |
| `doc` | 内置说明 |

长任务（提权 40s、探测 60s）超出广播 10s 限制，仍走 `am start --es run root|probe`。

**踩的坑**
manifest 注释里写了 `--es cmd status`，**XML 注释不允许出现连续两个减号**，
`ManifestMerger2` 直接报 `Error parsing AndroidManifest.xml`，
但 gradle 的 `-q` 模式只输出一行 `FAILED`，一开始误以为是别的问题。
以后改 manifest 后先跑 `python -c "xml.dom.minidom.parse(...)"` 校验。

---

### v7.8（68）— ko 改为外置，APK 从 30 MB 回到 16 MB

**改了什么**
- 从 `assets/` 移除 `kernelsu-qcom157-uapi5.ko`、`mtk419-uapi5.ko`、`sm7325-uapi5.ko`
  （4.19 MB + 4.45 MB + 5.66 MB = 14.5 MB，而用户只用得上其中一个）
- `bundledKo()` 不再从 APK 解包，改为「手动指定 → Android/data/.../ko/ → 报错指引」
- `missingPackagedAssets()` 不再要求 ko（否则缺 ko 会阻断整条提权链）

**为什么**
v7.3 把 ko 塞进 assets 是因为发现 `pickBundledKo()` 返回的文件名在 APK 里不存在
（内置路径一直是断的）。但把三个 4~5 MB 的模块全打进去，安装包从 16 MB 涨到 30 MB，
而**每台设备只用其中一个**。现在改为外置，由用户按机型放。

**现在的 ko 查找顺序**（代码里三处，优先级从高到低）
1. 界面「模块路径」手动指定
2. `/sdcard/Download/*.ko` —— **推荐**，`autoKsuSetup` 会逐个比对 vermagic 后选用
3. `Android/data/<pkg>/files/ko/<asset>` —— 由 `bundledKo()` 检查

**用户需要做的**
把对应机型的 ko 改名为 `kernelsu.ko` 放到 `/sdcard/Download/`。
Pad（4.19.157）用 `modules/kernelsu-qcom157-uapi5.ko`。

**同时去掉了版本强校验**（用户明确要求）
ko 必须按设备树单独编，同一机型不同 build 也可能只差 localversion 后缀，
版本串对不上不代表不能用。现在的行为：
- `/sdcard/Download/*.ko` 全部列出，逐个打印 `vermagic / 本机`，标注一致与否，**取第一个用**
- 内置/指定的 ko 即使 vermagic 不一致也只打警告，**继续 insmod**
- 是否换 ko、放哪个，由用户自己判断

---

### v7.7（67）— 按子 agent 审查修 6 条

审查发现的 6 个真问题，逐个说：

**#13 `bootId()` 的哨兵会锁死设备（最严重）**
`bootId()` 失败时返回 `"-"`，而闸门里 `got.contains(want)` 去匹配 `.glhold` 里的 UUID
—— **UUID 本身含连字符**，`contains("-")` 恒真。一旦 `/proc/.../boot_id` 读不到，
之后**每次开机都会判「本次已跑过」并永久拒绝执行**（只能重启 + 手删文件恢复）。
判断里写的 `want == "未知"` 是死代码（返回值从来不是这个）。
改：`bootId()` 只在匹配 UUID 正则时返回，否则返回空串；闸门遇到空串**放行**并告警。

**#3 ko 的磁盘缓存不校验，v7.6 的回滚在真机上不生效**
`bundledKo()` 只要 `Android/data/<pkg>/files/ko/xxx.ko` 存在且非空就直接用。
v7.3~v7.5 已经把 strip 过的坏 ko 释放到这个路径，升级到 v7.6 后**仍然拿旧文件去 insmod**。
这正是「换了正确 ko 却还报 ENOEXEC」的隐形原因。
改：比对 `assets.openFd().length` 与本地大小，不符则删除并重新释放。

**#2 闸门在成功后才写，失败可以无限重跑**
`markExploited()` 原本只在 checkRoot 成功或模块已加载后调用。但**跑失败同样扣一格**
`init_cred.usage`（只有 4 格），连点四次就扣到 0 → 延时重启。
改：`p7` 和 `ghostlock54` **执行前**就落标记。

**#5 gl54 推送判定是假阳性**
`push.out.contains("ghostlock54")` —— 失败的 `cp` 报错信息里本身就带这个文件名，
判定永远通过。之后 `nohup ./ghostlock54` 找不到文件，但 `echo submitted` 照常输出，
空转满 75s 才超时。
改：比对远端/本地字节数 + `[ -x ]`。

**#9 ksucheck 的 GRANT_ROOT 失败是预期，却提示成「root 未下来」**
`ksucheck` 走 Shizuku 的 shell 通道（uid 2000），而 allowlist 只写 Manager 和本 App 的 uid
—— `GRANT_ROOT` 必然 EPERM。原提示让用户去看这一行，等于把正常模块读成坏的。
改：只采信 `GET_INFO ✓` 作为「supercall 通」的证据，并说明 GRANT_ROOT 失败属预期。

**#6 模块在位分支在 5.4 上仍会走 autoKsuSetup**
v7.1 只改了 `runGhostlock54`，漏了 `doRoot` 里「`/sys/module/kernelsu` 已存在」这条分支
—— 它无条件调 `autoKsuSetup()`，而那里面全是 rksh 调用。改：按内核版本分叉。

**已记录但本次未修**（写下来防止遗忘）

| # | 问题 | 影响 | 为什么不修 |
|---|---|---|---|
| 1 | APK 里只有 A93 版 `ghostlock54`，Reno7 等 5.4 机型没有任何门禁 | 跑到别的 5.4 机型上就是往错地址写，大概率 panic | 需要为 gaa96 再编一份引擎，工作量大 |
| 4 | Activity `exported="true"`，intent 的 `ko`/`cmd` 参数会拼进 shell | 同设备任意应用可触发提权链 | 调试要用 `am start`，改成 false 会挡住自己；应先做路径校验而不是直接关 |
| 7 | `missingPackagedAssets` 是全局清单，4.19 缺个 `probe-perf` 也会阻断整条链 | 精简便打包时误伤 | 需要按内核分两组必需件 |
| 8 | `matchesKernelRelease` 严格全等 + `kernelVer` 未 trim + 无降级 | `os.version` 带后缀时把可用 ko 全判死 | 需要先确认 Pad 上 `os.version` 的真实形态 |
| 10 | v7.5 扩的 dmesg 关键词只在 4.19 路径生效 | 5.4 的 insmod 失败看不到内核原始报错 | 5.4 无 rksh，需写一个降级版 dmesg 读取 |
| 11 | `loadKsu`/`deployKsud`/`runShellCmd` 缺 finally | 异常后 `running` 永久 true，按钮全灰 | 辅助路径，最坏重启 App |
| 12 | 「已跑过漏洞」分支不装 KSU 也不验证 | 提权成功但装载失败时重试无动作 | 需要配合 #2 的语义重新设计 |

---

### v7.6（versionCode 66）— 回滚 ko 的 strip/删段

**改了什么**
- `assets/kernelsu-qcom157-uapi5.ko`、`mtk419`、`sm7325` 全部换回 `modules/` 下的**原始文件**（未 strip、未删段）
- `assets/rk9-54.ko` 恢复原始 216,904 B，只把 vermagic 字符串 `gaa96` 改成 `g9ef4`
- `tools/probe/check_ko.py` 纠正「空 `__versions` 会拒绝加载」的错误结论

**为什么改（这是我今天的错误）**

v7.3 时我认为「`__versions` 段存在但为 0 字节」会让内核报 `no symbol version for module_layout` 并拒绝加载，
于是对所有 ko 做了 `strip --strip-debug` + `llvm-objcopy --remove-section=__versions`。

实机结果：`insmod: failed to load ...: Exec format error`，dmesg 里连一条记录都没有。

后来直接读源码（`/home/dev/sm8250-kern/kernel/module.c`）才看到真相：

```c
static int check_version(...)
{
    if (versindex == 0)
        return try_to_force_load(mod, symname) == 0;

    num_versions = sechdrs[versindex].sh_size / sizeof(struct modversion_info);
    for (...) { ... }

    /* Broken toolchain. Warn once, then let it go.. */
    pr_warn_once("%s: no symbol version for %s\n", info->name, symname);
    return 1;        // ← 放行！
}
```

**空 `__versions` 只是 `pr_warn_once`，然后返回 1 放行**。模块照常加载。

而 `strip` + `objcopy` 这两步把 ELF 结构弄坏了 —— `elf_validity_check()` 返回 `ENOEXEC`，
所以 dmesg 里什么都没有（内核在读 ELF 头时就拒了，还没到 `check_modinfo`）。

**验证**
- `check_ko.py` 对原始文件：157 个符号全部命中，`__versions` 0 字节但不再判定为阻断
- v7.6 换回原始文件后待实机验证

**教训**
> 涉及内核行为，**先读源码再下结论**。我对 `check_version` 的记忆是错的，
> 而且没有先去 `kernel/module.c` 确认就批量改了 5 个文件。

---

### v7.5（65）— dmesg 关键词扩充

**改了什么**：`dmesgKsu()` 的关键词加上 `version magic|ELF structures|unknown symbol|disagrees about|invalid module|module verification|taints kernel`。

**为什么**：insmod 失败时最有用的那行 `module has invalid ELF structures` 不含原来的任何关键词，被 grep 过滤掉了，日志显示「dmesg 里没有 kernelsu 相关记录」——误导排查方向。

---

### v7.4（64）— kptr_restrict 导致 kln 读不到

**改了什么**：两处 insmod 前先用 `rksh` 把 `/proc/sys/kernel/kptr_restrict` 降到 0，再读 `kallsyms_lookup_name`；`grep` 限定 ` T ` 类型。

**为什么**：实机日志（OPPO Pad）显示提权成功（`uid=0(root)`），但 `kln` 为空。

根因：`kptr_restrict=2` 时只有带 `CAP_SYSLOG` 的进程能读到真实地址，而 `kln` 是用 `sh()` 读的
—— **`sh()` 走 Shizuku 的 shell 通道，uid 恒为 2000**，即使前面 `rksh` 已经拿到 uid=0，
读取这一步仍是 shell 在跑。

**验证**：v7.4 实机日志 `kptr_restrict -> 0`、`kln=0xffffffa2cf79e718` —— 拿到了。

---

### v7.3（63）— 补齐内置 ko（但处理方式错了，见 v7.6）

**改了什么**：把 `modules/` 下的 ko 复制进 `assets/`，并纳入 `missingPackagedAssets` 检查。

**背景**：`pickBundledKo()` 返回 `kernelsu-qcom157-uapi5.ko`，但这个文件**不在 APK 里**，
`bundledKo()` 会在 `assets.open()` 处抛异常 —— 内置 ko 这条路一直是断的，
4.19 机型全靠 `/sdcard/Download` 兜底。

**错误**：同时做了 strip + 删段（见 v7.6）。

---

### v7.2（62）— 合并 6.1sol 的 fail-closed 改动

**改了什么**
- `matchesKernelRelease`：vermagic 必须与 `uname -r` **完整相等**（原来 `vm==null` 放行 + 双向 `startsWith`）
- `isRootIdentity`：`uid=0` 整词匹配 + 退出码为 0
- `missingPackagedAssets` + `auditPackagedAssets` + UI 按钮
- `startRoot` 三态结果：`ok` 只代表引擎没报错，root 另需 `checkRoot` 核验
- 新增 `DiagnosticValidationTest`（8 例）

**为什么**：原逻辑会把 `5.4.254` 当成匹配 `5.4.254-qgki-g9ef4…`（拿错 ko 会 panic）；
`euid=0`、`uid=00`、超时输出里的残留 `uid=0` 都会误判为 root。

**验证**：8 tests / 0 failures。

---

### v7.1（61）— 5.4 路径不走 autoKsuSetup

**改了什么**：`runGhostlock54()` 成功后只调 `verifyKsu()`，不再调 `autoKsuSetup()`。

**为什么**：`autoKsuSetup()` 里所有 root 操作都走 `/data/local/tmp/rksh`，
而 rksh 依赖 rkshd + rk9.ko，**gl54 不会启动 rkshd** —— 5.4 上根本没有 root shell。
（v7.0 时我把 5.4 接进 autoKsuSetup 是错的。）

---

### v7.0（60）— kln 为空时中止 insmod

**改了什么**：两处 insmod 前，`kln` 取不到就 `return`，不装模块。

**为什么**：模块不内建 kallsyms 表，符号解析全靠 `kln` 传入的 `kallsyms_lookup_name`。
没传 kln 时 `insmod` 仍会成功、`/proc/modules` 也有 `kernelsu`，
但所有 `kallsyms_lookup_name` 返回 0 —— su / allowlist 全部静默失效，是**空壳模块**。

---

## 关键结论（已验证）

| 主题 | 结论 |
|---|---|
| OPPO Pad 内核 | `4.19.157-perf+`，`CONFIG_KALLSYMS_ALL=y`、`MODVERSIONS=y`、`MODULE_SIG=y`，**无 CFI / 无 PAC** |
| Pad 的 `sizeof(struct module)` | 期望值未知（原始 qcom157 的 ko 是 **832**，但加载失败，见下） |
| `kallsyms_lookup_name` | Pad 内核里有；`kptr_restrict=2` 时必须先降到 0 才能读 |
| 空 `__versions` 段 | **只是 warning，内核放行** —— 不要为此 strip/删段 |
| 提权 | p7 在 Pad 上稳定成功（`uid=0(root)`，第 6s 就绪） |
| allowlist | 走 `pushFile` 的 stdin 兜底能成功写入 1576 字节 |
| 22:15 那次 `insmod RC=0` | 用的是 `/sdcard/Download/kernelsu.ko`，**不是**内置的 |

## 待查

1. **`sizeof(struct module)` 到底该是多少**：原始 qcom157 是 832，仍 `ENOEXEC`。
   需要 `adb pull /sdcard/Download/kernelsu.ko` 量一下那个能加载的值。
2. 内核源码树在 `/home/dev/sm8250-kern`（1.2 GB，`oppo/sm8250_u_14.0.0_oslo`），
   config 是 `/tmp/pad-config.txt`（从 `boot.img` 的 `IKCFG` 挖出，6687 行）。
   **`make modules_prepare` 因 `asm-offsets.s` 失败而未完成**，但 `timeconst.h` / `bounds.h` / `autoconf.h` 都已生成。
3. KSU 官方 v3.3.1 与 4.19 不兼容（`nofault` API、`MODULE_IMPORT_NS`、`security/selinux/include/flask.h`），
   需要非 GKI fork 才能编出 4.19 版。

## 构建环境的坑（一次性记录）

- OPPO sm8250 主仓库的 Kconfig 有 **11 处**指向 `android_kernel_modules_and_devicetree_oppo_sm8250` 的断链
  （`kernel/sched_assist`、`block/blk`、`drivers/soc/oplus` 等）。
  **不要批量按路径存在性注释** —— `source` 路径是相对当前 Kconfig 目录的，批量处理会把
  `drivers/`、`fs/`、`net/` 一起切断，`.config` 从 6687 行掉到 636 行。
  正确做法：跑 `make syncconfig`，报错就**只注释报错的那一行**，迭代到收敛。
- 内核脚本调用 `python`（不是 `python3`），Ubuntu 22.04 需要建软链。
- `scripts/gcc-wrapper.py` 是 Python 2 语法（`print "x"`）且 `subprocess` 返回 bytes，
  需 `2to3` + `universal_newlines=True`。
- `CONFIG_LOCKING_PROTECT=y`（OPPO 私有）会让 `percpu-rwsem.h` 依赖 `sched_assist_locking.h`，编译模块前关掉。
