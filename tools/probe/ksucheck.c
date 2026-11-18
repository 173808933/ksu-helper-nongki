/*
 * ksucheck — 直接问内核：KernelSU 到底活没活、我这个 uid 有没有 root 权
 *
 * 原理：KSU 的 supercall 挂在 reboot 的 kprobe 上。调 reboot 时带两个 magic，
 *       内核 hook 认出后把匿名 inode 的 fd 写回第 4 个参数，并取消这次重启。
 *       拿到 fd 后就能 ioctl 查询/提权。
 *
 *   syscall(__NR_reboot, 0xDEADBEEF, 0xCAFEBABE, LINUX_REBOOT_CMD_RESTART, &fd)
 *
 * 三步验证：
 *   1) 能不能拿到 fd      → 模块的 reboot hook 是否真的注册成功
 *   2) GET_INFO           → 模块版本 / UAPI / flags（LKM? MANAGER? LATE_LOAD?）
 *   3) GRANT_ROOT         → 当前 uid 在不在 allowlist 里、能不能真提权
 *
 * 编译： aarch64-linux-gnu-gcc -static -O2 ksucheck.c -o ksucheck
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <linux/reboot.h>

#define KSU_INSTALL_MAGIC1 0xDEADBEEF
#define KSU_INSTALL_MAGIC2 0xCAFEBABE

struct ksu_get_info_cmd {
    uint32_t version;
    uint32_t flags;
    uint32_t features;
    uint32_t uapi_version;
};

/* 按 uapi/supercall.h 的定义手工构造 ioctl 号 */
#define KSU_IOCTL_GET_INFO   _IOC(_IOC_READ, 'K', 2, sizeof(struct ksu_get_info_cmd))
#define KSU_IOCTL_GRANT_ROOT _IOC(_IOC_NONE, 'K', 1, 0)

int main(void) {
    printf("========== ksucheck ==========\n");
    printf("uid=%d euid=%d\n", getuid(), geteuid());

    /* --- 1) 拿 fd --- */
    int fd = -1;
    errno = 0;
    long r = syscall(__NR_reboot, KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2,
                     LINUX_REBOOT_CMD_RESTART, &fd);
    if (r < 0) {
        printf("[1] 取 fd 失败: ret=%ld errno=%d (%s)\n", r, errno, strerror(errno));
        printf("    → 模块的 reboot kprobe 没接管。可能原因：\n");
        printf("      · 模块没加载 / 加载到一半失败\n");
        printf("      · 模块编译时用的符号地址不对（REBOOT_SYMBOL 解析失败）\n");
        printf("      · 这台内核的 reboot 走的是别的路径，kprobe 没挂上\n");
        return 1;
    }
    printf("[1] fd = %d  ✓ reboot hook 生效\n", fd);
    if (fd < 0) {
        printf("    ⚠ arg4 没被回填（fd=%d），说明 hook 进了但 task_work 没跑完\n", fd);
        return 1;
    }

    /* --- 2) GET_INFO --- */
    struct ksu_get_info_cmd info;
    memset(&info, 0, sizeof(info));
    errno = 0;
    if (ioctl(fd, KSU_IOCTL_GET_INFO, &info) < 0) {
        printf("[2] GET_INFO 失败: errno=%d (%s)\n", errno, strerror(errno));
        printf("    → fd 拿到了但 ioctl 不通，可能 ioctl 号与模块版本对不上\n");
    } else {
        printf("[2] GET_INFO ✓\n");
        printf("    version      = %u (0x%x)\n", info.version, info.version);
        printf("    uapi_version = %u\n", info.uapi_version);
        printf("    features     = %u\n", info.features);
        printf("    flags        = 0x%x  [%s%s%s%s%s]\n", info.flags,
               (info.flags & (1u << 0)) ? "LKM " : "",
               (info.flags & (1u << 1)) ? "MANAGER " : "",
               (info.flags & (1u << 2)) ? "LATE_LOAD " : "",
               (info.flags & (1u << 3)) ? "PR_BUILD " : "",
               (info.flags & (1u << 4)) ? "BUNDLED " : "");
        if (info.version != 32715)
            printf("    ⚠ 版本不是 32715（我们编的是 32715），管理器可能仍报「需要更新内核」\n");
        if (info.uapi_version != 6)
            printf("    ⚠ UAPI 不是 6（uapi/supercall.h 里写的是 6）\n");
    }

    /* --- 3) GRANT_ROOT --- */
    errno = 0;
    long g = ioctl(fd, KSU_IOCTL_GRANT_ROOT, 0);
    if (g < 0) {
        printf("[3] GRANT_ROOT 失败: ret=%ld errno=%d (%s)\n", g, errno, strerror(errno));
        printf("    → 当前 uid 不在 allowlist，或 allowlist 没被模块读到\n");
        printf("      （模块只在 init 阶段读一次 /data/adb/ksu/.allowlist，\n");
        printf("        所以必须先把 allowlist 写好、再 rmmod+insmod）\n");
    } else {
        printf("[3] GRANT_ROOT ✓ ret=%ld  —— 当前 uid 已获 root 权限\n", g);
    }

    printf("========== 结论 ==========\n");
    if (g >= 0)
        printf("KSU 内核侧就绪，allowlist 生效。管理器应该能识别（若仍报不支持，\n"
               "那是 Manager 内的 GKI 判断，与内核侧无关）。\n");
    else
        printf("KSU 内核侧未就绪，先解决上面的问题再谈管理器。\n");

    return 0;
}
