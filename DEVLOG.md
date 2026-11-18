# 开发日志

> **规则**：每次改动追加在顶部，写清「改了什么 / 为什么改 / 怎么验证的 / 结论对不对」。
> 特别是**错误的判断**必须记下来 —— 那是花时间最多的地方。
> 版本号对应 `app/build.gradle.kts` 的 `versionCode`。

---

## 2026-11-18

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
