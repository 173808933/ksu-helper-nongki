/* 让 KSU 3.3.1 能在 4.19 上编译的兼容补丁头 */
#ifndef KSU331_COMPAT_H
#define KSU331_COMPAT_H

/* 5.4+ 的符号命名空间 */
#ifndef MODULE_IMPORT_NS
#define MODULE_IMPORT_NS(ns)
/* 4.19 的 hashtab 是「槽位数组 + 链表」，KSU 源码用的是 5.x 的 hashtab_for_each。
 * 这里补一个等价遍历：内层沿 next 走，外层扫所有槽位。 */
#ifndef ksu_hashtab_for_each
#define ksu_hashtab_for_each(htab, node)                      \
    for (u32 __ksu_slot = 0; __ksu_slot < (htab)->size; __ksu_slot++) \
        for ((node) = (htab)->htable[__ksu_slot]; (node); (node) = (node)->next)
#endif

#endif

/* 5.x 的 CFI 注解 */
#ifndef __nocfi
#define __nocfi
#endif

/* 5.x 的隐式 fallthrough 注解 */
#ifndef fallthrough
#define fallthrough __attribute__((fallthrough))
#endif

/* 5.x 的 task_work_add notify 参数；4.19 是 bool，传 1 等价于 TWA_RESUME */
#ifndef TWA_RESUME
#define TWA_RESUME 1
#endif
#ifndef TWA_NONE
#define TWA_NONE 0
#endif
#ifndef TWA_SIGNAL
#define TWA_SIGNAL 1
#endif

/* 5.x 才有的辅助宏 */
#ifndef __GFP_NOFAIL
#define __GFP_NOFAIL 0
#endif

#ifndef KSU_PACKAGE_NAME
#define KSU_PACKAGE_NAME "me.weishu.kernelsu"
#endif
#ifndef KSU_VERSION
#define KSU_VERSION 32715
#endif

/* ==========================================================================
 * 4.19 根本没有的 API
 *
 * 这些调用在原始 KSU 里是**隐式声明**（C 默认返回 int），在 arm64 上会把
 * 返回的指针截断到 32 位 —— 编译能过，一跑就 oops。所以必须显式声明。
 * 实现全部在 port_compat.c。
 *
 * 注意：put_task_struct 不在这里声明 —— 4.19 的 sched/task.h 里它是
 * static inline，全局声明会导致 "static declaration follows non-static"。
 * 只有 allowlist.c 需要外部版本，在那边局部声明。
 * ======================================================================== */
/* 注意：这些前置声明必须在函数原型**之前**。C 规定 struct 标签若首次出现
 * 在函数参数/返回类型里，它的作用域只有那个原型（prototype scope），
 * 会变成和后面真正的 struct 不同的类型 → "conflicting types"。 */
struct cred;
struct inode;
struct path;
struct task_struct;
struct task_security_struct;
struct inode_security_struct;

long copy_from_user_nofault(void *dst, const void *src, unsigned long size);
long copy_to_user_nofault(void *dst, const void *src, unsigned long size);
long copy_to_kernel_nofault(void *dst, const void *src, unsigned long size);
long strncpy_from_user_nofault(char *dst, const void *src, long count);

int security_inode_init_security_anon(struct inode *inode, const void *name,
                                      const struct inode *context_inode);

void seccomp_filter_release(struct task_struct *tsk);

int path_mount(const char *dev_name, struct path *path, const char *type_page,
               unsigned long flags, void *data_page);
int path_umount(struct path *path, int flags);

long strscpy_pad(char *dest, const char *src, unsigned long count);

struct task_security_struct *selinux_cred(const struct cred *cred);
struct inode_security_struct *selinux_inode(const struct inode *inode);

#endif
