#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""mk331.py —— 把 KernelSU 3.3.1 的内核模块移植编译到 4.19.157-perf (ColorOS 14)

设计：
  * 源目录 KernelSU-3.3.1/kernel 保持原样不动，所有改动写到 p331/
  * 本脚本是**唯一**的补丁来源：每次运行都从原始树重建 p331，
    所以补丁不会叠加、可以反复跑
  * SELinux 策略注入层整体停用（设备跑 permissive，不需要 ksu 域/avtab 改写）

用法：
  python mk331.py            # 全量：重建源码树 → 编译 → 链接 → 版本表 → 符号重定向
  python mk331.py --keep     # 跳过重建（调试单个文件时快一点）
"""
import json
import os
import shutil
import struct
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sepol419_patch import patch_sepolicy_419

NDK = r'C:/Other/Andriod/toolchain/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
CC = os.path.join(NDK, 'clang.exe')
LD = os.path.join(NDK, 'ld.lld.exe')
NM = os.path.join(NDK, 'llvm-nm.exe')
OBJCOPY = os.path.join(NDK, 'llvm-objcopy.exe')
CFG = r'C:/k419'

BASE_DIR = r'C:/Other/Andriod/ksu'
ORIG = os.path.join(BASE_DIR, 'KernelSU-3.3.1', 'kernel')
PORT = os.path.join(BASE_DIR, 'p331')
OBJDIR = os.path.join(BASE_DIR, 'p331obj')
OUT = os.path.join(BASE_DIR, 'build', 'ksu331_419.ko')
CRC_MAP = r'C:/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/crc_map.json'

MODNAME = 'kernelsu'
KSU_VERSION = 32715

# 4.19 上不需要/不能编的架构分支
SKIP_OBJS = {'hook/riscv64/patch_memory.o', 'hook/riscv64/syscall_hook.o',
             'hook/x86_64/patch_memory.o', 'hook/x86_64/syscall_hook.o'}


# ---------------------------------------------------------------- 源码树重建

def reset_tree():
    if os.path.isdir(PORT):
        shutil.rmtree(PORT)
    shutil.copytree(ORIG, PORT)


# ---------------------------------------------------------------- 头文件垫片

SHIMS = {
    # 4.19 没有 linux/pgtable.h（5.0 才拆出来的），内容都在 asm/pgtable.h
    'shim/linux/pgtable.h': '''/* 4.19 垫片：linux/pgtable.h 是 5.0 才从 asm/pgtable.h 拆出来的 */
#ifndef _KSU331_SHIM_PGTABLE_H
#define _KSU331_SHIM_PGTABLE_H
#include <asm/pgtable.h>
#include <linux/mm.h>
#endif
''',
    # 4.19 的 min/max 还在 linux/kernel.h 里
    'shim/linux/minmax.h': '''/* 4.19 垫片：min/max 在 linux/kernel.h */
#ifndef _KSU331_SHIM_MINMAX_H
#define _KSU331_SHIM_MINMAX_H
#include <linux/kernel.h>
#endif
''',
    # 4.19 没有 uapi/linux/mount.h，MS_* 在 linux/mount.h
    'shim/uapi/linux/mount.h': '''/* 4.19 垫片：uapi/linux/mount.h 是 5.2 才有的，MS_* 在 linux/mount.h */
#ifndef _KSU331_SHIM_UAPI_MOUNT_H
#define _KSU331_SHIM_UAPI_MOUNT_H
#include <linux/mount.h>
#include <uapi/linux/fs.h>
#endif
''',
}


# ---------------------------------------------------------------- 代码补丁
# (相对 p331 的路径, 原文, 替换, 是否必须命中)

def patch_list():
    return [
        # ---- 1. seccomp 缓存：4.19 没有 action_cache，整层降级为 no-op ----
        ('infra/seccomp_cache.c', None, '''// SPDX-License-Identifier: GPL-2.0
/* 4.19 移植版：Linux 5.10 才给 seccomp_filter 加 action_cache
 * （commit 8e01b51 "seccomp: Introduce SECCOMP_ARCH_NATIVE cache"）。
 * 4.19 的每次 seccomp 检查都完整跑 BPF，不存在需要失效的位图，
 * 所以这两个函数在这里是纯 no-op。
 *
 * 注意：原版在这里重新定义 struct seccomp_filter 以访问 cache 字段，
 * 但那会让 prev/prog 的偏移与 4.19 内核不一致 —— 只要碰一下就取到垃圾指针。
 * 干脆不定义该结构。 */
#include <linux/types.h>

struct seccomp_filter;

void ksu_seccomp_clear_cache(struct seccomp_filter *filter, int nr)
{
    (void)filter;
    (void)nr;
}

void ksu_seccomp_allow_cache(struct seccomp_filter *filter, int nr)
{
    (void)filter;
    (void)nr;
}
''', True),

        # ---- 2. app_profile：filter_count 是 5.x 字段 ----
        ('policy/app_profile.c',
         '\tatomic_set(&current->seccomp.filter_count, 0);',
         '''#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 9, 0)
    atomic_set(&current->seccomp.filter_count, 0);
#endif
    /* 4.19: struct seccomp 只有 {mode, filter}，没有 filter_count */''', True),

        # ---- 3. file_wrapper：iopoll 是 5.5+ ----
        ('infra/file_wrapper.c',
         '#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 1, 0)\nstatic int ksu_wrapper_iopoll',
         '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 5, 0)
/* 4.19: file_operations 没有 iopoll（5.5 引入），整个 wrapper 跳过该回调 */
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(6, 1, 0)
static int ksu_wrapper_iopoll''', True),

        ('infra/file_wrapper.c',
         '\tp->ops.iopoll = fp->f_op->iopoll ? ksu_wrapper_iopoll : NULL;',
         '''#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 5, 0)
\tp->ops.iopoll = fp->f_op->iopoll ? ksu_wrapper_iopoll : NULL;
#endif''', True),

        # ---- 4. file_wrapper：remap_file_range 是 4.20+ ----
        ('infra/file_wrapper.c',
         'static loff_t ksu_wrapper_remap_file_range(struct file *file_in',
         '''#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 20, 0)
static loff_t ksu_wrapper_remap_file_range(struct file *file_in''', True),

        ('infra/file_wrapper.c',
         '''        return orig->f_op->remap_file_range(orig, pos_in, file_out, pos_out, len, remap_flags);
    }
}''',
         '''        return orig->f_op->remap_file_range(orig, pos_in, file_out, pos_out, len, remap_flags);
    }
}
#endif /* >= 4.20：4.19 用 clone_file_range/dedupe_file_range，不包装 */''', True),

        ('infra/file_wrapper.c',
         '    p->ops.remap_file_range = fp->f_op->remap_file_range ? ksu_wrapper_remap_file_range : NULL;',
         '''#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 20, 0)
    p->ops.remap_file_range = fp->f_op->remap_file_range ? ksu_wrapper_remap_file_range : NULL;
#endif''', True),

        # ---- 5. pkg_observer：fsnotify handle_event 签名在 5.9 变了 ----
        ('manager/pkg_observer.c',
         '''static int ksu_handle_inode_event(struct fsnotify_mark *mark, u32 mask, struct inode *inode, struct inode *dir,
                                  const struct qstr *file_name, u32 cookie)
{
    if (!file_name)
        return 0;
    if (mask & FS_ISDIR)
        return 0;
    if (file_name->len == 13 && !memcmp(file_name->name, "packages.list", 13)) {
        pr_info("packages.list detected: %d\\n", mask);
        ksu_defer_track_throne();
    }
    return 0;
}

static const struct fsnotify_ops ksu_ops = {
    .handle_inode_event = ksu_handle_inode_event,
};''',
         '''#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 9, 0)
static int ksu_handle_inode_event(struct fsnotify_mark *mark, u32 mask, struct inode *inode, struct inode *dir,
                                  const struct qstr *file_name, u32 cookie)
{
    if (!file_name)
        return 0;
    if (mask & FS_ISDIR)
        return 0;
    if (file_name->len == 13 && !memcmp(file_name->name, "packages.list", 13)) {
        pr_info("packages.list detected: %d\\n", mask);
        ksu_defer_track_throne();
    }
    return 0;
}

static const struct fsnotify_ops ksu_ops = {
    .handle_inode_event = ksu_handle_inode_event,
};
#else
/* 4.19 的 fsnotify_ops.handle_event：
 *   (group, inode, mask, data, data_type, file_name, cookie, iter_info)
 * file_name 是 NUL 结尾的 unsigned char*，不是 struct qstr。
 * dir 要从 data（fsnotify_event 的 inode）里取 —— 我们只关心文件名，
 * 不需要 dir，直接忽略。 */
static int ksu_handle_inode_event(struct fsnotify_group *group, struct inode *inode, u32 mask,
                                  const void *data, int data_type, const unsigned char *file_name,
                                  u32 cookie, struct fsnotify_iter_info *iter_info)
{
    (void)group; (void)inode; (void)data; (void)data_type;
    (void)cookie; (void)iter_info;

    if (!file_name)
        return 0;
    if (mask & FS_ISDIR)
        return 0;
    if (!strcmp((const char *)file_name, "packages.list")) {
        pr_info("packages.list detected: %d\\n", mask);
        ksu_defer_track_throne();
    }
    return 0;
}

static const struct fsnotify_ops ksu_ops = {
    .handle_event = ksu_handle_inode_event,
};
#endif''', True),

        # ---- 6. dispatch：tasklist_lock / init_task 4.19 未声明 ----
        ('supercall/dispatch.c',
         'static int do_set_init_pgrp(void __user *arg)',
         '''/* 4.19 里这两个符号没有在已包含的头里声明（init_task 导出、
 * tasklist_lock 未导出，由 patchsyms 重定向）。 */
extern rwlock_t tasklist_lock;
extern struct task_struct init_task;

static int do_set_init_pgrp(void __user *arg)''', True),

        # ---- 6.5 allowlist：put_task_struct 需要外部声明 ----
        ('policy/allowlist.c',
         '#include <linux/kref.h>',
         '''#include <linux/kref.h>

/* 4.19 的 put_task_struct 是 sched/task.h 里的 static inline，本文件没包含该头，
 * 只看到隐式声明（返回 int → 指针截断）。这里补正确的声明，
 * 实现在 port_compat.c（故意不放进全局兼容头，避免与 inline 版冲突）。 */
extern void put_task_struct(struct task_struct *t);''', True),

        # ---- 7. dispatch：4.19 的 task_pgrp/task_session 是 static inline ----
        ('supercall/dispatch.c',
         'static int do_set_init_pgrp(void __user *arg)',
         '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
/* 4.19 移植：task_pgrp()/task_session() 在这个内核里是 static inline
 * （task->group_leader->pids[...]），没有可重定向的符号。
 * 而且 SET_INIT_PGRP 只是让 su 进程拿到 init 的进程组，属于边缘功能，
 * 直接返回 -ENOSYS，不影响 root 授权主链路。 */
static int do_set_init_pgrp(void __user *arg)
{
    (void)arg;
    return -ENOSYS;
}
#else
static int do_set_init_pgrp(void __user *arg)''', True),

        ('supercall/dispatch.c',
         '''out:
    write_unlock_irq(&tasklist_lock);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 15, 0)
    free_pids(pids);
#endif

    return err;
}''',
         '''out:
    write_unlock_irq(&tasklist_lock);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 15, 0)
    free_pids(pids);
#endif

    return err;
}
#endif /* LINUX_VERSION_CODE < 5.0：4.19 走上面的 -ENOSYS 空实现 */''', True),

        # ---- 8.2 tracepoint 地址：不用编译期符号，改用运行时解析 ----
        ('hook/syscall_hook_manager.c',
         '    ret = register_trace_prio_sys_enter(ksu_sys_enter_handler, NULL, INT_MIN);',
         '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
    /* 4.19 移植：不用编译期的 &__tracepoint_sys_enter。
     * 那个符号没导出，我们靠 patchsyms 做 printk+delta 重定向；实测下去
     * tracepoint 注册时 panic（结构体里 regfunc 被当成函数指针调用了），
     * 说明地址来源不可靠。改用运行时 kallsyms 解析（和 sys_call_table 同一套，
     * 已实测准确），再把裸指针交给 tracepoint_probe_register_prio。 */
    {
        extern void *ksu_resolve_symbol_for_functable_hook(const char *name);
        struct tracepoint *ksu_tp =
            ksu_resolve_symbol_for_functable_hook("__tracepoint_sys_enter");

        void *ksu_rf = ksu_resolve_symbol_for_functable_hook("syscall_regfunc");

        ksu331_markf("hookmgr: tp=%px name=%px reg=%px funcs=%px",
                     ksu_tp,
                     ksu_tp ? ksu_tp->name : NULL,
                     ksu_tp ? (void *)ksu_tp->regfunc : NULL,
                     ksu_tp ? (void *)ksu_tp->funcs : NULL);
        ksu331_markf("hookmgr: expect syscall_regfunc=%px", ksu_rf);
        /* 4.19 的 func_add 对 prio 的处理与 5.x 不同，先用普通优先级，
         * 确认能注册成功再考虑提高（KSU 用 INT_MIN 只是要求最先执行）。 */
        ret = ksu_tp ? tracepoint_probe_register_prio(
                           ksu_tp, (void *)ksu_sys_enter_handler, NULL, 0)
                     : -ENOENT;
    }
#else
    ret = register_trace_prio_sys_enter(ksu_sys_enter_handler, NULL, INT_MIN);
#endif''', True),

        # ---- 8.5 patch_text：4.19 上绕开 stop_machine ----
        ('hook/arm64/patch_memory.c',
         '    return stop_machine(ksu_patch_text_cb, &info, cpu_online_mask);',
         '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
    /* 4.19 移植：不走 stop_machine。
     * 猜测崩溃点在 stop_machine 的多 CPU 停机状态机（回调里连 printk 都可能不安全），
     * 而我们打的是 syscall 表这种**数据**，不是正在执行的代码，
     * 单核写入 + 之后刷 dcache 足够安全（新槽位原本是 ni_syscall，没人调用）。 */
    (void)info;
    return ksu_patch_text_nosync(dst, src, len, flags);
#else
    return stop_machine(ksu_patch_text_cb, &info, cpu_online_mask);
#endif''', True),

        # ---- 8.6 pkg_observer：4.19 先停用（fsnotify 5.x API 改写后仍不稳）----
        ('manager/pkg_observer.c',
         'int ksu_observer_init(void)\n{',
         '''int ksu_observer_init(void)
{
#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
    /* 4.19 移植：先停用 fsnotify 观察器。
     * 它的唯一作用是"运行中检测 packages.list 被重写"（即管理器被重新安装），
     * 4.19 的 fsnotify 是 handle_event 老 API，改写后实测仍有问题。
     * 初始化结束时 init 还会调一次 track_throne(false) 做全量扫描，
     * 管理器照样能被认到；需要重新触发时重装一次 APK 即可。 */
    pr_info("ksu331: pkg_observer disabled in this port");
    return 0;
#endif''', True),

        # ---- 8.7 原始系统调用号改用 per-CPU 变量传递 ----
        ('hook/arm64/syscall_hook.c',
         '#include <linux/kallsyms.h>',
         '''#include <linux/kallsyms.h>
#include <linux/percpu.h>''', True),

        ('hook/arm64/syscall_hook.c',
         'syscall_fn_t *ksu_syscall_table = NULL;',
         '''syscall_fn_t *ksu_syscall_table = NULL;

#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
/* 4.19 移植：原始系统调用号用 per-CPU 变量传递。
 *
 * 原版把 nr 存进 regs->orig_x0，但 4.19 的 el0_svc 在派发前会覆写该字段，
 * dispatcher 读回来是垃圾 → 直接返回 -ENOSYS。实测后果是**所有 exec 失败**，
 * 系统连 sh 都起不来。
 *
 * tracepoint 回调与 dispatcher 在同一 CPU、同一进程上下文，中间不会调度，
 * 用 per-CPU 变量传递最稳妥。 */
DEFINE_PER_CPU(int, ksu331_orig_nr);

void ksu331_set_orig_nr(int nr)
{
    this_cpu_write(ksu331_orig_nr, nr);
}
#endif''', True),

        ('hook/arm64/syscall_hook.c',
         '    int orig_nr = (int)PT_REGS_ORIG_SYSCALL(regs);',
         '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
    int orig_nr = this_cpu_read(ksu331_orig_nr);
#else
    int orig_nr = (int)PT_REGS_ORIG_SYSCALL(regs);
#endif''', True),

        ('hook/syscall_hook_manager.c',
         '''        PT_REGS_ORIG_SYSCALL(current_regs) = id;
        current_regs->syscallno = ksu_dispatcher_nr;''',
         '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
        /* 4.19：orig_x0 会被内核覆写，改用 per-CPU 变量传原始 nr */
        ksu331_set_orig_nr((int)id);
        current_regs->syscallno = ksu_dispatcher_nr;
#else
        PT_REGS_ORIG_SYSCALL(current_regs) = id;
        current_regs->syscallno = ksu_dispatcher_nr;
#endif''', True),

        # ---- 8.56 dispatcher 槽位：写不进去就自动降级，别把系统搞死 ----
        ('hook/arm64/syscall_hook.c',
         '''    ksu_dispatcher_nr = ni_slot;
    ksu_syscall_table_hook(ksu_dispatcher_nr, (syscall_fn_t)ksu_syscall_dispatcher, NULL);''',
         '''    ksu_dispatcher_nr = ni_slot;
    ksu_syscall_table_hook(ksu_dispatcher_nr, (syscall_fn_t)ksu_syscall_dispatcher, NULL);

#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
    /* 4.19 移植：必须确认槽位真的被改写。
     * 若写入没落地，槽位仍是 ni_syscall，所有被重定向的系统调用都会 -ENOSYS
     * （实测表现为 exec 全挂、连 /system/bin/sh 都起不来）。
     * 与其把机器搞死，不如自动降级：把 dispatcher 关掉，系统保持可用。 */
    if (READ_ONCE(ksu_syscall_table[ksu_dispatcher_nr]) !=
        (syscall_fn_t)ksu_syscall_dispatcher) {
        ksu331_mark("syshook: DISPATCHER PATCH FAILED -> hooks disabled");
        pr_err("ksu331: dispatcher patch did not stick, syscall hooks disabled\\n");
        ksu_dispatcher_nr = -1;
    } else {
        ksu331_mark("syshook: dispatcher patch verified");
    }
#endif''', True),

        # ---- 8.7 set_app_profile：跳过全进程遍历（同 init 的处理）----
        ('supercall/dispatch.c',
         '        ksu_persistent_allow_list();\n        ksu_mark_running_process();',
         '''        ksu_persistent_allow_list();
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 0, 0)
        ksu_mark_running_process();
#else
        /* 4.19 移植：跳过。ksu_mark_running_process() 会 for_each_process_thread
         * 遍历所有进程并读 task->comm / task->pid / signal 链表，
         * 依赖我们头文件里未逐一验证的链表偏移。
         * 实测在管理器里给 shell 授权时当场 panic（重启）。
         * 它只是把 tracepoint 标记批量同步到已有进程，属于优化；
         * 新进程在 fork/exec 时会自己打标记，跳过不影响 su。 */
#endif''', True),

        # ---- 8.8 tp_marker：4.19 上禁用全进程/全线程遍历 ----
        ('hook/tp_marker.c',
         'static void handle_process_mark(bool mark)\n{',
         '''static void handle_process_mark(bool mark)
{
#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
    /* 4.19 移植：直接返回。
     * for_each_process_thread 要按 task_struct 的 tasks / thread_group 链表走，
     * 而这两个偏移我们无法从现有源码树保证与 ColorOS 一致 —— 实测一走就 panic
     * （在管理器里给 shell 授权时触发，整机重启）。
     * 它只负责把 tracepoint 标记批量同步到已有进程，属优化项；
     * 新进程在 fork/exec 时会自行打标记。 */
    (void)mark;
    return;
#endif''', True),

        ('hook/tp_marker.c',
         'void ksu_mark_running_process_locked(void)\n{',
         '''void ksu_mark_running_process_locked(void)
{
#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)
    /* 同上：4.19 不遍历进程表 */
    return;
#endif''', True),

        # ---- 9. init.c：去掉 kobject_del（手写 this_module 的 mkobj 是假地址）----
        ('core/init.c',
         '#ifdef MODULE\n#ifndef CONFIG_KSU_DEBUG\n    kobject_del(&THIS_MODULE->mkobj.kobj);\n#endif\n#endif',
         '''#ifdef MODULE
    /* 移植改动：__this_module 是手写布局（mkmod 风格），mkobj 指向假地址，
     * 原版的 kobject_del 会解引用它当场 panic。sysfs 里多留一个节点无副作用。 */
#endif''', True),

        # ---- 10. init.c：late-load 不许把 SELinux 改回 enforcing ----
        ('core/init.c',
         'if (!getenforce()) {\n            pr_info("Permissive SELinux, enforcing\\n");\n            setenforce(true);\n        }',
         '''/* 移植改动：本机跑 permissive（没有策略注入层，ksu 域不存在），
         * 原版在这里会把 enforcing 打开，直接导致所有 root 操作被 SELinux 拒绝。
         * 保持调用者传进来的状态，不要动。 */
        if (!getenforce())
            pr_info("ksu331: permissive mode kept (port build)\\n");''', True),

        # ---- 11. selinux.c：不做域切换（保持调用者域 + permissive） ----
        ('selinux/selinux.c',
         '''void setup_selinux(const char *domain, struct cred *cred)
{
    if (transive_to_domain(domain, cred, false)) {
        pr_err("transive domain failed.\\n");
        return;
    }
}''',
         '''void setup_selinux(const char *domain, struct cred *cred)
{
    /* 4.19 移植：不做域切换。
     *
     * 3.x 的正常流程是先把 "u:r:ksu:s0" 通过策略注入写进 policydb，
     * 再切过去。我们整体停用了策略注入层（设备跑 permissive），
     * 这个域根本不存在，切过去只会失败刷日志。
     *
     * 保持调用者自己的域（untrusted_app），uid 改成 0 后 DAC 不受限；
     * SELinux 层面靠全局 permissive 放行。这与 0.9.5 上验证过的方案一致。 */
    (void)domain;
    (void)cred;
}''', True),
    ]


def _flex_find(src, old):
    """先按字面找；找不到就退化成"空白宽松"匹配（tab 与空格视为等价）。
    KSU 源码里 tab/空格混用，硬编码缩进太脆。"""
    if old in src:
        return old, None
    import re
    parts = re.split(r'(\s+)', old)
    pat = ''
    for p in parts:
        if not p:
            continue
        if p.isspace():
            # 跨行空白才允许 \n；行内缩进只匹配空格/tab，否则会吃掉前一行的换行符
            pat += r'\s+' if '\n' in p else r'[ \t]+'
        else:
            pat += re.escape(p)
    m = re.search(pat, src)
    if not m:
        return None, None
    return m.group(0), m


def apply_patches():
    for rel, old, new, required in patch_list():
        p = os.path.join(PORT, rel)
        if old is None:
            open(p, 'w', encoding='utf-8', newline='\n').write(new)
            continue
        src = open(p, encoding='utf-8').read()
        found, m = _flex_find(src, old)
        if found is None:
            if required:
                raise SystemExit('补丁未命中: %s\n--- 期望片段 ---\n%s' % (rel, old[:200]))
            continue
        if m is None:
            src = src.replace(found, new, 1)
        else:
            src = src[:m.start()] + new + src[m.end():]
        open(p, 'w', encoding='utf-8', newline='\n').write(src)


# ---------------------------------------------------------------- 移植期插桩

def instrument():
    """给 init 每一步和 patch_text 关键点打点，崩溃后能定位到具体调用。"""
    import re

    p = os.path.join(PORT, 'core/init.c')
    src = open(p, encoding='utf-8').read()
    start = src.index('int __init kernelsu_init(void)')
    body_start = src.index('{', start)
    depth = 0
    end = body_start
    for i in range(body_start, len(src)):
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
            if depth == 0:
                end = i
                break
    body = src[body_start + 1:end]

    def repl(m):
        indent, call = m.group(1), m.group(2)
        return '%sksu331_mark("init: %s");\n%s%s();' % (indent, call, indent, call)

    # 必须锚定行首行尾：否则 setup_ksu_cred(); 里的 ksu_cred(); 子串也会被替换
    new_body = re.sub(r'(?m)^([ \t]*)(ksu_[a-z0-9_]+)\(\);$', repl, body)
    src = src[:body_start + 1] + new_body + src[end:]
    open(p, 'w', encoding='utf-8', newline='\n').write(src)

    # syscall_hook：分辨率最高的打点
    p = os.path.join(PORT, 'hook/arm64/syscall_hook.c')
    src = open(p, encoding='utf-8').read()
    src = src.replace(
        '    ksu_syscall_table = (syscall_fn_t *)ksu_resolve_symbol_for_functable_hook("sys_call_table");',
        '    ksu331_mark("syshook: enter");\n'
        '    ksu_syscall_table = (syscall_fn_t *)ksu_resolve_symbol_for_functable_hook("sys_call_table");\n'
        '    ksu331_markf("syshook: table=%px", ksu_syscall_table);')
    src = src.replace(
        '    if (ksu_find_ni_syscall_slots(&ni_slot, 1) < 1) {',
        '    ksu331_mark("syshook: find_ni begin");\n'
        '    if (ksu_find_ni_syscall_slots(&ni_slot, 1) < 1) {')
    src = src.replace(
        '    ksu_dispatcher_nr = ni_slot;',
        '    ksu331_markf("syshook: ni slot=%d", ni_slot);\n'
        '    ksu_dispatcher_nr = ni_slot;')
    src = src.replace(
        '    ni_syscall = (unsigned long)ksu_resolve_symbol_for_functable_hook("__arm64_sys_ni_syscall");',
        '    ni_syscall = (unsigned long)ksu_resolve_symbol_for_functable_hook("__arm64_sys_ni_syscall");\n'
        '    ksu331_markf("syshook: ni_syscall=%lx nr_max=%d", ni_syscall, (int)__NR_syscalls);')
    open(p, 'w', encoding='utf-8', newline='\n').write(src)

    # syscall_hook_manager：继续细分
    p = os.path.join(PORT, 'hook/syscall_hook_manager.c')
    src = open(p, encoding='utf-8').read()
    # 跳过全进程遍历：for_each_process_thread 依赖我们头文件里的 task_struct /
    # signal 偏移，与 ColorOS 实际布局不一致会直接崩。它只是把"无关进程"的
    # TIF_SYSCALL_TRACEPOINT 清掉以省开销，不清也能工作。
    src = src.replace('    ksu_mark_running_process_locked();',
                      '#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 0, 0)\n'
                      '    ksu_mark_running_process_locked();\n'
                      '#endif')
    src = src.replace('    // Register syscall hooks via dispatcher',
                      '    ksu331_mark("hookmgr: register syscalls");\n'
                      '    // Register syscall hooks via dispatcher')
    src = src.replace('    ret = register_trace_prio_sys_enter(ksu_sys_enter_handler, NULL, INT_MIN);',
                      '    ksu331_mark("hookmgr: tracepoint begin");\n'
                      '    ret = register_trace_prio_sys_enter(ksu_sys_enter_handler, NULL, INT_MIN);\n'
                      '    ksu331_markf("hookmgr: tracepoint ret=%d", ret);')
    src = src.replace('    ksu_setuid_hook_init();',
                      '    ksu331_mark("hookmgr: setuid_hook_init");\n'
                      '    ksu_setuid_hook_init();\n'
                      '    ksu331_mark("hookmgr: setuid_hook_init done");')
    src = src.replace('    ksu_sucompat_init();',
                      '    ksu331_mark("hookmgr: sucompat_init");\n'
                      '    ksu_sucompat_init();\n'
                      '    ksu331_mark("hookmgr: sucompat_init done");')
    open(p, 'w', encoding='utf-8', newline='\n').write(src)


# ---------------------------------------------------------------- 重写文本写入

def patch_text_writer():
    """4.19：整体重写 ksu_patch_text_nosync。

    原实现依赖 copy_to_kernel_nofault（4.19 无此函数）；我们补的那个版本用
    __arch_copy_from_user，它在 arm64 上走 ldtr（非特权加载）读源地址，
    而源是模块 .text（内核地址）→ 必定 fault → 返回 -EFAULT → patch 静默失败。
    后果：sys_call_table 槽位保持 ni_syscall，所有被重定向的 syscall 返回
    -ENOSYS，表现为 exec 全挂、连 /system/bin/sh 都起不来。

    改成按“最稳妥 → 最强”顺序试两种写法，每步 memcmp 回读校验：
      1) fixmap 别名（FIXMAP_PAGE_NORMAL 带 PTE_WRITE，页表属性可写）
      2) set_memory_rw：临时把该页改为可写，写完立刻恢复只读
    """
    p = os.path.join(PORT, 'hook/arm64/patch_memory.c')
    src = open(p, encoding='utf-8').read()
    sig = 'static int ksu_patch_text_nosync(void *dst, void *src, size_t len, int flags)'
    start = src.index(sig)
    end = src.index('\n}\n', start) + 3
    orig = src[start:end]
    inner = orig[orig.index('{') + 1:orig.rindex('}')]

    new419 = r"""
    /* 4.19 移植版：两段式写入 + 每步回读校验。
     *
     * 1) fixmap 别名：FIXMAP_PAGE_NORMAL 带 PTE_WRITE，最接近上游做法
     * 2) set_memory_rw：临时把该页改成可写（走内核自己的页表代码，不受
     *    init_mm 偏移问题影响），写完立刻恢复只读
     */
    extern int set_memory_rw(unsigned long addr, int numpages);
    extern int set_memory_ro(unsigned long addr, int numpages);

    unsigned long phy = __pa_symbol(dst);
    int ret = -EFAULT;

    {
        void *map = set_fixmap_offset(FIX_TEXT_POKE0, phy);

        memcpy(map, src, len);
        if (!memcmp(map, src, len)) {
            ret = 0;
            ksu331_markf("patch: fixmap ok dst=%lx", (unsigned long)dst);
        } else {
            ksu331_markf("patch: fixmap miss dst=%lx phy=%lx",
                         (unsigned long)dst, phy);
        }
        clear_fixmap(FIX_TEXT_POKE0);
    }

    if (ret) {
        unsigned long va = (unsigned long)dst & PAGE_MASK;
        int rw = set_memory_rw(va, 1);

        if (!rw) {
            memcpy(dst, src, len);
            if (!memcmp(dst, src, len)) {
                ret = 0;
                ksu331_markf("patch: set_memory_rw ok dst=%lx",
                             (unsigned long)dst);
            } else {
                ksu331_markf("patch: set_memory_rw miss dst=%lx",
                             (unsigned long)dst);
            }
            set_memory_ro(va, 1);
        } else {
            ksu331_markf("patch: set_memory_rw failed dst=%lx r=%d",
                         (unsigned long)dst, rw);
        }
    }

    if (!ret) {
        if (flags & KSU_PATCH_TEXT_FLUSH_ICACHE)
            ksu_flush_icache((uintptr_t)dst, (uintptr_t)dst + len);
        if (flags & KSU_PATCH_TEXT_FLUSH_DCACHE)
            ksu_flush_dcache(dst, len);
    }

    return ret;
