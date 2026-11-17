# p7 环境变量（GL_*）对照表

> 分析对象：`ksu-helper/app/src/main/assets/p7`（806,176 字节，ELF64 aarch64，**非 PIE** 静态链接 glibc，装载基址 0x400000）
> 方法：`llvm-objdump -d`（LLVM 14，WSL）全量反汇编 + `.rodata` 字符串交叉定位 + `strtouq/strtoq` 调用点确认进制（脚本 `ksu/annot2.py`）
> 结论先行：**交付的 `ghostlock-app-main/src/core` 不是 p7 的源码**（详见第 4 节）。本表全部依据二进制反汇编 + `assets/profiles.json` + `MainActivity.kt`，没有源码行号可引。

---

## 0. 三个阅读前提

1. `p7` 只有 **一个** `getenv("GL_NTRIG")` 调用点（0x40169c），且只有一个前驱（0x400980）。这一点决定了下面第 3 节的全部结论。
2. 所有偏移类参数走同一个解析器 `envoff()`（0x401a20）：
   ```c
   size_t envoff(const char *name, size_t def) {
       const char *v = getenv(name);
       if (!v || !*v) return def;          // 未设 / 设成空串 → 用默认
       return strtouq(v, NULL, 0);          // base=0：0x 前缀走十六进制，否则十进制
   }
   ```
3. 布尔类参数是**只看有没有**（`getenv() != NULL`）。`GL_PERM=0` 依然算"开启"，只有 `unset` 才关闭。少数几个额外要求非空串（见表格"类型"列）。

---

## 1. 参数总表（44 个）

"依据"列给的是 p7 的反汇编地址（读取点 / 落点），因为 p7 没有源码可引。

