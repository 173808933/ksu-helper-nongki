# KSU 助手（KsuHelper）

通过 **Shizuku** 取得 `shell`（uid 2000）身份，在手机端执行 4.19 内核的临时 root 提权链，
并在提权成功后加载 KernelSU 模块。

- 包名 `com.xxl.ksuhelper`
- minSdk 26 / targetSdk 37
- 当前版本 **v3.1**（versionCode 22）
- 产物：`C:/Other/Andriod/ksu/KsuHelper_v3.1_debug.apk`（35.5 MB，debug 签名，内置 4 组 kernelsu.ko）

---

## 1. 为什么需要 Shizuku

提权链的第一步要给线程设置实时调度策略（`sched_setscheduler(SCHED_FIFO/RT)`），
这一步需要 `CAP_SYS_NICE`。普通 App（uid 10xxx / `u:r:untrusted_app:s0`）没有这个能力，
漏洞在 App 进程里根本触发不了。

`adb shell` 有（实测 Find X3 通过），而 **Shizuku 的进程就是由 adb/无线调试拉起的 shell 进程**，
fork 出来的子进程继承同样的 uid 与 capability 集合，所以经由 Shizuku 执行等价于 adb shell 执行。

> 因此「第一次」仍然需要电脑：Shizuku 本身要靠 `adb shell sh /sdcard/.../start.sh`
> 或「无线调试」启动一次。之后只要 Shizuku 还活着（重启前），就能在手机上点按钮完成。

## 2. 前提

| 项 | 要求 |
|---|---|
| Shizuku | 已安装、已启动（服务运行中）、已授权本应用 |
| 设备 | 4.19 那批：Find X3 / 一加 9R / Reno6 / Reno8 / A2m / A2 Pro / A97（小米 12X 静态通过但未验证）；5.4：Reno7 PFJM10（v3.1 起支持） |
| 系统状态 | **本次开机尚未提过权**（漏洞一次开机只能成功一次） |
| 存储 | 外部私有目录可写（用于释放组件） |

## 3. 使用步骤

1. 安装 APK → 打开 → 顶部状态应显示 `Shizuku 服务 运行中`
2. 点「授权 Shizuku」→ 手机弹窗点允许（状态变 `已授权`，服务 UID 2000）
3. 先点「只推送组件（自检，不跑漏洞）」：
   - 确认 4 个组件已落到 `/data/local/tmp`，并打印 `uid: uid=2000(shell)`、SELinux、CapEff
   - 这一步不触碰内核，可安全重复
4. 点「一键提权」：释放 → 推送 → 执行 `p7` → 轮询 `/data/local/tmp/rksh.sock`（最多 72s）
5. 成功后状态变 `root 状态 已就绪`，可在「Root 命令」里执行任意 root 命令
6. 「KernelSU」区：填 ko 路径（默认 `/sdcard/Download/kernelsu.ko`）→「加载模块」；
   填 ksud 路径 →「部署 ksud 到 /data/adb」

## 4. 提权链在做什么

```
assets/p7      (806 KB)  CVE-2026-43499 futex PI 提权，进程内提权到 uid 0 后加载模块
assets/rk9.ko  ( 15 KB)  极简内核模块：init 里 call_usermodehelper 拉起 rkshd，并暴露 /proc/rk9
assets/rkshd   (2.2 MB)  uid 0 + u:r:shell:s0 的常驻 daemon，监听 /data/local/tmp/rksh.sock
assets/rksh    (2.2 MB)  客户端，rksh -c '命令'
```

执行命令（与 PC 侧 `root.sh` 完全一致）：

```
GL_ATTR=cred GL_AUTO=1 GL_PERM=1 GL_ZEROSIG=1 GL_RETRY=2 GL_RCRED=1 \
GL_KO=/data/local/tmp/rk9.ko nohup ./p7 > /sdcard/root.log 2>&1 &
```

`GL_AUTO=1` 让 p7 自己从 `/proc/kallsyms` / `init_task` 推偏移，所以 env 里不含
每台机器不同的地址（`GL_INITCRED` 等），这是它能跨机型使用的原因。

## 5. 失败排查

| 现象 | 原因 / 处理 |
|---|---|
| `Shizuku 服务 未运行` | Shizuku 没启动：PC 上 `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh` |
| 推送时报 `cp 失败` 后走 stdin | 正常兜底（Android/data 被限制读取时）；两条都失败才是问题 |
| 会话说 `sched_setscheduler` EPERM | Shizuku 未以 shell 身份运行（例如被降权），或该 ROM 收紧了 capability |
| 提权超时 | 看 App 日志里打印的 `/sdcard/plog.txt`、`/sdcard/root.log`；本次开机可能已用过一次 |
| shell 失联 | 内核没扛住，长按电源 12s 重启后重试 |

组件清单与日志（只读排查）：

```
/data/local/tmp/{p7,rk9.ko,rkshd,rksh}
/sdcard/plog.txt        p7 的运行日志
/sdcard/root.log        p7 的 stdout/stderr
/proc/rk9               模块接口（存在=模块已加载）
/data/local/tmp/rksh.sock
```

## 6. 构建

```bash
export JAVA_HOME=/c/Other/Andriod/toolchain/jdk/jdk-21.0.12.1+1
export ANDROID_HOME=/c/Other/Andriod/toolchain/android-sdk
./gradlew assembleDebug
```

两个坑（已在本工程里绕开）：

1. **AGP 9 内置 Kotlin**：不能再 `apply plugin: org.jetbrains.kotlin.android`，否则直接报错
2. **Shizuku API 13.1.5 把 `Shizuku.newProcess` 改成 private**：用 **13.1.0**（签名公开，与 13.x 服务端协议兼容）

本工程不需要 NDK / CMake。

## 7. 边界