"""

    new = (sig + '\n{\n#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 0, 0)\n'
           + new419 + '\n#else\n' + inner + '\n#endif\n}\n')
    src = src[:start] + new + src[end:]
    open(p, 'w', encoding='utf-8', newline='\n').write(src)


# ---------------------------------------------------------------- 整文件替换

STUB_FILES = {

    'selinux/sepolicy_419.c': r"""// SPDX-License-Identifier: GPL-2.0
/* 4.19 适配层：把 KSU 的「复制 policydb → 改 → 换指针」改成
 * 「复制 policydb → 改 → 序列化 → security_load_policy 重载」。
 *
 * 4.19 的 selinux_ss 里 policydb 是内联的，没有可替换的 policy 指针，
 * 所以只能走重载这条路。实测 policydb_write/security_load_policy 都可用。
 */
#include <linux/kernel.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include <linux/mutex.h>

#include "security.h"
#include "ss/services.h"
#include "ss/policydb.h"

#define KSU419_CAP (8UL << 20)

extern int policydb_write(struct policydb *p, void *fp);
extern int policydb_read(struct policydb *p, void *fp);
extern void policydb_destroy(struct policydb *p);

static DEFINE_MUTEX(ksu419_sepol_mutex);

void ksu419_lock(void)   { mutex_lock(&ksu419_sepol_mutex); }
void ksu419_unlock(void) { mutex_unlock(&ksu419_sepol_mutex); }