| 参数名 | 类型 | 默认值 | 语义 | 内核版本相关 | 依据（p7 地址 / 依据文件） |
|---|---|---|---|---|---|
| `GL_SELINUX` | hex | `0x3c64000` | `selinux_state` 相对 text 的偏移。写入时目标 = `text + SELINUX` | **强相关** | 载入 0x4005ac；落 `[0x4b3090]`；用于 PERM kind3 (0x4031ec) |
| `GL_INITTASK` | hex | `0x2b9cc40` | `init_task` 偏移。用于 WR 的 `task` 字段、`WR_SCRATCH` 默认值 | **强相关** | 0x4005c4；`[0x4b3050]` |
| `GL_INITCRED` | hex | `0x2bacf08` | `init_cred` 偏移。BUMP 步骤的基准 | **强相关** | 0x4005e0；`[0x4b3088]` |
| `GL_PARANOID` | hex | `0x2b8e254` | `sysctl kernel.paranoid` / `dmesg_restrict` 相关变量的偏移；MECH 步骤目标 | **强相关** | 0x4005fc；`[0x4b3068]` |
| `GL_SIGENFORCE` | hex | `0x2bbc370` | `sig_enforce` 偏移；ZEROSIG 步骤目标。**5.4 上该符号可能不存在** | **强相关** | 0x400618；`[0x4b3060]` |
| `GL_CROFF` | hex | `0x7e0` | `task_struct->cred` 偏移 | **强相关** | 0x400644；`[0x4b3070]` |
| `GL_REALCRED` | hex | `0x7d8` | `task_struct->real_cred` 偏移 | **强相关** | 0x400630；`[0x4b3078]` |
| `GL_ADDRLIM` | hex | `0x40` | 通用偏移：PROBE 目标 = `text + 该值`；TCHK 目标 = `task + 该值`。名称为 `addr_limit`，但两处用法不同，**精确语义未能确认** | 可能相关（档案从未覆盖它） | 0x400660；`[0x4b3058]`；PROBE 0x4026e0；TCHK 0x402b7c |
| `GL_SCRATCH` | hex | `GL_SELINUX + 0x800` = `0x3c64800` | scratch 区偏移（写入用的"安全落点"）。默认值由 `SELINUX+2048` 动态算出 | **强相关**（跟随 SELINUX） | 0x400678（`add x1,x23,#2048`）；`[0x4b50d8+2680]` |
| `GL_ATTR` | enum 字符串 | 未设（等价 `""`） | **模式选择器**。见 §2 | 无关 | 0x4006f0，`[plan+160]` |
| `GL_PRIV` | bool | 未设 | 给 futex 操作加 `FUTEX_PRIVATE_FLAG (0x80)`。设置 `[plan+64]=128` | 无关 | 0x400700 → 0x400710 |
| `GL_AUTO` | bool | 未设 | 开启 `perf_event_open` 自助泄漏内核 text 基址（`[plan+24]`）。**不是**"全自动"，只影响泄漏步骤 | 无关（但依赖 perf 权限/kptr_restrict） | 0x400ac0 |
| `GL_LEAK` | bool | 未设 | 泄漏到 text 基址后**打印并退出**，不做后续利用（`leak-only`） | 无关 | 0x401494 → 0x4014b0，`[L] leak-only text=0x%llx` |
| `GL_IPF` | bool | 未设 | 泄漏阶段的 `ipf` 标记，置 `[plan+20]=1`，只出现在 `[L] text=0x%llx ipf=%d` 日志里。**实际作用未能确认**（未见分支读取它） | 无关 | 0x40144c → `str w0,[plan+20]` |
| `GL_TMODE` | dec | 0（不等待） | `sample_once()` 里在 page-fault 扫描前的等待策略：`1`= `sched_yield()`×20000；`2`= `getpid()`×100000；其它=不等待 | 无关（时序调参） | 0x401da0 → 0x401dc8；分支 0x40207c / 0x402038 |
| `GL_NTRIG` | dec | `4000` | trigger 次数。**只在 `GL_ATTR=self` 下被读取**，见 §3 | 无关 | 0x40169c → 0x4016b8 |
| `GL_NOPUNCH` | bool | 未设 | 跳过 punch（不摆伪造 waiter），只上报 `punched=1` 然后等 trigger。见 §3 | 无关 | 0x4035e4 → 0x4035f0 |
| `GL_OFF` | hex | `288 (0x120)` | punch payload 里 `0xdead0000dead0000` 的写入索引（把 0xdead 塞进 buffer 的第 OFF 字节） | 可能相关（跟 kstack 帧布局/`RANDOMIZE_KSTACK_OFFSET` 有关） | 0x403ba8 → 0x403bcc；`str x0,[x21,x22]` |
| `GL_PTOFF` | hex | `text + GL_ADDRLIM` | **绝对** punch 目标偏移，覆盖默认目标（`strtouq` 结果直接当目标，不再加 text） | 视用法（通常需按内核重算） | 0x4026d4 → 0x402704 |
| `GL_VOFF` | hex | `0`（未设时 target=`text+GL_SCRATCH`） | VWR 目标 = `task_ptr + VOFF`（相对采样到的 task_struct） | **强相关** | 0x402928 → 0x402958 |
| `GL_VTGT` | hex | 未设 | VWR 目标 = 绝对值（覆盖 VOFF） | 视用法 | 0x402960 → 0x402984 |
| `GL_VVAL` | hex | 未设（默认 `text + 0x41`） | VWR 写入值 = 绝对值 | 无关（值而已） | 0x40298c → 0x4029b4 |
| `GL_VADD` | hex | 未设 | VWR 写入值 = `text + 该值`（与 VVAL 互斥，后解析的 VADD 覆盖 VVAL） | 视用法 | 0x4029bc → 0x4029e0 |
| `GL_TCHK_OFF` | hex | `GL_ADDRLIM (0x40)` | TCHK 目标 = `task_ptr + TCHK_OFF`（默认走 ADDRLIM） | **强相关**（task_struct 布局） | 0x402b80 → 0x402ba8 |
| `GL_PERM` | bool | 未设 | cred 计划 phase，kind=3：目标 `text + GL_SELINUX`，写入 `target+1` → 把 SELinux 写进 permissive | **强相关** | 0x402cb4 → 0x402cdc（`w1=3`，0x4031ec） |
| `GL_BUMP` | bool | 未设 | cred 计划 phase，kind=4：目标 `text + GL_INITCRED - 8`，值 = punch buffer 基址 → 换 cred 指针 | **强相关** | 0x402ce8 → 0x402d08（`w1=4`，0x4031d0） |
| `GL_FIXUID` | bool | 未设 | cred 计划 phase，kind=2：目标 `text + (GL_INITCRED+4) - 8` | **强相关** | 0x402d14 → 0x402d30（`w1=2`，`x2=x24+4`） |
| `GL_ZEROSIG` | bool | 未设 | cred 计划 phase，kind=2：目标 `text + GL_SIGENFORCE - 8`（清零签名强制） | **强相关** | 0x402d3c → 0x402d5c |
| `GL_MECH` | bool | 未设 | cred 计划 phase，kind=2：目标 `text + GL_PARANOID - 8` | **强相关** | 0x402d68 → 0x402d88 |
| `GL_IDENT` | bool | 未设 | cred 计划 phase，kind=0：目标 `task_ptr - 8 + 0x7e8`，即 `task + CROFF`（`0x7e8`=CROFF+8，硬编码） | **强相关** | 0x402d94 → 0x402db0；0x402ec4 分支 |
| `GL_RCRED` | bool | 未设 | cred 计划 phase，kind=1：目标 `text + GL_REALCRED` | **强相关** | 0x402e2c → 0x402e50 |
| `GL_RETRY` | dec | 0 | `GL_IDENT` 步骤的重复次数（1..10，>10 截断，≤0 忽略） | **强相关** | 0x402dc4 → 0x402df0（`strtoq` base 10） |
| `GL_CP` | 字符串 `"src|dst"` | 未设 | 拷贝文件：`src` 用 `'|'` 分隔 `dst`（`read`/`write` 循环，不保权限） | 无关 | 0x403054 → 0x403444；`[W] CP done %ld bytes -> %s` |
| `GL_INSTALL` | bool（看存在） | 未设 | 装一个临时 `sh`：`mkdir /data/local/tmp/rm 0777` → `mount tmpfs mode=0777` → 拷 `GL_SRC` 到 `<dir>/sh` → `chown 0:0` + `chmod 04755` | 无关 | 0x403078 → 0x4030d4；0x403668 |
| `GL_SRC` | 路径 | 未设（用内置 `/system/bin/sh` 之类） | `GL_INSTALL` 的源文件 | 无关 | 0x40368c → 0x4036a4 |
| `GL_KO` | 路径（需非空） | 未设 | `open()` 后 `finit_module` 加载内核模块（rk9.ko） | 无关（模块自身相关） | 0x4030f8 → 0x40351c |
| `GL_KOPARAM` | 字符串（需非空） | 空 → 自动从 `/proc/kallsyms` 取 `kallsyms_lookup_name` 生成 `kln=0x%lx` | **`finit_module` 的参数字符串**。不填时 p7 自己扫 kallsyms 拼 `kln=...`；扫不到用内置兜底字符串 | 无关 | 0x403564 → 0x40359c (`syscall 273`)；自动路径 0x403a28 |
| `GL_DMESG` | 路径（需非空） | 未设 | `syslog(SYSLOG_ACTION_READ_ALL, buf 1MB)` 并写文件 | 无关 | 0x40311c → 0x403324（`syscall 116`） |
| `GL_EXEC` | 路径（需非空） | 未设 | uid 门控：读 `/proc/thread-self/status` 的 `Uid:`，**real==0 且 eff==0** 才继续 | 无关 | 0x403138 → 0x4033d4；比较 0x403ca8 |
| `GL_EXECBIN` | 路径（需非空） | 未设 | 门控通过后 `execve(path, {path, NULL})`（或 `sh -c path` 形式） | 无关 | 0x403154 / 0x4032b4；0x40377c |
| `GL_WR_OFF` | hex | `GL_SELINUX (0x3c64000)` | 任意写目标 = `text + WR_OFF` | **强相关** | 0x40386c → 0x403898 |
| `GL_WR_TARGET` | hex | `text + WR_OFF` | 任意写目标（绝对地址，覆盖 WR_OFF） | 视用法 | 0x4038b0 → 0x4038e4 |
| `GL_WR_VALUE` | hex | 0 | 任意写写入的 8 字节值 | 无关 | 0x4038ec → 0x403914 |
| `GL_WR_SCRATCH` | hex | `GL_INITTASK + 0x800` | payload 的 `scratch` 字段（`[plan+1936]`） | **强相关** | 0x40392c / 0x4039e0；默认 0x403c5c |