- 不改 boot / 不写分区 / 不碰 AVB，重启即失效
- 唯一写入点：`/data/local/tmp`（组件）、`/sdcard`（日志）、提权后 `insmod` 与 `/data/adb`（ksud）
- 提权成功一次后 `init_cred` 会被污染，本次开机不能再跑第二次漏洞；重启恢复

---

## 8. 实测记录：一加 9（LE2110 / Android 16 / 5.4.254-qgki）

用 root shell + adb 触发，不走手动点击。结论如下。

### 8.1 通过的部分

| 项 | 结果 |
|---|---|
| Shizuku 服务拉起（shell 身份） | ✅ `shizuku_server` pid 9202，uid shell |
| Binder 连通 / 状态显示 | ✅ 「运行中」「服务 UID 2000 (shell)」 |
| `Shizuku.newProcess` 执行 | ✅ `uid=2000(shell) ... context=u:r:shell:s0` |
| 组件推送（4 个） | ✅ cp 直传成功，落到 `/data/local/tmp`，权限正确 |
| 授权弹窗 + 回调 | ✅（见 8.3） |
| 一键提权 / 加载模块 | ⏸ 未跑（这台是 5.4，跑 4.19 的链没有意义） |

### 8.2 修掉的一个真实 bug

首次 `sh()` 全部失败：

```
启动进程失败: IllegalArgumentException: process hasn't exited
```

原因：`ShizukuRemoteProcess` 只重写了 `waitFor()`，而 JDK 的
`Process.waitFor(timeout, unit)` 默认实现靠 `exitValue()` 轮询，**且只捕获
`IllegalThreadStateException`**；Shizuku 抛的是 `IllegalArgumentException`，直接穿出。

修法：改用 `ShizukuRemoteProcess.waitForTimeout(long, TimeUnit)`，并加轮询兜底（`waitProc()`）。
这个 bug 会让整个 App 在任何设备上都不可用，属必修。

### 8.3 授权流程验证

用 `probe` 变体（包名 `com.xxl.ksuhelper.p2`，权限天然未授予）走完整流程：

```
am start -n com.xxl.ksuhelper.p2/com.xxl.ksuhelper.MainActivity --es run probe
↓
[auto] 无 Shizuku 权限，发起授权请求
↓  （Shizuku 弹出「要允许KSU 助手使用 Shizuku吗?」/「始终允许」「拒绝」）
input tap 1251 614
↓
[shizuku] 权限已授予
[auto] 开始执行: probe
[id] uid=0(root)   ← 因为这次服务端是用 root 启动的
```

弹窗、回调、后续自动执行全部正常。

### 8.4 重要限制：这台 ROM 上 shell 模式被 Shizuku 拒绝

用 `shell` 身份启动服务端时，请求授权弹出的是**错误框**：

> Shizuku: adb 权限受限
> 这极有可能是你的设备制造商限制了 adb 的权限。

与实测吻合：

```
grep CapEff /proc/self/status  →  0000000000000000
ulimit -r                      →  0
chrt -f 1 /system/bin/true     →  sched_setscheduler: Operation not permitted
```

**这台 Android 16 的 adb shell 没有任何 capability，连 `pm revoke` 都被拒**
（`SecurityException: Neither user 2000 nor current process has REVOKE_RUNTIME_PERMISSIONS`）。
Shizuku 自检到这一点后直接拒绝授权，所以 shell 模式下无法使用。

改成 root 启动服务端后一切正常（8.3 的结果就是 root 模式）。

### 8.5 对 4.19 目标机的含义

- App 侧无问题：授权、执行、推送都验证过了
- **需要确认目标 ROM 的 adb shell 是否有足够权限**。4.19 那批是 Android 11 时代，
  大概率比 Android 16 宽松，但必须在真机上实测一次（看 Shizuku 是否也弹「adb 权限受限」）
- 如果目标机同样受限，退路是：先用本项目的漏洞拿到 root，再把 Shizuku 以 root 启动
  —— 但那样就不需要 Shizuku 了，直接 `rksh` 即可

### 8.6 adb 触发入口（便于自动化验证）

```bash
adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run probe      # 最小 id 测试
adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run selfcheck  # 推送自检
adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run status     # 状态检查
adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run root       # 一键提权
adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run cmd --es cmd "id"
```

日志同时进 logcat（tag `KsuHelper`）：

```bash
adb logcat -s KsuHelper:I
```

注意：Activity 已在前台时 `onCreate` 不会重跑，需先 `am force-stop`。

### 8.7 第二轮实测（20:30）：授权恢复，但 shell 仍无 RT 权限

用户自行用无线调试启动 Shizuku（`shizuku_server` 以 shell 运行）后：

| 项 | 结果 |
|---|---|
| Shizuku 权限 | ✅ `granted=true`（点「始终允许」后持久生效） |
| `newProcess` | ✅ `uid=2000(shell) context=u:r:shell:s0` |
| 四个组件推送 | ✅ 全部落地，权限 755/644 |
| **`CapEff`** | ❌ **0000000000000000** |
| **`ulimit -r`** | ❌ 0 |
| **`chrt -f 1 true`** | ❌ `sched_setscheduler: Operation not permitted` |

也就是说：**App → Shizuku → shell → 命令执行** 这条链完全通了，
但 shell 在这台 ROM 上没有实时调度能力，而我们的漏洞第一步正是
`sched_setscheduler(SCHED_FIFO)`。

### 8.8 一个坑：这台 ROM 会吞掉第三方 App 的 logcat

`Log.i("KsuHelper", ...)` 从 18:37 之后再也进不了 logcat（`logcat -b all` 里 0 条），
但 UI 和进程都正常。加了一条旁路：日志同时写到

```
/sdcard/Android/data/com.xxl.ksuhelper/files/khelper.log
```

读取（需要 root，因为 Android/data 对它机不可读）：

