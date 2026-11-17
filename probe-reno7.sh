#!/bin/bash
# probe-reno7.sh —— Reno7(PFJM10) 实机探测，全程只读，不改设备任何状态
#
#   ./probe-reno7.sh <serial>
#
# 输出 6 组信息，用来决定走哪条路（有 root 直接 insmod / 无 root 跑 p7）。
set -u
S="${1:?用法: $0 <serial>}"
ADB="/c/Other/Andriod/toolchain/android-sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1

say() { printf '\n===== %s =====\n' "$*"; }
run() { timeout 25 "$ADB" -s "$S" shell "$1" 2>&1 | tr -d '\r'; }

say "0. 连接"
"$ADB" devices | grep -q "$S" || { echo "设备 $S 不在线"; exit 1; }
echo "OK: $S"

say "1. 机型 / 系统 / 内核"
run 'getprop ro.product.model; getprop ro.product.device; getprop ro.build.version.release;
     getprop ro.build.display.id; uname -a; cat /proc/version'

say "2. vermagic 是否与我们编的 ko 一致"
echo "目标: 5.4.254-qgki-gaa96eae94d32 SMP preempt mod_unload modversions aarch64"
run 'uname -r'
echo "--- 内核里的 vermagic 字符串 ---"
run 'strings /proc/version 2>/dev/null | head -1'

say "3. 安全配置（决定 p7 能不能自己推地址）"
run 'echo -n "kptr_restrict: "; cat /proc/sys/kernel/kptr_restrict 2>/dev/null || echo 读不到;
     echo -n "dmesg_restrict: "; cat /proc/sys/kernel/dmesg_restrict 2>/dev/null || echo 读不到;
     echo -n "selinux: "; getenforce;
     echo -n "verifiedboot: "; getprop ro.boot.verifiedbootstate;
     echo -n "verity: "; getprop ro.boot.veritymode'

say "4. 现有 root 状态（有就不用跑 p7）"
run 'which su; ls -l /system/bin/su /system/xbin/su 2>/dev/null;
     ls -d /data/adb/ksu /data/adb/magisk 2>/dev/null;
     cat /proc/modules 2>/dev/null | grep -E "^(kernelsu|rk9)" || echo "无 kernelsu/rk9 模块";
     su -c "id" 2>&1 | head -2'

say "5. config 关键项（能否与我们的构建假设对上）"
run 'if [ -r /proc/config.gz ]; then zcat /proc/config.gz | grep -E "^CONFIG_(MODULE_SIG|MODVERSIONS|CFI_CLANG|LTO_CLANG|KALLSYMS_ALL|KPROBES)="; else echo "/proc/config.gz 不存在（需要 root 或未开启）"; fi'

say "6. p7 的竞态前置条件（负载/uptime）"
run 'cat /proc/uptime; cat /proc/loadavg; grep -E "MemTotal|MemFree" /proc/meminfo'

say "结论提示"
cat <<'EOF'
  vermagic 与目标不一致  → 我们编的 ko 全部不能用，先确认 ROM 版本
  su -c id 返回 uid=0   → 走 B 路线：直接 insmod rk9 + kernelsu，不跑 p7
  没有 root             → 走 A 路线：p7 提权（一次开机只有一发，失败要重启）
  kptr_restrict=2       → p7 必须用显式 GL_*（APK v3.0 已默认这么做）
EOF
