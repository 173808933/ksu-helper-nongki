#include <linux/sched.h>
#include <linux/cred.h>
#include <linux/fs.h>
char a_cred[offsetof(struct task_struct, cred)];
char a_real[offsetof(struct task_struct, real_cred)];
char a_comm[offsetof(struct task_struct, comm)];
char a_pid[offsetof(struct task_struct, pid)];
char a_nsproxy[offsetof(struct task_struct, nsproxy)];
char a_fs[offsetof(struct task_struct, fs)];