```bash
adb shell 'su -c "cat /sdcard/Android/data/com.xxl.ksuhelper/files/khelper.log"'
```

这是排查这台设备唯一可靠的日志通道。

### 8.9 新增的调试入口 `sh`

`--es run sh --es cmd "<命令>"` 会以 **Shizuku 的 shell 身份**执行（不经过 root 的 rksh），
专门用来验证 capability：

```bash
adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run sh \
  --es cmd "grep CapEff /proc/self/status; ulimit -r; chrt -f 1 true"
```

区别：`--es run cmd` 走 `rksh`（需已提权）；`--es run sh` 走 Shizuku 的 shell（无需 root）。

### 8.10 静态确认：p7 没有「不依赖 CAP_SYS_NICE」的机制

在二进制里能看到的提权尝试只有两条，日志格式串为证：

```
set_nice ret=%ld errno=%d          → setpriority(PRIO_PROCESS, 0, -20)
sched_setattr ret=%ld errno=%d     → sched_setattr(..., SCHED_FIFO/DEADLINE)
```

另有 `GL_MECH` / `GL_IDENT` / `GL_RETRY` 等开关，但没有第三条调度路径：
两条都需要 `CAP_SYS_NICE`（降 nice 值与设实时策略同权限）。

配合 8.7 的 `CapEff=0` 可以下结论：**在这台 ROM 上，无论走 adb 还是 Shizuku，
漏洞的第一步都过不去**。这不是 App 的问题。

### 8.11 重启后的行为

- Shizuku 服务端随重启消失，需要重新拉起（`sz_starter` 方式或无线调试）
- **授权是持久的**：重装 App 后仍是 `granted=true`，不需再弹窗
- `/data/local/tmp` 里的组件仍在（`/data/local/tmp` 跨重启保留），自检重推一遍即可
- **本机出现一次 App 被清掉**（`pm path` 为空、`dumpsys` 里 versionCode 还在但组件不存在），
  `su -c pm install -r` 重装后恢复。原因未确认，怀疑 ColorOS 清理侧载应用

### 8.12 安全闸：内核版本不匹配时拒绝跑漏洞

2026-11-16 20:36 这台机器重启过一次。事后核查：

| 检查 | 结果 |
|---|---|
| `ro.boot.bootreason` | `reboot`（不是 `kernel_panic` / `watchdog`） |
| `/sys/fs/pstore/` | 空 |
| p7 的 `/sdcard/plog.txt` / `root.log` | **都不存在** |
| adb 断开位置 | `pm install` 返回 Success 之后、`am start` 之前 |

即：那条 `p7 --help` 的命令**很可能根本没发出去**，`p7` 大概率没执行；
App 消失则是「安装刚完成就被重启打断，包状态没落盘」（`pm path` 空但 `dumpsys` 残留 versionCode）。

**但把 p7 指向非目标内核本身是错误操作**——p7 不一定认 `--help`，一旦忽略参数就会跑默认流程。
需要用法信息时应静态提取，不要执行。

现在加了硬闸（`MainActivity.startRoot`）：

```kotlin
val kv = System.getProperty("os.version") ?: ""
if (!force && !kv.startsWith("4.19")) {
    log("!! 拒绝执行：当前内核 $kv 不是 4.19 ...")
    return
}
```

效果（在 5.4 的一加 9 上实测）：

```
[auto] 开始执行: root
!! 拒绝执行：当前内核 5.4.254-qgki-gacce45beef5d 不是 4.19
   提权链只针对 4.19；在其它内核上运行 p7 可能触发 panic 重启
   如确需强跑：am start ... --es run root --es force true
```

界面上的「一键提权」按钮同样受这道闸限制；只有显式带 `--es force true` 才放行。

---

## 9. 设备档案（v1.9）

APK 内置 `assets/profiles.json`，按 `ro.product.model` 自动匹配机型；匹配不到时按内核版本唯一命中；
多个命中则不猜、回退「通用」。界面「设备档案」卡片可手动切换。

| 档案 | 型号码 | 内核 | cred | 备注 |
|---|---|---|---|---|
| OPPO Pad | OPD2101 | 4.19.157 | 0x7e0 | selinux_state 比 Find X3 高 0x24000 |
| OPPO Reno5 Pro 5G | PDSM00 | 4.19.191 | 0x768 | KALLSYMS_ALL=n，只有 3 个地址 |
| OPPO Find X3 | PEDM00 | 4.19.157 | 0x7e0 | PC 侧已实测 |
| OnePlus 9R | LE2100 | 4.19.157 | 0x7e0 | 未真机验证 |
| OPPO A2m | PJU110 | 4.19.191 | 0x768 | CFI 开启 |
| OPPO A2 Pro | PJG110 | 4.19.191 | 0x768 | |
| OPPO A97 | PFTM10 | 4.19.191 | 0x768 | CFI 开启 |
| Xiaomi 12X | PSYCHE | 4.19.157 | 0x7e8 | 未验证 |
| 通用 | - | - | - | 只用 GL_AUTO=1 |
| OPPO Reno7 | PFJM10 | **5.4.254** | - | **本链不支持**，运行会被闸门拦下 |

**注入策略**：默认不写 `GL_*`，只用 `GL_AUTO=1` 让 p7 自行推导（这是 Find X3 实测通过的用法）。
需要时打开「注入档案地址」，会把档案里的 `GL_SELINUX` 等一并写进 env。
两种模式别混用：同一台机器只能用其中一套地址。

## 10. 日志与导出（v1.9）

每条日志都带**时间戳 + 启动后毫秒偏移**：

```
[21:15:53.477 +1572ms] uname   : Linux localhost 5.4.254-qgki ...
[21:15:53.580 +1675ms]   p7 (787 KB) -> /data/local/tmp/p7  OK  115ms
```

