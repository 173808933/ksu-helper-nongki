#include <linux/sched.h>
#include <linux/cred.h>
#include <linux/fs.h>
#include <linux/seccomp.h>
#include <linux/nsproxy.h>
char b_seccomp[offsetof(struct task_struct, seccomp)];
char b_mm[offsetof(struct task_struct, mm)];
char b_nsproxy[offsetof(struct task_struct, nsproxy)];
char b_fs[offsetof(struct task_struct, fs)];
char b_flags[offsetof(struct task_struct, flags)];
char b_pid[offsetof(struct task_struct, pid)];
char b_tgid[offsetof(struct task_struct, tgid)];
char b_files[offsetof(struct task_struct, files)];
char b_signal[offsetof(struct task_struct, signal)];