static struct policydb *ksu419_live_db(void)
{
    return &selinux_state.ss->policydb;
}

/* 复制当前在线策略；失败返回 NULL */
struct policydb *ksu419_policy_dup(void)
{
    struct policy_file fp;
    struct policydb *db;
    void *buf;
    size_t written;
    int ret;

    buf = vmalloc(KSU419_CAP);
    if (!buf)
        return NULL;

    fp.data = buf;
    fp.len = KSU419_CAP;
    ret = policydb_write(ksu419_live_db(), &fp);
    if (ret) {
        pr_err("ksu419: policydb_write failed: %d\n", ret);
        vfree(buf);
        return NULL;
    }
    written = KSU419_CAP - fp.len;

    db = kzalloc(sizeof(*db), GFP_KERNEL);
    if (!db) {
        vfree(buf);
        return NULL;
    }

    fp.data = buf;
    fp.len = written;
    ret = policydb_read(db, &fp);
    vfree(buf);
    if (ret) {
        pr_err("ksu419: policydb_read failed: %d\n", ret);
        kfree(db);
        return NULL;
    }
    return db;
}

/* 序列化并重载；返回 0 成功 */
int ksu419_policy_commit(struct policydb *db)
{
    struct policy_file fp;
    void *buf;
    size_t written;
    int ret;

    if (!db)
        return -EINVAL;

    buf = vmalloc(KSU419_CAP);
    if (!buf)
        return -ENOMEM;

    fp.data = buf;
    fp.len = KSU419_CAP;
    ret = policydb_write(db, &fp);
    if (ret) {
        pr_err("ksu419: commit write failed: %d\n", ret);
        vfree(buf);
        return ret;
    }
    written = KSU419_CAP - fp.len;

    ret = security_load_policy(&selinux_state, buf, written);
    pr_info("ksu419: policy reloaded len=%zu ret=%d\n", written, ret);
    vfree(buf);
    return ret;
}