`导出日志` 会先抓一份**诊断快照**再落盘，保证导出文件自洽，内容包含：

- 头部：App 版本 / 机型 / device / product / 系统 / 内核 / 当前档案与注入开关 / Shizuku 与 root 状态
- `uname -a`、`boot_id`、`uptime`、`loadavg`、`getenforce`、`id`、`CapEff`、`kptr_restrict`、`dmesg_restrict`
- `/proc/modules`（kernelsu/rk9）、`/proc/rk9`、`rksh.sock`、四个组件的 `ls -l`
- 有 root 时追加 root 侧 `id` / `getenforce` / `dmesg tail`
- `/sdcard/plog.txt`、`/sdcard/root.log` 尾部

落地路径：

```
/sdcard/Android/data/com.xxl.ksuhelper/files/logs/ksuhelper-YYYYMMDD-HHmmss.log
```

读取（该目录对其他 App 不可读，需 root）：

```bash
adb shell 'su -c "ls -l /sdcard/Android/data/com.xxl.ksuhelper/files/logs/"'
adb pull /sdcard/Android/data/com.xxl.ksuhelper/files/logs/<文件>
```

另有持续写入的 `/sdcard/Android/data/com.xxl.ksuhelper/files/khelper.log`（用于 ROM 吞 logcat 的场合）。

---

## 11. 从两份现场日志得到的三个改进（v2.3）

### 11.1 OPPO Pad：p7 用了它内置的 Find X3 地址

日志证据：

```
档案  GL_SELINUX=0x3c88000  GL_SCRATCH=0x3c88800
p7    [off] selinux=0x3c64000 scratch=0x3c64800     ← Find X3 的值
结果  [main] selinux enforce=1  →  模块从未加载，socket 不存在
```

**关键结论：`GL_AUTO=1` 只自动扫描 `cred` 这类偏移，数据符号地址仍沿用
p7 编译期写死的默认值（Find X3 的），它不会自己推导。** 所以：

- Find X3：默认值恰好正确 → 能跑通
- 其它机型：**必须注入档案里的 `GL_*`**，否则 selinux / sig_enforce 全是错的

改进：

| 项 | 之前 | 现在 |
|---|---|---|
| 注入开关 | 默认关 | **匹配到带地址的档案时默认开** |
| 失败诊断 | 只有超时 | 打印 p7 的 `[off]` 行，并与档案逐项比对，不一致直接点出来 |
| 等待策略 | 固定等 72s | 记录 p7 pid，**进程退出即判失败**，立刻抓 plog |

### 11.2 Reno5 Pro：4 份日志是同一次会话，Shizuku 没启动

```
Shizuku binder=false perm=false uid=-1
Shizuku binder=离线 权限=未授予
```

整段日志里没有任何 `[off]`、没有 `p7` 执行记录 —— 一步都没跑。
另外连续导出 4 次，产生了 4 个文件。

改进：

- Shizuku 离线时，界面顶部加红色提示条 + 启动命令
- 导出文件头部写 `注意 Shizuku 未运行，本次导出不含任何执行记录`
- **导出节流 2.5 秒**，避免连点刷文件

### 11.3 Reno5 Pro 还有一个硬障碍：地址只有 3/8

```
GL_CROFF=0x768  GL_REALCRED=0x760  GL_INITTASK=0x20fcc00
缺 GL_INITCRED / GL_SELINUX / GL_SIGENFORCE / GL_PARANOID / GL_SCRATCH
```

原因是该机 `KALLSYMS_ALL=n`，数据符号不在符号表里，工具只能 recover 出 `init_task`。
界面上现在会直接标红 `缺 5 项地址`。

即便 Shizuku 正常，按 11.1 的结论：注入只能给 3 个，其余 5 个仍是 p7 内置的
Find X3 值 —— 大概率同样失败在 `enforce=1`。

**要真正支持这台，需要在 kernel.bin 上恢复那 5 个数据符号**（代码交叉引用法：
`prepare_kernel_cred` 找 `init_cred`、selinux 相关函数找 `selinux_state`、
`module_sig_check` 找 `sig_enforce` 等），属于静态分析工作，可在 PC 侧完成。

---

## 12. 重启/panic 后日志不丢（v2.4）

### 12.1 原来的问题

导出的文件只写**内存里的本次会话**（`logText`）。所以：

- 设备 panic 重启 → App 进程没了 → 内存清空
- 重新打开 App 再导出 → 只得到新会话，**崩溃前那段全部丢失**
- 而磁盘上的 `khelper.log` 其实一直在（每行都 flush），只是没人去读它

Reno5 Pro 那 4 份 1~2 KB 的日志就是这么来的：同一个会话连点 4 次导出。

### 12.2 现在的做法

**① 导出读磁盘，不读内存**

导出文件结构变成：

```
[头部：设备/内核/档案/Shizuku/root/boot_id]
=== 会话（内存，仅本次启动） ===
=== 磁盘历史（含以往重启前的会话） ===      ← 读 khelper.log，App 自己就能读，不需要 Shizuku
=== 最近一次 p7 输出（run-*.log 尾部） ===   ← 读 App 目录下的 p7 日志
```

**② p7 的输出改写到 App 目录**

以前：`> /sdcard/root.log`（重启后要读它必须有 shell）
现在：`> /sdcard/Android/data/com.xxl.ksuhelper/files/run-MMdd-HHmmss.log`

App 对自己的外部私有目录有直接读写权限，**所以 panic 重启后打开 App 就能读到崩溃前 p7 的输出**，不需要 Shizuku。

**③ 会话用 boot_id 标记**

每次启动写一行 `[session] boot_id=...`，重启后 boot_id 会变，能直接分清哪段是崩溃前、哪段是崩溃后。

**④ 日志轮转**

