#!/bin/bash
# ksu-deploy.sh —— 在 Find X3（PEDM00 / 4.19.157）上把 KernelSU 以 .ko 形式跑起来
#
# 前置：设备已用 root.sh 拿到临时 root（/proc/rk9 存在）
#
# 组成：
#   ksu_guard.ko   反 OPPO 检测（拦 oplus_root_reboot / oplus_root_check_succ）
#   kernelsu.ko    KernelSU 0.9.5 移植版（SU_PATH 已改成 /data/adb/ksu/bin/su）
#   管理器 APK     me.weishu.kernelsu v0.9.5（官方 release）
#
# 用法：
#   ./ksu-deploy.sh            部署 + 自检（幂等）
#   ./ksu-deploy.sh status     看状态
#
# 三个硬约束（踩过）：
#   1. 守卫必须先于 KSU 加载 —— KSU 一提权，OPPO 的 kevent 上报立刻触发
#   2. 管理器要被内核"认领"，必须发生一次 packages.list 重写（脚本用 pm install 触发）
#   3. 内核模块不能 rmmod（LSM hook 已挂进 security_hook_heads，卸载必崩），改代码只能重启
set -u

ADB=${ADB:-/c/Other/Andriod/toolchain/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-ca8a710a}
KSUDIR=${KSUDIR:-/c/Other/Andriod/ksu}
GUARD=${GUARD:-/c/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/kmod/ksu_guard.ko}
KSU=${KSU:-$KSUDIR/build/kernelsu_patched.ko}
APK=${APK:-$KSUDIR/KernelSU_v0.9.5.apk}

cyg() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

A() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$ADB" -s "$SERIAL" "$@"; }

# 远端命令走临时脚本，彻底躲开 bash→adb→rksh→sh 四层引号
R() {
	local f
	f=$(mktemp)
	printf '%s\n' "$1" > "$f"
	A push "$(cyg "$f")" /data/local/tmp/_r.sh >/dev/null 2>&1
	A shell '/data/local/tmp/rksh -c "sh /data/local/tmp/_r.sh"'
}

need() {
	"$ADB" devices | grep -q "^$SERIAL" || { echo "[-] 设备不在线"; exit 1; }
	R 'test -e /proc/rk9 && echo ok' 2>/dev/null | grep -q ok || { echo "[-] 没有 root，先跑 ./root.sh"; exit 1; }
}

deploy() {
	need
	echo "[1/5] 推送模块"
	A push "$(cyg "$GUARD")" /data/local/tmp/ksu_guard.ko >/dev/null || exit 1
	A push "$(cyg "$KSU")" /data/local/tmp/kernelsu.ko >/dev/null || exit 1

	echo "[2/5] 先装守卫"
	R 'insmod /data/local/tmp/ksu_guard.ko 2>/dev/null
grep -c "^ksu_guard " /proc/modules'

	echo "[3/5] 再装 KernelSU"
	R 'if grep -q "^kernelsu " /proc/modules; then
	echo "already loaded"
else
	insmod /data/local/tmp/kernelsu.ko
	echo "insmod rc=$?"
fi'

	echo "[4/5] 用户态目录与权限 + SELinux"
	R 'setenforce 0 2>/dev/null
echo "enforce=$(getenforce)"' 
	R 'chmod 711 /data/adb /data/adb/ksu /data/adb/ksu/bin 2>/dev/null
chmod 755 /data/adb/ksud 2>/dev/null
ln -sf /data/adb/ksud /data/adb/ksu/bin/su
ls -ld /data/adb/ksu/bin'

	echo "[5/5] 认领管理器"
	A push "$(cyg "$APK")" /data/local/tmp/ksu.apk >/dev/null 2>&1
	R 'pm install -r -d /data/local/tmp/ksu.apk 2>&1 | tail -1'
	sleep 2
	R 'echo -n "crowning="; dmesg | grep -c "Crowning manager"'

	echo
	echo "[*] 自检"
	A shell 'am force-stop me.weishu.kernelsu; am start -n me.weishu.kernelsu/.ui.MainActivity' >/dev/null 2>&1
	sleep 4
	R 'echo -n "guard_intercepts="; dmesg | grep -c "ksu_guard:"'
	echo "    管理器若显示「工作中 / 版本 12000」即成功"
}

status() {
	need
	echo "boot_id : $(A shell 'cat /proc/sys/kernel/random/boot_id' | tr -d '\r')"
	echo "模块    : $(R 'cat /proc/modules' | grep -E '^(ksu_guard|kernelsu) ' | awk '{print $1}' | tr '\n' ' ')"
	echo "ksud    : $(R 'ls -la /data/adb/ksud 2>&1' | tail -1)"
	echo "su 软链 : $(R 'ls -la /data/adb/ksu/bin/su 2>&1' | tail -1)"
	echo "管理器  : $(A shell 'pm list packages' 2>/dev/null | grep -c me.weishu.kernelsu) 个"
	echo "守卫    : $(R 'dmesg | grep -c "ksu_guard:"') 次拦截"
	echo "认领    : $(R 'dmesg | grep -c "Crowning manager"') 次"
}

case "${1:-deploy}" in
	status) status ;;
	deploy|*) deploy ;;
esac