void ksu419_policy_free(struct policydb *db)
{
    if (!db)
        return;
    policydb_destroy(db);
    kfree(db);
}
""",
    # ---- 4.19 完全没有的 API：模块内自带实现 ----
    'port_compat.c': r"""// SPDX-License-Identifier: GPL-2.0
/* 4.19 移植垫片：KSU 3.3.1 用到、但 4.19.157 内核里根本没有的 API。
 *
 * 每一处都必须同时给"声明 + 实现"：原来这些调用是隐式声明（返回 int），
 * 在 arm64 上会把返回的指针截断成 32 位，运行时直接 oops。 */
#include <linux/kernel.h>
#include <linux/types.h>
#include <linux/sched.h>
#include <linux/refcount.h>
#include <linux/cred.h>
#include <linux/fs.h>
#include <linux/uaccess.h>

struct task_security_struct;
struct inode_security_struct;

/* asm 版 uaccess：不带 access_ok（本机 addr_limit 偏移不对，access_ok 恒失败），
 * 异常表仍然兜底，PAN 切换在 asm 内部完成。 */
extern unsigned long __must_check __arch_copy_from_user(void *to,
        const void __user *from, unsigned long n);
extern unsigned long __must_check __arch_copy_to_user(void __user *to,
        const void *from, unsigned long n);

