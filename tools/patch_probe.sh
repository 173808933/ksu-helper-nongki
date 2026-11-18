#!/bin/bash
# 在 kernelsu_init 最开头创建一个 /proc/ksu_probe 节点，
# 用来判断 init 到底有没有执行（这颗内核上 printk 看不到）
set -e
cd /home/dev/gl419/src/ksu/kernel

python3 - <<'PY'
p = "core/init.c"
s = open(p).read()

if "ksu_probe_show" in s:
    print("已打过补丁")
    raise SystemExit(0)

# 1) 在文件靠前处加 include 和 handler
anchor = 'int __init kernelsu_init(void)\n{'
probe_code = '''#include <linux/proc_fs.h>
#include <linux/seq_file.h>

/* 调试信号灯：这颗内核上 printk 看不到，用一个 /proc 节点证明 init 执行到了 */
static int ksu_probe_show(struct seq_file *m, void *v)
{
    seq_printf(m, "alive level=%d\\n", ksu54_level);
    return 0;
}

static int __init ksu_probe_init(void)
{
    proc_create_single("ksu_probe", 0444, NULL, ksu_probe_show);
    return 0;
}

int __init kernelsu_init(void)
{
    ksu_probe_init();
'''
if anchor not in s:
    print("锚点没找到")
    raise SystemExit(1)
s = s.replace(anchor, probe_code, 1)
open(p, "w").write(s)
print("已插入 /proc/ksu_probe")
PY

timeout 900 make -C /home/dev/sm8250-kern M="$PWD" src="$PWD" CONFIG_KSU=m \
    ARCH=arm64 CC=clang LD=ld.lld CLANG_TRIPLE=aarch64-linux-gnu- \
    CROSS_COMPILE=aarch64-linux-gnu- modules -j8 < /dev/null 2>&1 \
  | grep -E "error:|LD \[M\]" | tail -4
ls -la kernelsu.ko