### 关于"是否内核版本相关"的判定依据

`profiles.json` 里 9 个机型档案**只覆盖**这 8 个：`GL_CROFF / GL_REALCRED / GL_INITCRED / GL_INITTASK / GL_SIGENFORCE / GL_PARANOID / GL_SELINUX / GL_SCRATCH`。
其余参数在所有档案里都没出现，说明作者认为它们跨机型通用（至少 4.19 系列内）。这 8 个是"换内核必须重算"的硬约束。

---

## 2. `GL_ATTR` 是总开关（不看它，其余参数都可能无效）

`GL_ATTR` 是字符串枚举，`strcmp` 逐个比（0x400724–0x4007e0）。取值：

| 取值 | 行为 |
|---|---|
| `self` | 置 `[plan+2160]=1`。**唯一会读 `GL_NTRIG` 的模式**：waiter 摆 punch → main 用 `GL_NTRIG` 次 `set_nice` 触发 |
| `probe` | 置 `[plan+40]=1`。waiter 走 PROBE（`GL_PTOFF`/`GL_SCRATCH`/`GL_ADDRLIM`），main 进入 cred 类触发循环 |
| `cred` | 置 `[plan+44]=1`。**App 的实际默认值**。waiter 按 cred 计划逐 phase punch，main 逐 phase 触发；触发次数 = phase 数，与 `GL_NTRIG` 无关 |
| `tchk` | 置 `[plan+48]=1`。waiter 走 TCHK（读 `task+TCHK_OFF`） |
| `vwr` | 置 `[plan+52]=1`。waiter 走 VWR（`GL_VOFF/VTGT/VVAL/VADD`） |
| `wr` / `""`（设了但为空） | 置 `[plan+1644]=1`。waiter 走**任意写**（`GL_WR_*`） |
| `offsets` | 只打印 `[off] ...` 三行（selinux/inittask/initcred、paranoid/sigenforce/scratch、cred/realcred/addrlim）后退出 |
| `wait` | 只 arm，sleep 8s，打印 `[main] survived timeout window, exiting now` |
| `wake` | arm 后 `FUTEX_WAKE`，sleep 6s，退出 |
| `punch` | 直接 punch 一次，退出（`[main] zero-punch patched=%d, exiting with no trigger`） |
| `trigger` | punch 后固定 4000 次触发（不看 `GL_NTRIG`） |
| `all` | 同 `trigger` |