/* ---- 5.8 的 nofault 系列 ---- */
long copy_from_user_nofault(void *dst, const void __user *src, unsigned long size)
{
    return __arch_copy_from_user(dst, src, size) ? -EFAULT : 0;
}

long copy_to_user_nofault(void __user *dst, const void *src, unsigned long size)
{
    return __arch_copy_to_user(dst, src, size) ? -EFAULT : 0;
}

long copy_to_kernel_nofault(void *dst, const void *src, unsigned long size)
{
    /* 两边都是内核地址，直接 memcpy。
     *
     * 原来用 __arch_copy_from_user 是错的：arm64 上它用 ldtr（非特权加载）读源地址，
     * 源是模块 .text（内核地址）→ 必定 fault → 返回 -EFAULT → 整个 patch_text 失败，
     * 表现就是 sys_call_table 槽位永远是 ni_syscall，所有挂钩的 syscall 返回 ENOSYS。
     * 这是本次 exec 全挂的真正原因。 */
    memcpy(dst, src, size);
    return 0;
}

long strncpy_from_user_nofault(char *dst, const void __user *unsafe_addr, long count)
{
    long i;

    if (count <= 0)
        return count;

    for (i = 0; i < count; i++) {
        char c;

        if (__arch_copy_from_user(&c, unsafe_addr + i, 1))
            return -EFAULT;
        dst[i] = c;
        if (!c)
            return i;
    }
    return i;
}