`khelper.log` 超过 2 MB 时，复制到 `khelper.prev.log` 并清空，避免无限膨胀。

### 12.3 验证

同一台设备连续两次启动 + 导出，第二个导出文件里：

```
=== 会话（内存，仅本次启动） ===
[21:41:25.179 +57ms] ========== 启动 ==========
=== 磁盘历史（含以往重启前的会话） ===
[21:41:12.243 +56ms] ========== 启动 ==========      ← 上一次会话完整保留
```

### 12.4 仍然会丢的部分

panic 发生的那一瞬间，**内核缓冲区里还没落盘的最后几行**可能丢（p7 自己写的 `/sdcard/plog.txt` 同理）。
这是内核崩溃的固有特性，任何用户态日志都做不到零丢失。
但相比之前"整段丢失"，现在至少能保住崩溃前的完整前半段。

---

## 13. root 成功 ≠ KSU 可用（v2.5 补上这一环）

### 13.1 现场日志说明了什么

OPPO Pad（21:46）那次：

```
第 6s 就绪
uid=0(root) gid=0(root) ... context=u:r:shell:s0
finit_module ret=0 errno=0        ← 这是 rk9.ko（root daemon 模块）
[off] selinux=0x3c88000 ...       ← 注入了，地址正确
[main] selinux enforce=0          ← permissive 成功
```

**提权确实成功了**，而且验证了「注入档案地址」这个修复是对的。
但整份日志里 `加载 KSU` / `insmod` / `ksud` 各 **0 处**：

> 这条链只提供 `rksh` 这个 root shell，**从来没加载过 kernelsu.ko，也没部署 ksud**。

所以 KSU 管理器显示"无 root"是必然的 —— 它要的是 KernelSU 自己的内核模块 + ksud，
不是"系统里有个 root shell"。

KSU 生效需要三样东西：

| 组成 | 从哪来 | 之前状态 |
|---|---|---|
| `kernelsu.ko` | 我们编好的（qcom-4.19.157 UAPI5） | ❌ 没加载 |
| `/data/adb/ksud` | KSU 管理器 APK 里的 `lib/arm64-v8a/libksud.so` | ❌ 没部署 |
| KSU 管理器 | 已安装 | ✅ |

### 13.2 v2.5 的做法：提权成功后自动接上

提权成功 → 自动执行：

```
1. 找 ko：/sdcard/Download/*.ko 优先；没有则按内核版本/机型组取内置 ko（v3.1 起有 4 个）
2. 读 ko 里的 vermagic 与本机 uname -r 比对，不一致直接弃用（不盲 insmod）
3. rksh -c "insmod <ko>"  → 校验 /proc/modules 出现 kernelsu，失败则打 dmesg 尾部
4. pm path me.weishu.kernelsu 拿到管理器 APK
5. rksh -c "unzip -p <apk> lib/arm64-v8a/libksud.so > /data/adb/ksud && chmod 755"
6. /data/adb/ksud --version 验证
```

内置 ko 与 `kernelsu-findx3-uapi5.ko` 逐字节一致（sha256 `2d78ea6d6623aa43`），
UAPI 5 与 KSU 3.3.1 管理器匹配。

界面上新增开关「提权后自动装 KSU」，默认开。

### 13.3 各机型对应关系

| 机型组 | 内核 | 内置 ko（v3.1） | 状态 |
|---|---|---|---|
| qcom-4.19.157（Find X3 / 一加 9R / OPPO Pad） | 4.19.157-perf+ | `kernelsu-qcom157-uapi5.ko` | ✅ 已实测 |
| mtk-4.19.191（A2m/A2 Pro/A97/Reno6/Reno8/Reno5 Pro） | 4.19.191+ | `kernelsu-mtk419-uapi5.ko` | ✅ 已内置（nocfi 编译） |
| sm7325-5.4.254（Reno7 PFJM10） | 5.4.254-qgki-gaa96eae94d32 | `kernelsu-sm7325-uapi5.ko`（带 secpolicy）<br>`kernelsu-sm7325-uapi6.ko`（备选） | ⏳ 待真机验证 |
| 小米 12X | 4.19.157-perf-ge9da… | ❌ 无（vermagic 与 qcom 组不同） | 需另编 |

内置 ko 全部走同一套预检：vermagic 首段必须与本机 `uname -r` 前缀一致，否则日志
直接报「vermagic 不匹配」并停手 —— 这样小米 12X 这种同大版本不同小版本的机型
不会被 qcom157 的 ko 误加载。

想临时换别的 ko（比如自己重编的、或 UAPI6），把文件丢到 `/sdcard/Download/`，
它优先级高于内置，重启 App 后点「提权 → 自动装 KSU」即可。

### 13.4 如果还是不行

- `/proc/modules` 里没有 `kernelsu` → ko 与内核不匹配（组别 / CFI / UAPI），看日志里 insmod 的报错
- 有 `kernelsu` 但管理器仍显示未安装 → ksud 没部署成功，手动点「部署 ksud」并填路径
- 管理器报 UAPI 不匹配 → 换另一个 UAPI 版本的 ko（我们另编过 uapi6）

### 13.5 怎么判断「这个 KSU 是不是我们自己构建的」

三者是独立的，别混：

| 组成 | 来源 | 是否我们构建 |
|---|---|---|
| `kernelsu.ko` | APK 内置 `assets/kernelsu-qcom157-uapi5.ko` | **是**（sha256 `2d78ea6d6623aa43`） |
| `/data/adb/ksud` | 从设备上已装的 KSU 管理器 APK 里 `unzip -p ... lib/arm64-v8a/libksud.so` | 取决于设备上装的是哪份管理器 |
| KSU 管理器 App | 设备上已安装，我们不改 | 同上 |

判据（v2.6 起自动写进日志）：