注意点名不一致：`[main] zero-punch patched=%d, exiting with no trigger` 这条日志出现在 **`GL_ATTR=punch`** 分支（0x4017b0 → 0x401788 → 0x401798），**不是** `GL_NTRIG=0` 的路径。两者都会"打完 punch 就退出"，但走的是完全不同的代码。

---

## 3. `GL_NTRIG` 到底能不能当"只探测不触发"的开关？

### 3.1 反汇编事实（0x401694–0x401768）

```
401694: GL_NTRIG 字符串
40169c: getenv("GL_NTRIG")
4016a4: if (NULL) w21 = 4000                      // 默认 4000
4016b0: else     w21 = (int)strtoq(v, NULL, 10)   // 十进制，负数/0 合法
4016c4: puts("[main] waiting for the waiter to time out and self-punch")
4016e8: 等 [plan+600] != 0（waiter 已 punch），最多 10000 × 1ms = 10s
4016f0: printf("[main] armed=%d, triggering %d x sched_setattr", [plan+600], w21)
401714: if (w21 <= 0)  goto 401564
40171c: for (i = 0; i < w21; i++) {
            set_nice(waiter_tid, i % 19 + 1);   // sched_setattr(SCHED_BATCH, nice)
            busy_loop(20000);
        }
401564: puts("[main] trigger loop finished without panic");
40157c: [plan+604] = 1;
401588: _exit(0);
```

