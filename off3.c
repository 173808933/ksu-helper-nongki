#include <linux/sched.h>
#include <linux/cred.h>
#include <linux/fs.h>
#include <linux/seccomp.h>
#include <linux/nsproxy.h>
char c_cred[offsetof(struct task_struct, cred)];
char c_real[offsetof(struct task_struct, real_cred)];
char c_comm[offsetof(struct task_struct, comm)];
char c_seccomp[offsetof(struct task_struct, seccomp)];
char c_nsproxy[offsetof(struct task_struct, nsproxy)];
char c_fs[offsetof(struct task_struct, fs)];
char c_mm[offsetof(struct task_struct, mm)];
char c_pid[offsetof(struct task_struct, pid)];