```
管理器: versionName=v3.3.1 versionCode=32681     ← 我们构建的（官方 32680）
        我们构建的是 Debug 证书 SHA-256 1614211d79...
        官方签名是         SHA-256 c371061b19...
/sys/module/kernelsu/version: ...                ← 模块自报版本
ksud sha256: xxxx...                             ← 部署后的 ksud 指纹
```

三者的版本链必须一致：**ko 的 UAPI 要匹配 ksud/管理器的 UAPI**。
我们内置的是 UAPI 5，对应 KSU 3.3.1（`KERNEL_SU_UAPI_VERSION = 5`）。

设备上装官方 3.3.1 还是我们编的 3.3.1，对 UAPI 5 来说都一样兼容；
但若装的是 1.x 之类老版本，UAPI 不匹配，管理器会报版本错误 —— 换对应 UAPI 的 ko 即可。

### 13.6 使用前提（重要）

「一键提权 + 自动配置 ko」确实把提权到 KSU 可用串起来了，但有 4 个前提：

| # | 前提 | 说明 |
|---|---|---|
| 1 | 装的是 **KSU 3.3.1**（官方或我们编的都行） | 内置 ko 是 UAPI 5，与 3.3.1 匹配 |
| 2 | **先装管理器，再点一键提权** | 自动流程要用 `pm path me.weishu.kernelsu` 找 APK 取 ksud；顺序反了要多点一次「部署 ksud」 |
| 3 | **每次重启后重跑一键提权** | root shell 与 kernelsu 模块都是内存态；`/data/adb/ksud` 和管理器是持久的 |
| 4 | 在管理器里给 App 授超级用户 | 这一步只能手动点，App 替代不了 |

另外：

- 管理器显示「已安装」的条件是：`/proc/modules` 里有 `kernelsu` + `ksud` 可执行且版本匹配
- MTK 组机型未内置 ko，需把 `kernelsu-mtk-*.ko` 放到 `/sdcard/Download/`（会自动优先）
- permissive 是 p7 临时改的，重启恢复 Enforcing

v2.7 修的两个缺口：

- 已有 root 时（连点两次）原来直接 return、不跑自动 KSU → 现在会跑
- 模块已在 `/proc/modules` 时不再重复 `insmod`（避免报错刷日志）

---

## 14. 现场日志修的两个致命 bug（v2.8）

OPPO Pad 22:42 那份日志，暴露了两处会让「第二次点击」永远失败的问题。

### 14.1 `cp` 覆盖正在执行的 p7 → Text file busy

```
[22:41:58.897]   cp 失败(1) cp: /data/local/tmp/p7: Text file busy → 改用 stdin
[22:41:59.143]   stdin 失败: sh: can't create /data/local/tmp/p7: Text file busy
[22:41:59.145]   !! p7 推送失败
```

第一次提权成功后 **p7 进程仍在内存里跑**（pid 13531），它持有可执行映像的 inode。
再 `cp` 覆盖同名文件 → `ETXTBSY`；连 `>` 重定向也一样被拒。

修法：一律先写 `$remote.new`，再 `mv -f` 覆盖。

```kotlin
val tmp = "$remote.new"
sh("cp '<src>' '$tmp' && chmod 644 '$tmp' && mv -f '$tmp' '$remote'")
```

`rename(2)` 只换目录项、不动正在使用的 inode，所以一定成功。

### 14.2 推送排在 root 检查之前 → 推送一失败就整体中止

原来的顺序是：

```
1/5 释放 → 2/5 推送 → 检查 root → 跑漏洞
```

p7 残留导致推送失败，于是 `return false`，**永远走不到「检查 root」**，
自动装 KSU 也就没机会执行。日志里三次点击都停在 2/5，就是这个原因。

新顺序：

```
0   先 checkRoot()  →  已提权就直接进 autoKsuSetup，不推送、不跑漏洞
1/5 释放 → 2/5 推送 → 检查 root → 跑漏洞
2/5 失败但已有 root  →  不中止，继续装 KSU
```

### 14.3 另外三处修正

| 项 | 旧 | 新 |
|---|---|---|
| `自动装KSU` 状态 | 只在开关处显示 | 写进「一键提权」首行，`自动装KSU=true/false` |
| 关掉开关时的行为 | 静默跳过 | 明确打 `! 「提权后自动装 KSU」是关的，跳过` |
| 诊断快照读的 p7 输出 | 固定 `/sdcard/root.log`（残留上一次） | 读本次 `run-*.log` |

第三条解释了之前日志里那两行矛盾：诊断段显示 `[off] selinux=0x3c64000`（Find X3 的旧值），
而实际这次用的是 `0x3c88000` —— 因为 `/sdcard/root.log` 是上一回留下的文件。

### 14.4 Pad 上现在的正确操作

root 已经在了（`rksh` 通），所以装 v2.8 后**只需要点一次「一键提权」**：

```
0    已有 root（本次开机已提权），跳过推送与漏洞
========== 自动加载 KSU ==========
  ko 来源: 内置 4.19.157 qcom UAPI5
  insmod exit=0
  /proc/modules: kernelsu ...
  模块已加载
```

不再需要重启，也不必重新跑漏洞。

---

## 15. Pad 22:58 失败的真正原因：kptr_restrict 挡住了 rk9 的 kln（v2.9）

### 15.1 先纠正一个误会

23:03 那份日志里 **「一键提权」出现 0 次** —— 那次只是打开 App 看了一眼就导出，
没有执行任何提权动作。真正的失败记录在 `plog.txt` 尾部（22:58 那次）：

```
[W] raw_uid=0 raw_euid=0 kill(1,0)=0 errno=0        ← 提权成功了
[W] KO open /data/local/tmp/rk9.ko -> 4 errno=0
[W] kallsyms_lookup_name 未找到（kptr_restrict?）   ← 这里断的
[W] finit_module ret=0 errno=0
[main] comm='wkwait'  scontext='u:r:kernel:s0'
Uid:	0	0	4294967275	0                            ← saved uid 是脏值
[main] exiting now                                   ← 没建 socket 就退出
```

