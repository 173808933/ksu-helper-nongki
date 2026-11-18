#!/system/bin/sh
# ---------------------------------------------------------------------------
# A93 / 5.4.254-qgki 只读探测
#
# 全部是读操作，不写内核内存、不调 exploit，不消耗 init_cred 预算。
# 目的是在真机跑 gl54 之前，把能从用户态看到的证据一次采全。
#
# 用法： sh probe.sh
# ---------------------------------------------------------------------------

echo "================ A93 PROBE ================"
echo "time     : $(date)"
echo "uname    : $(uname -a)"
echo "id       : $(id)"
echo "getenforce: $(getenforce 2>/dev/null)"
echo "cmdline  : $(cat /proc/cmdline 2>/dev/null | cut -c1-200)"
echo "boot_id  : $(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
echo "uptime   : $(cat /proc/uptime 2>/dev/null)"

echo
echo "--- sysctl（决定用户态能做什么）---"
for f in \
    kernel/perf_event_paranoid \
    kernel/kptr_restrict \
    kernel/dmesg_restrict \
    vm/mmap_min_addr \
    kernel/unprivileged_bpf_disabled \
    kernel/unprivileged_userns_clone
do
    printf '  %-32s %s\n' "$f" "$(cat /proc/sys/$f 2>/dev/null || echo '(读不到)')"
done

echo
echo "--- selinux 节点（gl54 第一阶段要写的目标）---"
ls -l /sys/fs/selinux/enforce /sys/fs/selinux/checkreqprot 2>&1 | sed 's/^/  /'
echo "  enforce 内容: $(cat /sys/fs/selinux/enforce 2>/dev/null || echo '读不到')"

echo
echo "--- /proc/config.gz（能读到就能确认 MODULE_SIG_FORCE 等真实配置）---"
if [ -r /proc/config.gz ]; then
    echo "  可读，关键项："
    zcat /proc/config.gz 2>/dev/null | grep -E \
        'CONFIG_(MODULE_SIG|MODULE_SIG_FORCE|KALLSYMS|STACKPROTECTOR|ARM64_PTR_AUTH|CFI_CLANG|RANDOMIZE_BASE|PERF_EVENTS|SECURITY_SELINUX)=' \
        | sed 's/^/    /'
else
    ls -l /proc/config.gz 2>&1 | sed 's/^/  /'
fi

echo
echo "--- kallsyms 自检（地址全 0 说明被 kptr_restrict 挡住）---"
echo -n "  符号总数: "; wc -l < /proc/kallsyms 2>/dev/null || echo '?'
TEXT=""
for s in _text _stext init_task init_cred selinux_state __log_buf proc_pid_status futex_unlock_pi is_module_sig_enforced sysctl_perf_event_paranoid proc_pid_stat futex_wait_requeue_pi rt_mutex_start_proxy_lock; do
    a=$(grep -E " (t|T|d|D|b|B|r|R) ${s}$" /proc/kallsyms 2>/dev/null | head -1 | cut -d' ' -f1)
    if [ -n "$a" ]; then
        printf '  %-28s 0x%s\n' "$s" "$a"
        [ "$s" = "_text" ] && TEXT="$a"
    else
        printf '  %-28s (无此符号)\n' "$s"
    fi
done

# 若 kallsyms 给了真实地址，直接算出相对偏移，和档案里标定的值对照
if [ -n "$TEXT" ] && [ "$TEXT" != "0000000000000000" ]; then
    echo
    echo "--- 相对 _text 的偏移（可直接和 profiles.json 对照）---"
    TB=$((0x$TEXT))
    for s in init_task init_cred selinux_state __log_buf proc_pid_status futex_unlock_pi is_module_sig_enforced sysctl_perf_event_paranoid; do
        a=$(grep -E " (t|T|d|D|b|B|r|R) ${s}$" /proc/kallsyms 2>/dev/null | head -1 | cut -d' ' -f1)
        [ -z "$a" ] && continue
        off=$((0x$a - TB))
        printf '  %-28s 0x%x\n' "$s" "$off"
    done
    echo
    echo "  期望值（A93 g9ef4 标定）："
    echo "    init_task        0x27a5980"
    echo "    init_cred        0x27b2a88"
    echo "    selinux_state    0x29c0000"
    echo "    __log_buf        0x29d4784"
    echo "    proc_pid_status  0x52ba84   (+0x794 处任务指针在 x20)"
    echo "    futex_unlock_pi  0x31eed4   (+0x618 处 waiter=x29-40)"
else
    echo
    echo "  → 地址被屏蔽（kptr_restrict 或非 root），无法直接核对偏移，"
    echo "    需依赖 gl54 运行期的 perf 侧信道。"
fi

echo
echo "--- 已加载模块（看有没有 rk9/kernelsu 残留，或厂商反作弊）---"
cat /proc/modules 2>/dev/null | sed 's/^/  /' | head -30

echo
echo "--- 栈保护 / PAC 相关（影响模块能否加载）---"
if [ -r /proc/kallsyms ]; then
    grep -cE " (__stack_chk_guard|__arm64_ssbd_start_mitigation)$" /proc/kallsyms 2>/dev/null \
        | sed 's/^/  stack_chk 相关符号数: /'
fi

echo
echo "--- 内存 ---"
grep -E 'MemTotal|MemFree|MemAvailable' /proc/meminfo 2>/dev/null | sed 's/^/  /'

echo
echo "--- /proc/self/status 前 12 行（gl54 采样这个函数取 task）---"
head -12 /proc/self/status 2>/dev/null | sed 's/^/  /'

echo
echo "================ PROBE DONE ================"