/* ---- 4.20 的 security_inode_init_security_anon ----
 * 用于给 file_wrapper 造匿名 inode。4.19 没有这个 LSM 钩子，
 * 直接不支持：wrapper 创建失败后调用方会退回原文件，功能降级但不崩。 */
int security_inode_init_security_anon(struct inode *inode, const void *name,
                                      const struct inode *context_inode)
{
    (void)inode;
    (void)name;
    (void)context_inode;
    return -EOPNOTSUPP;
}

/* ---- put_task_struct：4.19 里是 static inline，但这棵树里没被 inline 化 ---- */
void __put_task_struct(struct task_struct *t);

void put_task_struct(struct task_struct *t)
{
    if (refcount_dec_and_test(&t->usage))
        __put_task_struct(t);
}

/* ---- seccomp_filter_release：5.9 才改名，4.19 叫 put_seccomp_filter ---- */
extern void put_seccomp_filter(struct task_struct *tsk);

void seccomp_filter_release(struct task_struct *tsk)
{
    put_seccomp_filter(tsk);
}

/* ---- 5.9 的 path_mount / path_umount ----
 * 4.19 的 do_mount() 收的是**路径字符串**而不是 struct path，
 * 没有等价的"按 path 挂载/卸载"入口。KSU 用它们做模块挂载的隔离，
 * 我们暂时不用模块，直接返回 -ENOSYS，相关功能降级。 */
