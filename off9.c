#include <linux/sched.h>
#include <linux/cred.h>
#include <linux/fs.h>
#include <linux/seccomp.h>
#include <linux/nsproxy.h>
char b_cred[offsetof(struct task_struct, cred)];
char b_real[offsetof(struct task_struct, real_cred)];
char b_comm[offsetof(struct task_struct, comm)];
char b_seccomp[offsetof(struct task_struct, seccomp)];
char b_nsproxy[offsetof(struct task_struct, nsproxy)];
char b_fs[offsetof(struct task_struct, fs)];
char b_size[sizeof(struct task_struct)];