结论（`GL_ATTR=self` 前提下）：

- `GL_NTRIG=0` → **一次 `sched_setattr` 都不发**，打印 `[main] armed=N, triggering 0 x sched_setattr` 后立刻 `_exit(0)`。
- 但 **punch 已经发生了**：waiter 线程独立完成 arm → `WAIT_REQUEUE_PI`（5s 超时）→ `punch_buf()`。`GL_NTRIG` 只是"扣不扣扳机"，不控制"上不上膛"。
- 退出码恒为 0，**不能用 exit code 判断是否成功**；只能看日志。

### 3.2 ⚠️ 致命前提：只有 `GL_ATTR=self` 才读 `GL_NTRIG`

`GL_NTRIG` 的唯一读取点 0x40169c 的唯一前驱是 0x400980：
```
40097c: ldr  w21, [plan+2160]     // = (GL_ATTR == "self")
400980: cbnz w21, 0x401694
```
而 `GL_ATTR=cred`（**App 现在的默认**，`MainActivity.kt:1023`）在 0x400810 就 `cbnz → 0x400a80` 跳走了，永远到不了 0x40097c。

**所以：`buildEnv()` 里给 `GL_ATTR=cred ... GL_NTRIG=0` 追加的那一段（`MainActivity.kt:1036-1039`）在运行时完全无效。** 走 cred 模式时触发次数写死在 cred 计划里：
```
4011b4: 读 offsets+56（= phase 数 plan_n）
4011e8..: 对每个 phase：set_nice(waiter_tid, phase+5) → [1632]=phase → usleep(250ms)
```
也就是说 cred 模式**一定会触发 plan_n 次**，`GL_NTRIG` 出局。

### 3.3 想真正"只探测不触发"，该怎么配

| 目的 | 需要的 env | 实际发生什么 | 残留风险 |
|---|---|---|---|
| 只验证泄漏/偏移推导 | `GL_ATTR=offsets`，或 `GL_LEAK=1 GL_AUTO=1` | 只读 perf/kallsyms 算地址，打印后退出 | 无（只读） |
| 验证 arm 通路但不碰 payload | `GL_ATTR=self GL_NOPUNCH=1 GL_NTRIG=0` | arm + `WAIT_REQUEUE_PI`，`punch_buf` 被跳过，不发 trigger | 最低 |
| 打 punch 但不触发 | `GL_ATTR=self GL_NTRIG=0` | arm + WAIT_REQUEUE_PI + `punch_buf`（把伪造 waiter 摆到内核栈 sigreturn 帧里）+ 退出 | 见 3.4 |
| 现在 App 实际发的 | `GL_ATTR=cred ... GL_NTRIG=0` | **正常全流程利用**，触发 plan_n 次 | 高 |

### 3.4 punch 本身的残留到底有多大

punch 的实质（`punch_buf` @0x4020a0 + `handler` @0x401a64）：
1. `sigaction(SIGUSR1)` + `pthread_sigmask(UNBLOCK)`；
2. 全局 `[0x4b50d8]` = {len=512, buf=payload, flag, armed=1}；
3. `raise(SIGUSR1)` → 内核进 `rt_sigreturn`，handler 在**信号帧的 `fpsimd_context`** 里 `memcpy` 512 字节 payload，并清零 fpsr/fpcr；
4. handler 返回 → 内核从这块被改写的帧恢复 → payload 的字节就留在**该线程的内核栈**上；
5. 真正危险的是随后的 `sched_setattr()`：它让 PI 代码走到那个栈槽、把 payload 当 `rt_mutex_waiter` 消费掉。