int path_mount(const char *dev_name, struct path *path, const char *type_page,
               unsigned long flags, void *data_page)
{
    (void)dev_name; (void)path; (void)type_page; (void)flags; (void)data_page;
    return -ENOSYS;
}

int path_umount(struct path *path, int flags)
{
    (void)path; (void)flags;
    return -ENOSYS;
}

/* ---- 移植期打点：写文件 + fsync，panic 重启后日志仍在 ----
 * 0.9.5 移植时验证过：只有 vfs_fsync 才能保证崩溃后日志落盘。 */
void ksu331_mark(const char *tag)
{
    struct file *f = filp_open("/data/local/tmp/ksu331.log",
                               O_WRONLY | O_CREAT | O_APPEND, 0644);
    loff_t pos = 0;

    if (IS_ERR(f))
        return;
    kernel_write(f, tag, strlen(tag), &pos);
    kernel_write(f, "\n", 1, &pos);
    vfs_fsync(f, 1);
    filp_close(f, NULL);
}

/* 带格式化的版本，用来把地址值写进日志 */
void ksu331_markf(const char *fmt, ...)
{
    char buf[192];
    va_list ap;

    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    ksu331_mark(buf);
}

/* ---- SELinux 访问器：4.19 是 static inline，这棵树里没有 ----
 * 布局就是 cred->security / inode->i_security，与 4.19 一致。 */
struct task_security_struct *selinux_cred(const struct cred *cred)
{
    return cred ? (struct task_security_struct *)cred->security : NULL;
}