所以链条是：**cred 写成功（uid/euid=0）→ rk9 加载 → rk9 初始化要 kln 拿不到 → socket 没建 → 失败**。

### 15.2 为什么拿不到 kln

`rk9.ko` 初始化需要 `kallsyms_lookup_name` 的地址。取得它只有两条路：

1. 读 `/proc/kallsyms`（`kptr_restrict=2` 时连 uid=0 都读不到，要 `CAP_SYSLOG`）
2. 由加载方通过 `kln=` 参数传进来

p7 走的是第 1 条，被 kptr_restrict 挡住。而它提权后只改了 cred 的 uid，
**没有补 capability**，所以即使 uid=0 也读不到。

### 15.3 v2.9 的两处改动

**① 显式地址模式下不再带 `GL_AUTO`**

```
旧: GL_ATTR=cred GL_AUTO=1 GL_PERM=1 ... GL_SELINUX=0x3c88000 ...
新: GL_ATTR=cred GL_PERM=1 ... GL_PRIV=1 GL_SELINUX=0x3c88000 ...
```

`GL_AUTO=1` 会让 p7 走 perf_event 泄漏 + `/proc/kallsyms` 自动推导，
在 kptr_restrict=2 的机器上必然失败，还把「kallsyms_lookup_name 未找到」
带进 rk9 的初始化。既然档案里有全套地址（8/8），就不需要自动推导。

**② 加 `GL_PRIV=1`**

让 p7 提权后给自己补 capability（含 `CAP_SYSLOG`），这样它能读 `/proc/kallsyms`，
把 kln 算出来传给 rk9。这是绕开 kptr_restrict=2 的唯一办法。

### 15.4 如果 GL_PRIV 也不管用

用已有的 root 手动起 rk9，把 kln 直接喂进去：

```bash
adb shell 'su -c "K=\$(grep \" kallsyms_lookup_name\$\" /proc/kallsyms | cut -d\" \" -f1); echo kln=0x\$K; insmod /data/local/tmp/rk9.ko kln=0x\$K"'
```

如果这条 `grep` 输出为空，说明 root 也没有 `CAP_SYSLOG`，那就得先
`echo 0 > /proc/sys/kernel/kptr_restrict`（需要 root 且 SELinux 允许）。

### 15.5 顺带修的诊断问题

| 项 | 旧 | 新 |
|---|---|---|
| `kptr` / `dmesg_r` | 读失败时留空（`kptr    : dmesg_r :`） | 输出 `读不到` |
| `socket` 检查 | `ls -l` 报 `Permission denied` 容易误判 | `test -S` 后明确输出「存在 / 不存在或无权查看」 |
| 导出头部 | 不知道本次跑没跑过 | 没跑过时写「本次启动没有执行过一键提权，p7 字段是上次留下的」 |

## 16. v3.1：把 ko 全塞进 APK（2026-11-16 23:30）

之前 APK 只内置 qcom-4.19.157 一个 ko，其它机型要自己往 `/sdcard/Download/` 丢文件。
v3.1 直接内置四份，装一个 APK 就能覆盖 4.19.157 / 4.19.191 / 5.4.254 三组内核。

### 16.1 assets 清单

| asset | 大小 | 来源 | vermagic |
|---|---|---|---|
| `kernelsu-qcom157-uapi5.ko` | 4.25 MB | `kernelsu-findx3-uapi5.ko` | `4.19.157-perf+` |
| `kernelsu-mtk419-uapi5.ko` | 4.24 MB | `kernelsu-mtk-4.19.191-nocfi.ko` | `4.19.191+` |
| `kernelsu-sm7325-uapi5.ko` | 5.39 MB | `kernelsu-sm7325-5.4.254-uapi5-policy.ko` | `5.4.254-qgki-gaa96eae94d32` |
| `kernelsu-sm7325-uapi6.ko` | 5.33 MB | `kernelsu-sm7325-5.4.254-uapi6.ko` | 同上 |

sha256（前 16 位）：
`2d78ea6d6623aa43` / `cf27f3b94329d9d7` / `86563bb7741e87d0` / `c3e52fc6dccc5906`。

`rk9-54.ko` 也在同一批里（sha256 `f1224bb97c946de6`），它是 5.4 的提权跳板，
**不需要 kln**，与 kernelsu.ko 独立：rk9 负责拿到 root shell，kernelsu.ko 负责 KSU。

APK 由此从 19.8 MB 涨到 35.5 MB —— ko 在 APK 里是 Stored（AAPT2 不压缩 `.ko`），
所以体积就是四份之和。嫌大可以把 uapi6 那份删掉再编（改 `pickBundledKo` 的返回）。

### 16.2 选哪个 ko

```
1. /sdcard/Download/*.ko 存在 → 用它（人工优先，方便临时换 UAPI/换组）
2. 否则按顺序：
   内核 5.4.*            → kernelsu-sm7325-uapi5.ko（档案 id 以 _uapi6 结尾时用 uapi6）
   档案 ∈ qcom157 三机型 → kernelsu-qcom157-uapi5.ko
   档案 ∈ mtk 六机型     → kernelsu-mtk419-uapi5.ko
   内核 4.19.157.*       → qcom157（兜底）
   内核 4.19.191.*       → mtk419（兜底）
   其它                  → 提示去 /sdcard/Download 放
```

### 16.3 新增的 vermagic 预检

释放 ko 之后、推送之前，App 会从 ko 二进制里把 `vermagic=` 字符串抠出来，
与本机 `System.getProperty("os.version")`（即 `uname -r`）比首段：

