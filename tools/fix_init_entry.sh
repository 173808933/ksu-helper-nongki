#!/bin/bash
# 根因：内核取模块 init 入口 = .init.text 段起始地址。
# 这个 ko 的 .init.text 第一个函数是 ksu_setup_stack_chk_guard（返回 void），
# 于是内核把辅助函数当 init 调用，真正的 kernelsu_init 永远不执行。
# 修法：把辅助函数移出 .init.text（去掉 __init），让 kernelsu_init_early 排在段首。
set -e
cd /home/dev/gl419/src/ksu/kernel

python3 - <<'PY'
p = "core/init.c"
s = open(p).read()
n = 0

old = "__attribute__((no_stack_protector)) void __init ksu_setup_stack_chk_guard()"
new = "/* 不能标 __init：否则它会排在 .init.text 段首，被内核当成模块 init 入口 */\n__attribute__((no_stack_protector)) void ksu_setup_stack_chk_guard()"
if old in s:
    s = s.replace(old, new, 1); n += 1

old2 = "static int __init ksu_probe_init(void)"
new2 = "static int ksu_probe_init(void)"
if old2 in s:
    s = s.replace(old2, new2, 1); n += 1

open(p, "w").write(s)
print("改了 %d 处" % n)
PY

timeout 900 make -C /home/dev/sm8250-kern M="$PWD" src="$PWD" CONFIG_KSU=m \
    ARCH=arm64 CC=clang LD=ld.lld CLANG_TRIPLE=aarch64-linux-gnu- \
    CROSS_COMPILE=aarch64-linux-gnu- modules -j8 < /dev/null 2>&1 \
  | grep -E "error:|LD \[M\]" | tail -3

echo "=== .init.text 前三个函数（第一个就是内核会调用的 init）==="
llvm-objdump -t kernelsu.ko 2>/dev/null | grep " F .init.text" | sort -k1 | head -3
ls -la kernelsu.ko
