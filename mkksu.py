#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""mkksu.py —— 把 KernelSU 0.9.5 的 kernel/ 编成 kernelsu.ko（外挂模块形式）

不依赖内核源码树：头文件用 C:/k419（已按设备 /proc/config.gz 校准），
selinux 内部头文件从 findx3 源码树里单独抠出来放在 ksu/selinux_hdr。
符号 CRC 用 crc_map.json 伪造，和 mkmod.py 同一套。

用法：python mkksu.py [模块名]
"""
import json
import os
import subprocess
import sys

NDK = r'C:/Other/Andriod/toolchain/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
CC = os.path.join(NDK, 'clang.exe')
LD = os.path.join(NDK, 'ld.lld.exe')
NM = os.path.join(NDK, 'llvm-nm.exe')
CFG = r'C:/k419'
KSU = r'C:/Other/Andriod/ksu/port'
SEL = r'C:/Other/Andriod/ksu/selinux_hdr/security/selinux'
D = r'C:/Other/Andriod/ksu/build'

NAME = sys.argv[1] if len(sys.argv) > 1 else 'kernelsu'
OUT = os.path.join(D, '%s.ko' % NAME)

SRCS = ['ksu.c', 'allowlist.c', 'apk_sign.c', 'sucompat.c', 'throne_tracker.c',
        'core_hook.c', 'ksud.c', 'embed_ksud.c', 'kernel_compat.c',
        'selinux/selinux.c', 'selinux/sepolicy.c', 'selinux/rules.c']

INC = ' '.join([
    '-I%s/arch/arm64/include' % CFG, '-I%s/arch/arm64/include/generated' % CFG,
    '-I%s/arch/arm64/include/uapi' % CFG, '-I%s/arch/arm64/include/generated/uapi' % CFG,
    '-I%s/include' % CFG, '-I%s/include/generated' % CFG,
    '-I%s/include/uapi' % CFG, '-I%s/include/generated/uapi' % CFG,
    '-I' + KSU, '-I%s/include' % KSU,
    '-I' + SEL, '-I%s/include' % SEL,
])

DEFS = ' '.join([
    '-D__KERNEL__', '-DMODULE', '-DKBUILD_MODNAME="%s"' % NAME,
    '-DKSU_VERSION=12000', '-DKSU_COMPAT_HAS_CURRENT_SID', '-DKSU_COMPAT_HAS_SELINUX_STATE',
    '-DEXPECTED_SIZE=0x033b',
    '-DEXPECTED_HASH="c371061b19d8c7d7d6133c6a9bafe198fa944e50c1b31c9d8daa8d7f1fc2d2d6"',
])

BASE = ('-target aarch64-linux-gnu -march=armv8-a -mcmodel=large -fno-pic -fno-pie '
        '-fno-asynchronous-unwind-tables -fno-unwind-tables -fno-stack-protector '
        '-fno-omit-frame-pointer -mgeneral-regs-only -Wno-everything '
        '-include %s/include/linux/kconfig.h' % CFG)
FREE = '-target aarch64-linux-gnu -mcmodel=large -fno-pic -ffreestanding -Wno-everything'


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print('FAIL:', ' '.join(cmd)[:200])
        print(r.stdout[-4000:])
        print(r.stderr[-4000:])
        sys.exit(1)
    return r.stdout


os.makedirs(D, exist_ok=True)

# modinfo
open(os.path.join(D, 'mod.c'), 'w').write('''/* 手写 modpost 产物：.modinfo */
#define RK_MODINFO(tag, val)							\\
	static const char __modinfo_##tag[]					\\
	__attribute__((section(".modinfo"), used, aligned(1))) = #tag "=" val

RK_MODINFO(vermagic, "4.19.157-perf+ SMP preempt mod_unload modversions aarch64");
RK_MODINFO(license, "GPL");
RK_MODINFO(name, "%(n)s");
RK_MODINFO(depends, "");
RK_MODINFO(description, "KernelSU (lkm port)");
''' % {'n': NAME})

# __this_module
open(os.path.join(D, 'thismod.c'), 'w').write('''#define SEC __attribute__((section(".gnu.linkonce.this_module"), used))

extern int init_module(void);
extern void cleanup_module(void);

struct rk_module_layout {
	unsigned char pad0[0x18];
	char name[56];
	unsigned char pad1[0x188 - 0x50];
	void *init_fn;
	unsigned char pad2[0x350 - 0x190];
	void *exit_fn;
	unsigned char pad3[0x380 - 0x358];
};

struct rk_module_layout __this_module SEC __attribute__((aligned(64))) = {
	.name = "%(n)s",
	.init_fn = init_module,
	.exit_fn = cleanup_module,
};
''' % {'n': NAME})

objs = []
for s in SRCS:
    o = os.path.join(D, s.replace('/', '_').replace('.c', '.o'))
    run([CC] + (BASE + ' ' + DEFS).split() + INC.split() + ['-c', os.path.join(KSU, s), '-o', o])
    objs.append(o)

run([CC] + FREE.split() + ['-c', os.path.join(D, 'mod.c'), '-o', os.path.join(D, 'mod.o')])
run([CC] + FREE.split() + ['-c', os.path.join(D, 'thismod.c'), '-o', os.path.join(D, 'thismod.o')])
objs += [os.path.join(D, 'mod.o'), os.path.join(D, 'thismod.o')]

# 先把所有 .o 链成一个临时 ko，再对合并后的符号表取 U ——
# 直接 nm -u 多个 .o 会把"别的 .o 里已定义"的符号也报成 U
TMP = os.path.join(D, 'tmp_%s.ko' % NAME)
run([LD, '-r', '-m', 'aarch64elf', '-T', r'C:/k419/module.lds'] + objs + ['-o', TMP])
und = []
for line in run([NM, '-u', TMP]).splitlines():
    p2 = line.split()
    if len(p2) == 2 and p2[0] == 'U':
        und.append(p2[1])
und = [u for u in dict.fromkeys(und)
       if u not in ('__this_module', 'init_module', 'cleanup_module')]

cm = json.load(open(r'C:/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/crc_map.json'))
miss = [u for u in und if u not in cm]
if miss:
    print('NOT EXPORTED (%d): %s' % (len(miss), miss))
    print('(这些需要运行时解析，先只链接已导出的)')

L = ['struct modversion_info { unsigned long crc; char name[56]; };',
     'static const struct modversion_info rk_versions[] __attribute__((section("__versions"), used)) = {',
     '\t{ 0x%08xUL, "module_layout" },' % cm['module_layout']]
for u in und:
    if u in cm:
        L.append('\t{ 0x%08xUL, "%s" },' % (cm[u], u))
L.append('};')
open(os.path.join(D, 'versions.c'), 'w').write('\n'.join(L) + '\n')
run([CC] + FREE.split() + ['-c', os.path.join(D, 'versions.c'), '-o', os.path.join(D, 'versions.o')])

run([LD, '-r', '-m', 'aarch64elf', '-T', r'C:/k419/module.lds',
     os.path.join(D, 'versions.o')] + objs + ['-o', OUT])
print('exported deps: %d, missing: %d' % (len([u for u in und if u in cm]), len(miss)))
print('built', OUT, os.path.getsize(OUT))
if miss:
    open(os.path.join(D, 'missing.txt'), 'w').write('\n'.join(miss) + '\n')