```
  内置 kernelsu-qcom157-uapi5.ko  4357 KB  组: qcom-4.19.157 UAPI5
  ko vermagic: 4.19.157-perf+ SMP preempt mod_unload modversions aarch64
  本机 uname  : 4.19.157-perf-ge9da1b2c3
  !! vermagic 不匹配（ko=4.19.157-perf+，本机=4.19.157-perf-ge9da1b2c3）
```

不匹配就直接停手，不再 insmod。这条对小米 12X 这种「同一内核版本号、不同
厂商后缀」的机型是硬保护 —— 它的 cred 偏移（0x7e8）也和 qcom 组不同。

### 16.4 insmod 失败时自动捞 dmesg

以前只报「模块没加载成功」，原因得自己 dmesg。现在直接打尾部 25 行：

```
  ! 模块没加载成功，ksud 部署没有意义，已跳过
  --- dmesg 尾部（insmod 报错的真正原因在这里）---
  [ 1234.567890] kernelsu: loading out-of-tree module taints kernel
  [ 1234.567900] kernelsu: module verification failed: signature and/or required key missing
  ...
  常见原因：vermagic/组别不符、CFI type hash 不符（clang 版本不同）、
            unknown symbol、MODVERSIONS CRC 不符（Module.symvers 与本机内核不同）
```

5.4 这份 ko 是用 clang 14 编的，内核是 clang 11.0.2，CFI type hash 有可能对不上。
真机首次测试如果 insmod 失败，`dmesg | grep -i cfi` 会直接给答案。

## 17. 23:43 那次实机测试：为什么日志是空的（v3.2）

### 17.1 现场

用户拿来两份导出（23:46:05 / 23:46:11），两份都是**重启之后**的会话：

```
内核    5.4.254-qgki-gaa96eae94d32
boot_id 6f65d008-cdff-4888-b4e3-5fb7715c412d
注意    本次启动没有执行过「一键提权」，p7 相关字段是上一次运行留下的
最近一次 p7 输出（run-1116-234323.log）: (无)
```

`run-1116-234323.log` 存在但 **0 字节**；`khelper.log` 的"磁盘历史"也只有 972 B，
正好等于本次会话自己 —— 23:43 那一轮的执行记录一个字都没留下。

### 17.2 为什么全没了

p7 的输出是用 `nohup ./p7 > 文件 &` 重定向的，App 的 `appendText` 也从不 fsync。
**内核 panic 时 f2fs 会回滚到上一次 checkpoint**，自那以后的所有写入整块消失，
连文件大小都回到 0（inode 活了，数据块没活）。

所以 0 字节 **不能**说明"p7 没输出"，只能说明 panic 距最后一次 checkpoint 很近。
这也解释了为什么 khelper.log 里 23:43 的几十行也没了 —— 它们是同一批脏页。

### 17.3 v3.2 的三处改动

| 改动 | 原因 |
|---|---|
| p7 输出主副本改到 `/data/local/tmp/p7run.log` | `/sdcard` 是 FUSE → `/data/media`，多一层转发；`/data` 分区直写更可靠 |
| 等待循环每 2s 执行 `sync` + `cp` 到 App 目录 | 崩溃最多只丢 2 秒；App 目录那份供导出直接读 |
| 启动时检测残留并另存 `run-recovered.log` | 上次 panic 留下的真身自动归档，不会被下一轮覆盖 |

下次再崩，日志至少会停在崩前 2 秒。**这比任何 pstore 都靠谱**（pstore 要 root 才能读，
而 root 正是我们想拿到的东西）。

### 17.4 新增「只探测不触发」开关

p7 的字符串里有 `GL_NTRIG` 参数和这条路径：

```
[main] zero-punch patched=%d, exiting with no trigger
```

打开开关后 env 追加 `GL_NTRIG=0`：p7 会跑完 perf 泄漏、waiter 定位、punch，
但**不做**最后的 `sched_setattr` 触发，也就是不写 cred。

换新内核时先用它，能把"客观条件是否成立"和"触发会不会崩"两件事分开：

- 探测就崩 → 连泄漏/定位都不成立，别再往下走
- 探测能打完 → 说明 perf 泄漏与地址推导在 5.4 上可用，问题出在触发路径

### 17.5 p7 是 4.19 专用的硬证据

从二进制里抠出来的字符串：

```
pi_attr_419.c                                  ← 源文件名就写着 419
[leak] perf_event_open fail errno=%d
[prctl] samples=%d in-func=%d -> task=0x%llx votes=%u
[main] sched_setattr ret=%ld errno=%d
[main] armed=%d, triggering %d x sched_setattr
[waiter] ret=%ld errno=%d, punching 0xdead@idx0x%zx
[main] waiting for the waiter to time out and self-punch
```

整条链是 **perf_event 泄漏 → PI waiter 定位 → sched_setattr 触发**，
依赖 4.19 的 PI/rtmutex 实现。5.4 这几段代码都重写过（rtmutex 重构、
`__sched_setscheduler` 的 PI 处理变更），所以：

**5.4 上崩在 p7 阶段是预期内的，不是 rk9/kernelsu 的问题。**

后面还有一道 CFI（§16.4：模块 clang14 vs 内核 clang11.0.2，`__cfi_check_fail` 是
`brk #0x5502`），但那要等 p7 先跑通才会遇到。

### 17.6 5.4 的下一步

按这个顺序，每步都要留下日志再往下走：

1. `只探测不触发` 跑一遍 → 看 `[off]`、`[leak]`、`[waiter]` 是否正常打印
2. 探测通过后关掉开关正常触发 → 看 cred 是否被改（`rksh -c id`）
3. 前两步都过，再测 `insmod rk9.ko` / `insmod kernelsu.ko level=0`
4. kernelsu.ko 要真正可用，最终得用 clang-r383902b1 重编（CFI 那条）