所以：
- **punch = 往自己线程的内核栈摆一份伪造 waiter（不写内核堆、不改任何内核全局量）**；
- 期间只要有一次 syscall 就会覆盖掉那个栈槽（这正是交付源码 `multicast_waiter_route.cpp:158-163` 注释里写的"syscall between the sigreturn copy and the consumer's sched_setattr() unwinds over the stack slot"）；
- `GL_NTRIG=0` 之后进程立刻 `_exit(0)`，线程组销毁、内核栈释放 → **payload 随之消失**。

因此从代码结构上看，`GL_ATTR=self GL_NTRIG=0`（或更保守的加上 `GL_NOPUNCH=1`）**不会留下持久的 cred 改写，也不会在退出后留下活的内核链表节点**——比 App 现在发的 cred 配方安全一个数量级。

**但必须明确写下的"未能确认"项**：
- 这段推理只基于 p7 自身代码。`WAIT_REQUEUE_PI`（0x40269c，5s 超时）本身在 5.4.254 上是否会先触发一次内核侧的 waiter 入链，二进制里看不到（在内核里）。
- 因此 `GL_NTRIG=0` 只能说"**不再主动触发越界写**"，不能说"**绝对无副作用**"。第一次在 5.4.254 上试，仍应假设会 panic。

---

## 4. 与交付源码的差异（重要）

### 4.1 结论：`src/core` 不是 p7 的源码

证据（全部为"源码里没有"）：

| 检查项 | 源码 `ghostlock-app-main/src/core` | p7 |
|---|---|---|
| `GL_ATTR` / `GL_NTRIG` / `GL_PTOFF` / `GL_WR_TARGET` / `GL_TCHK_OFF` / `GL_TMODE` / `GL_KOPARAM` | 0 处 | 全部存在 |
| 字符串 `triggering` / `zero-punch` / `arm-check` / `pi_attr_419` | 0 处 | 全部存在 |
| `main()` 入口 | `argc/argv` 解析 `--ghostlock-app-call` / `--load-prebuilt-profile <bin>` / `--dump-kernel-log`，读 `glk1` profile 文件（`main.cpp:26-70`） | 无参数，纯 `getenv` |
| 模块划分 | `attack/ops.cpp`、`session/backend/cve_2026_43499_backend.cpp`、`route/*`、`race/*` | 单文件风格：`main` + `waiter_fn` + `owner_fn` + `punch_buf` + `sample_once`，全部 C 式符号 |

即：p7 是同一漏洞（`CVE-2026-43499`，futex PI UAF）的**较早/另一分支**实现，源码是后来的重写版（profile 文件 + 多后端）。**拿源码行号给 p7 的参数做注释是不可靠的。**

### 4.2 "源码有、p7 没有"的 13 个 GL_*