struct inode_security_struct *selinux_inode(const struct inode *inode)
{
    return inode ? (struct inode_security_struct *)inode->i_security : NULL;
}
""",
    # SELinux 策略注入层：整套停用


    'feature/selinux_hide.c': '''// SPDX-License-Identifier: GPL-2.0
/* 4.19 移植：SELinux 状态伪装层停用。
 * 原版 hook 了 selinux_setprocattr / selinuxfs 的 status、access 节点，
 * 依赖 5.x 的 selinux_policy 结构与 security_*_with_policy 系列函数。
 * 我们跑 permissive，本来也没有"隐藏"的需求（隐藏是为了对抗检测），
 * 先全部做成空实现，等主链路稳定后再单独补。 */
#include <linux/kernel.h>

void ksu_selinux_hide_init(void)
{
    pr_info("ksu331: selinux_hide disabled in this port\\n");
}

void ksu_selinux_hide_exit(void) { }

void ksu_selinux_hide_drop_backup_if_unused(void) { }

void ksu_selinux_hide_handle_second_stage(void) { }

void ksu_selinux_hide_handle_post_fs_data(void) { }
''',
}


def write_stubs():
    for rel, content in SHIMS.items():
        full = os.path.join(PORT, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        open(full, 'w', encoding='utf-8', newline='\n').write(content)
    for rel, content in STUB_FILES.items():
        p = os.path.join(PORT, rel)
        open(p, 'w', encoding='utf-8', newline='\n').write(content)


# ---------------------------------------------------------------- 编译

def obj_list():
    kb = open(os.path.join(PORT, 'Kbuild'), encoding='utf-8').read()
    objs = []
    for line in kb.splitlines():
        line = line.strip()
        if not line.startswith('kernelsu-objs'):
            continue
        if '+=' in line:
            tail = line.split('+=', 1)[1]
        elif ':=' in line:
            tail = line.split(':=', 1)[1]
        else:
            continue
        for tok in tail.split():
            if tok.endswith('.o'):
                objs.append(tok)
    # 手动补 arm64 分支（Kbuild 里由 CONFIG_ARM64 决定）
    objs += ['hook/arm64/patch_memory.o', 'hook/arm64/syscall_hook.o']
    objs += ['port_compat.o', 'selinux/sepolicy_419.o']
    out = []
    for o in objs:
        if o in SKIP_OBJS or o in out:
            continue
        if not os.path.exists(os.path.join(PORT, o[:-2] + '.c')):
            print('  ! 源文件不存在，跳过:', o)
            continue
        out.append(o)
    return out


def build_cflags():
    inc = [
        '-I' + os.path.join(PORT, 'shim'),
        '-I' + PORT,
        '-I' + os.path.join(PORT, 'include'),
        '-I' + os.path.join(BASE_DIR, 'KernelSU-3.3.1'),
        '-I' + os.path.join(BASE_DIR, 'hdr3/include'),
        '-I' + os.path.join(BASE_DIR, 'hdr3/arch/arm64/include'),
        '-I' + os.path.join(CFG, 'arch/arm64/include'),
        '-I' + os.path.join(CFG, 'arch/arm64/include/generated'),
        '-I' + os.path.join(CFG, 'arch/arm64/include/uapi'),
        '-I' + os.path.join(CFG, 'arch/arm64/include/generated/uapi'),
        '-I' + os.path.join(CFG, 'include'),
        '-I' + os.path.join(CFG, 'include/generated'),
        '-I' + os.path.join(CFG, 'include/uapi'),
        '-I' + os.path.join(CFG, 'include/generated/uapi'),
        '-I' + os.path.join(BASE_DIR, 'selinux_hdr/security/selinux'),
        '-I' + os.path.join(BASE_DIR, 'selinux_hdr/security/selinux/include'),
    ]
    defines = [
        '-DCONFIG_KSU=1', '-DCONFIG_ARM64=1', '-DCONFIG_KPROBES=1',
        '-DCONFIG_EXT4_FS=1', '-DCONFIG_SECURITY_SELINUX=1',
        '-DKSU_VERSION=%d' % KSU_VERSION,
        '-DKSU_PACKAGE_NAME="me.weishu.kernelsu"',
        '-DEXPECTED_SIZE=0x033b',
        '-DEXPECTED_HASH="c371061b19d8c7d7d6133c6a9bafe198fa944e50c1b31c9d8daa8d7f1fc2d2d6"',
        '-DKSU_IS_PR_BUILD=0', '-DKSU_NEW_DCACHE_FLUSH=0',
    ]
    base = [
        '-target', 'aarch64-linux-gnu', '-march=armv8-a', '-mcmodel=large',
        '-fno-pic', '-fno-pie', '-fno-asynchronous-unwind-tables',
        '-fno-unwind-tables', '-fno-stack-protector', '-fno-omit-frame-pointer',
        '-mgeneral-regs-only', '-D__KERNEL__', '-DMODULE',
        '-DKBUILD_MODNAME="%s"' % MODNAME,
        '-DCONFIG_HAVE_ARCH_PREL32_RELOCATIONS=1',
        '-include', os.path.join(CFG, 'include/linux/kconfig.h'),
        '-include', os.path.join(BASE_DIR, 'hdr3/include/linux/android_kabi.h'),
        '-include', os.path.join(BASE_DIR, 'ksu331_compat.h'),
        '-Wno-everything',
    ]
    return base + defines + inc


def compile_all(objs):
    os.makedirs(OBJDIR, exist_ok=True)
    flags = build_cflags()
    ok, bad = [], []
    log = []
    for o in objs:
        src = os.path.join(PORT, o[:-2] + '.c')
        dst = os.path.join(OBJDIR, o.replace('/', '_'))
        if os.path.exists(dst):
            os.remove(dst)
        r = subprocess.run([CC] + flags + ['-c', src, '-o', dst],
                           capture_output=True, text=True)
        if r.returncode == 0:
            ok.append(dst)
        else:
            bad.append(o)
            log.append('===== %s =====\n%s' % (o, r.stderr[-4000:]))
    if log:
        open(os.path.join(BASE_DIR, 'build331.log'), 'w', encoding='utf-8').write('\n'.join(log))
    print('  编译成功 %d，失败 %d' % (len(ok), len(bad)))
    for b in bad:
        print('    FAIL', b)
    return ok, bad


# ---------------------------------------------------------------- 链接

def gen_support_sources():
    """生成 modinfo / this_module / 版本表 三个辅助 .c"""
    d = OBJDIR
    open(os.path.join(d, 'gen_modinfo.c'), 'w', encoding='utf-8', newline='\n').write('''/* 手写 modpost 产物：.modinfo */
#define KSU_MODINFO(tag, val)							\\
	static const char __modinfo_##tag[]					\\
	__attribute__((section(".modinfo"), used, aligned(1))) = #tag "=" val

KSU_MODINFO(vermagic, "4.19.157-perf+ SMP preempt mod_unload modversions aarch64");
KSU_MODINFO(license, "GPL");
KSU_MODINFO(name, "%s");
KSU_MODINFO(depends, "");
KSU_MODINFO(description, "KernelSU 3.3.1 port for 4.19.157");
''' % MODNAME)

    open(os.path.join(d, 'gen_thismod.c'), 'w', encoding='utf-8', newline='\n').write('''/* 按设备内核实测布局手写 __this_module：
 *   name@0x18  init@0x188  exit@0x350  size 0x380
 * （偏移由 modprobe2.ko 在真机上读 THIS_MODULE 验证过） */
#define SEC __attribute__((section(".gnu.linkonce.this_module"), used))

extern int init_module(void);
extern void cleanup_module(void);

struct ksu_module_layout {
	unsigned char pad0[0x18];
	char name[56];
	unsigned char pad1[0x188 - 0x50];
	void *init_fn;
	unsigned char pad2[0x350 - 0x190];
	void *exit_fn;
	unsigned char pad3[0x380 - 0x358];
};

struct ksu_module_layout __this_module SEC __attribute__((aligned(64))) = {
	.name = "%s",
	.init_fn = init_module,
	.exit_fn = cleanup_module,
};
''' % MODNAME)


def link_objects(objs, out):
    r = subprocess.run([LD, '-r', '-m', 'aarch64elf', '-T', r'C:/k419/module.lds'] + objs + ['-o', out],
                       capture_output=True, text=True)
    if r.returncode:
        print(r.stdout)
        print(r.stderr[-3000:])
        raise SystemExit('链接失败')
    return out


def undef_symbols(ko):
    out = subprocess.run([NM, '-u', ko], capture_output=True, text=True).stdout
    names = []
    for line in out.splitlines():
        p = line.split()
        if len(p) == 2 and p[0] == 'U':
            names.append(p[1])
    return [n for n in dict.fromkeys(names)
            if n not in ('__this_module', 'init_module', 'cleanup_module')]


def gen_versions(ko, path):
    """按 crc_map 生成 __versions 段：内核要求所有带 CRC 的符号都必须列上"""
    cm = json.load(open(CRC_MAP))
    und = undef_symbols(ko)
    have = [s for s in und if s in cm]
    lines = ['struct modversion_info { unsigned long crc; char name[56]; };',
             'static const struct modversion_info ksu_versions[]',
             '    __attribute__((section("__versions"), used)) = {',
             '\t{0x%08xUL, "module_layout"},' % cm['module_layout']]
    for s in have:
        if s == 'module_layout':
            continue
        lines.append('\t{0x%08xUL, "%s"},' % (cm[s], s))
    lines.append('};')
    open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
    return [s for s in und if s not in cm]


def build_support(path, flags_free):
    free = ['-target', 'aarch64-linux-gnu', '-mcmodel=large', '-fno-pic',
            '-ffreestanding', '-Wno-everything']
    outs = []
    for name in ('gen_modinfo', 'gen_thismod', 'gen_versions'):
        src = os.path.join(OBJDIR, name + '.c')
        if not os.path.exists(src):
            continue
        o = os.path.join(OBJDIR, name + '.o')
        r = subprocess.run([CC] + free + ['-c', src, '-o', o], capture_output=True, text=True)
        if r.returncode:
            print(r.stderr[-2000:])
            raise SystemExit('辅助目标编译失败: ' + name)
        outs.append(o)
    return outs


def main():
    keep = '--keep' in sys.argv
    print('[1/5] 重建源码树 %s' % ('(跳过)' if keep else ''))
    if not keep:
        reset_tree()
    write_stubs()
    apply_patches()
    patch_text_writer()
    patch_sepolicy_419(PORT)
    instrument()

    print('[2/5] 编译')
    objs = obj_list()
    okay, bad = compile_all(objs)
    if bad:
        print('  有文件编译失败，中止')
        return 1

    print('[3/5] 生成辅助目标')
    gen_support_sources()
    support = build_support(None, None)

    print('[4/5] 链接')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    tmp = os.path.join(OBJDIR, 'ksu331_raw.ko')
    link_objects(support[0:2] + okay, tmp)
    missing = gen_versions(tmp, os.path.join(OBJDIR, 'gen_versions.c'))
    ver_obj = build_support(None, None)[2]
    link_objects([ver_obj] + support[0:2] + okay, tmp)
    print('  内核里没有的符号（由 patchsyms 处理）:', missing if missing else '无')

    print('[5/5] 写最终模块')
    shutil.copyfile(tmp, OUT)
    print('  输出:', OUT, os.path.getsize(OUT), 'bytes')
    print('  未定义符号', len(undef_symbols(OUT)), '个')
    return 0


if __name__ == '__main__':
    sys.exit(main())
