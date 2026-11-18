#!/bin/bash
# 把关键 pr_info 改成 pr_err（level 3），这颗内核上看不到 pr_info
set -e
cd /home/dev/gl419/src/ksu/kernel

sed -i 's/pr_info("welcome to KernelSU version /pr_err("welcome to KernelSU version /' core/init.c
sed -i 's/pr_info("ksu54: level=%d, skip dispatcher install/pr_err("ksu54: level=%d, skip dispatcher install/' hook/arm64/syscall_hook.c
sed -i 's/pr_info("sys_call_table=0x%lx"/pr_err("sys_call_table=0x%lx"/' hook/arm64/syscall_hook.c
sed -i 's/pr_info("hook_manager: ksu_hook_manager_init called/pr_err("hook_manager: ksu_hook_manager_init called/' hook/syscall_hook_manager.c

grep -n "pr_err(\"welcome" core/init.c | head -2
grep -n "pr_err(\"sys_call_table" hook/arm64/syscall_hook.c | head -2
grep -n "pr_err(\"hook_manager" hook/syscall_hook_manager.c | head -2

timeout 900 make -C /home/dev/sm8250-kern M="$PWD" src="$PWD" CONFIG_KSU=m \
    ARCH=arm64 CC=clang LD=ld.lld CLANG_TRIPLE=aarch64-linux-gnu- \
    CROSS_COMPILE=aarch64-linux-gnu- modules -j8 < /dev/null 2>&1 \
  | grep -E "error:|LD \[M\]" | tail -3
ls -la kernelsu.ko