| 源码参数 | 源码位置 | 语义（源码侧） | p7 里的对应物 |
|---|---|---|---|
| `GL_PUNCH` | `route/multicast_waiter_route.cpp:288`、`session/backend/cve_2026_43499_backend.cpp:529` | 是否执行 punch（开关） | `GL_NOPUNCH`（反向） |
| `GL_PUNCH_ROUNDS` | `multicast_waiter_route.cpp:343` | punch 重复轮数（对抗 `RANDOMIZE_KSTACK_OFFSET`） | 无（p7 靠 `GL_OFF` 单次 + `raw_kstack` 处理） |
| `GL_WRITE_TARGET` | `cve_2026_43499_backend.cpp:111` | 任意写目标 | `GL_WR_TARGET`（改名） |
| `GL_LEAK_ONLY` | `support/util.cpp:722` | 只泄漏 | `GL_LEAK`（改名） |
| `GL_KS_VERBOSE` | `kernelsnitch/kernelsnitch.h:354` | kernelsnitch 详细日志 | 无 |
| `GL_FUTEX_TABLE` | `kernelsnitch.h:356` | futex hash 表基址覆盖 | 无 |
| `GL_WAIT_MS` | `race/threads.cpp:42` | waiter 等待毫秒数 | 无（p7 硬编码 5s/10s） |
| `GL_PRE_ARM` | `threads.cpp:53,131,305` | 预先把 owner 压到目标 nice | 无 |
| `GL_OWNER_CHAIN_TIMEOUT_MS` | `threads.cpp:138` | owner 链超时 | 无 |
| `GL_UNLOCK_AFTER_FAIL` | `threads.cpp:168,267,295` | 失败后主动 unlock | 无 |
| `GL_ALTSTACK_LOCK` | `multicast_waiter_route.cpp:178` | 固定 altstack `ss_size`（对抗随机化） | 无（p7 用 `GL_OFF`） |
| `GL_POISON_WAITER_LOCK` | `multicast_waiter_route.cpp:277` | 污染 waiter lock | 无 |
| `GL_SPRAY_STAMP` | `multicast_waiter_route.cpp:316` | page-touch 喷溅 | 无 |

### 4.3 "p7 有、源码找不到"的

**全部 44 个。** 也就是说不能用源码验证任何一条 p7 参数的语义——本报告第 1、2、3 节全部来自反汇编。

### 4.4 顺带发现的两处不一致

1. **`GL_PRIV` 的注释与二进制行为不符。** `MainActivity.kt:1024-1026` 说"提权后给自己补 capability（含 CAP_SYSLOG）"，但 p7 里 `GL_PRIV` 只做一件事：`[plan+64] = 0x80`，而 `[plan+64]` 只被当作 futex op 的 flag 用（`orr w2,w2,#0xc` → `0xC0`/`0x8C`），即 `FUTEX_PRIVATE_FLAG`。**p7 里没有任何补 capability 的代码**（未见 `capset`/`cap_effective` 写入）。
2. **`GL_NTRIG=0` 的注释与二进制行为不符。** 见 §3.2：`GL_ATTR=cred` 时该变量从不被读。

---

## 5. 在 5.4.254（SM7325 / PFJM10）上跑，哪些必须重算

`assets/profiles.json` 已经内置了一条 5.4.254 档案 `oppo_reno7_pfjm10_54`，可直接用作重算基准。对照 p7 的**编译内置默认值**（= Find X3 4.19.157 的值）：

| 参数 | p7 内置默认（4.19.157 骁龙870） | 5.4.254 档案值 | 变化 | 重算依据 |
|---|---|---|---|---|
| `GL_CROFF` | `0x7e0` | `0x808` | +0x28 | `task_struct` 字段变动（cred 在 5.4 后移） |
| `GL_REALCRED` | `0x7d8` | `0x800` | +0x28 | 同上，紧跟 cred 前 8 字节 |
| `GL_INITCRED` | `0x2bacf08` | `0x2caf850` | +0x100948 | 内核镜像符号位置整体变化 |
| `GL_INITTASK` | `0x2b9cc40` | `0x2ca2800` | 同量级 | 同上 |
| `GL_PARANOID` | `0x2b8e254` | `0x2bb0048` | 同量级 | 同上 |
| `GL_SIGENFORCE` | `0x2bbc370` | `0x2f07400` | **语义变了** | **5.4 该机型未开 `MODULE_SIG`，没有 `sig_enforce` 符号**，档案把 `GL_SIGENFORCE` 指到 `selinux_state` 同页空白地址（写入无害）。所以 `GL_ZEROSIG` 在 5.4 上等于空操作 |
| `GL_SELINUX` | `0x3c64000` | `0x2f07000` | 大幅变化 | `selinux_state` 位置随 config 变化，**档案注释明确警告"地址不可混用"**（同 4.19 的两个 OPPO 机型就差 0x24000） |
| `GL_SCRATCH` | `0x3c64800` | `0x2f07800` | 跟随 SELINUX | 恒等于 `GL_SELINUX + 0x800`（p7 代码里就是这么算的），不必单独算 |

**必须重算（强绑定）**：`GL_SELINUX`、`GL_SCRATCH`、`GL_CROFF`、`GL_REALCRED`、`GL_INITCRED`、`GL_INITTASK`、`GL_PARANOID`、`GL_SIGENFORCE` —— 即 profiles.json 里那 8 个，一个都不能沿用默认。

**未能确认、需要实测的 4 个**（所有档案都没覆盖，只能在 5.4 上试）：
- `GL_ADDRLIM`（默认 0x40）：TCHK 目标 = `task+0x40`；5.4 的 `task_struct` 前 0x100 字节布局变了（`thread_info` 搬进 task_struct 之后又调整过），0x40 很可能不对。
- `GL_OFF`（默认 288）：payload 在 sigreturn 帧里的索引，受 `CONFIG_RANDOMIZE_KSTACK_OFFSET` 与 `struct fpsimd_context` 大小影响。**5.4 若开了 `RANDOMIZE_KSTACK_OFFSET`，单次 punch 命中率会下降**（源码版是用 `GL_PUNCH_ROUNDS` 重复打来覆盖随机偏移；p7 没这个参数）。
- `GL_PTOFF`：默认 `text+ADDRLIM`，继承了 `GL_ADDRLIM` 的疑点。
- `GL_TCHK_OFF`：默认 = `GL_ADDRLIM`。

**与内核无关、可以照抄的**：`GL_ATTR`（模式）、`GL_PRIV`、`GL_AUTO`、`GL_LEAK`、`GL_IPF`、`GL_TMODE`、`GL_NTRIG`、`GL_NOPUNCH`、`GL_RETRY`、`GL_CP`、`GL_INSTALL`、`GL_SRC`、`GL_KO`、`GL_KOPARAM`、`GL_DMESG`、`GL_EXEC`、`GL_EXECBIN`、`GL_WR_VALUE`，以及所有"布尔开关"本身。

**一个现成的坑**：`GL_SCRATCH` 的默认值是 `SELINUX+0x800`，但如果你显式给了 `GL_SELINUX` 而**没给** `GL_SCRATCH`，p7 会按新 SELINUX 自动算出正确的 scratch（`add x1, x23, #2048`，0x400670，x23 就是刚解析的 SELINUX 结果）。但如果两者都显式给了、又给错页，`GL_ZEROSIG`/`GL_MECH` 会写到奇怪的地方。**建议两条都给**（档案里就是这么配的）。

---

## 6. 附：反汇编证据索引

| 关键点 | 地址 |
|---|---|
| `envoff()` 通用解析（base=0，空串走默认） | 0x401a20 |
| 8 个偏移参数的载入 + `[off]` 打印 | 0x4005a0–0x4006d4 |
| `GL_ATTR` 枚举分派 | 0x4006e0–0x4007e0 |
| 主线程 / waiter / owner 三线程启动 | 0x40083c–0x400868 |
| cred 计划构建（PERM/BUMP/FIXUID/ZEROSIG/MECH/IDENT/RETRY/RCRED） | 0x402c84–0x402e64 |
| cred 计划执行（kind 分派） | 0x402ea0–0x403210 |
| `GL_NTRIG` 读取与 trigger 循环 | 0x401694–0x401764 |
| `GL_NOPUNCH` 判定 | 0x4035e4 |
| `GL_OFF` + punch | 0x403ba4–0x403c18 |
| `punch_buf()`（SIGUSR1 + sigreturn 帧改写） | 0x4020a0 / handler 0x401a64 |
| WR 任意写 | 0x403860–0x4039d4 |
| VWR | 0x402918–0x402a58 |
| TCHK | 0x402b6c–0x402c30 |
| PROBE | 0x4026c0–0x402760 |
| 安装/加载/落盘/执行后处理链 | 0x403054–0x403180 |
| perf 泄漏 / `GL_AUTO` / `GL_IPF` / `GL_LEAK` | 0x400ab8–0x401440 |
