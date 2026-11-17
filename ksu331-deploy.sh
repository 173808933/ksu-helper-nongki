#!/bin/bash
# ksu331-deploy.sh —— 在 4.19.157 (ColorOS 14 / Find X3) 上部署 KernelSU 3.3.1 移植版
#
# 前提：设备已用 root.sh 拿到临时 root（每次开机只能跑一次）
# 顺序不能变：permissive → insmod。enforcing 下 insmod 会直接 Oops。
#
# 用法：
#   ./ksu331-deploy.sh          # 部署 + 自检
#   ./ksu331-deploy.sh status   # 只看状态

set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ADB=${ADB:-/c/Other/Andriod/toolchain/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-ca8a710a}
KO=${KO:-$HERE/build/ksu331_419_patched.ko}
GUARD=${GUARD:-/c/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/kmod/ksu_guard.ko}
# push 需要 Windows 路径：脚本里带 MSYS_NO_PATHCONV=1，POSIX 路径 adb 不认
if command -v cygpath >/dev/null 2>&1; then
	KO_PUSH=$(cygpath -w "$KO")
	GUARD_PUSH=$(cygpath -w "$GUARD")
else
	KO_PUSH=$KO; GUARD_PUSH=$GUARD
fi

A() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$ADB" -s "$SERIAL" "$@"; }
R() { A shell "/data/local/tmp/rksh -c \"$1\""; }

status() {
	echo "== 设备 =="
	A shell 'getprop sys.boot_completed; getenforce'
	echo "== 模块 =="
	R 'cat /proc/modules | grep kernelsu || echo "(未加载)"'
	R 'cat /sys/module/kernelsu/initstate 2>/dev/null'
	echo "== 管理器 =="
	A shell 'dumpsys package me.weishu.kernelsu | grep -m1 versionName'
	echo "== 认领记录 =="
	R 'dmesg | grep -e "Crowning" -e "is_manager" | tail -3'
}

deploy() {
	echo "[1/5] 检查临时 root"
	if ! A shell 'ls /proc/rk9 >/dev/null 2>&1 && echo ok' | grep -q ok; then
		echo "  ✗ 没有 root 通道，先跑 root.sh（每次开机只能一次）"; exit 1
	fi
	echo "  ✓ root 通道在"

	echo "[2/5] SELinux 转宽容"
	R 'setenforce 0'
	R 'getenforce'

	echo "[3/5] 推送并加载反检测守卫"
	if [ -f "$GUARD" ]; then
		if R 'grep -q ksu_guard /proc/modules && echo yes' | grep -q yes; then
			echo "  守卫已加载，跳过"
		else
			A push "$GUARD_PUSH" /data/local/tmp/ksu_guard.ko >/dev/null 2>&1 && \
				R 'insmod /data/local/tmp/ksu_guard.ko; echo guard=$?'
		fi
		echo "  （不装守卫的话，OPPO 会在 su 提权瞬间 kill 掉 ksud）"
	else
		echo "  ! 找不到 $GUARD，跳过"
	fi

	echo "[4/5] 推送并加载 KSU 模块"
	if R 'grep -q kernelsu /proc/modules && echo yes' | grep -q yes; then
		echo "  模块已加载，跳过 insmod（改模块后需重启）"
	else
		A push "$KO_PUSH" /data/local/tmp/ksu331.ko >/dev/null 2>&1 || { echo "  ✗ push 失败"; exit 1; }
		R 'rm -f /data/local/tmp/ksu331.log'
		R 'insmod /data/local/tmp/ksu331.ko; echo ins=$?'
		sleep 2
	fi
	R 'grep kernelsu /proc/modules || echo not-loaded'

	echo "[5/5] 自检"
	R 'dmesg | grep -e is_manager -e Crowning | tail -2'

	echo "-- 槽位写入结论 --"
	V=$(R 'grep -e dispatcher -e DISPATCHER -e alias -e fixmap /data/local/tmp/ksu331.log 2>/dev/null | tail -4')
	echo "$V"
	if echo "$V" | grep -q "dispatcher patch verified"; then
		echo "  ✓ sys_call_table 槽位已写入（fixmap 直写成功）"
	elif echo "$V" | grep -q "alias retry ok"; then
		echo "  ✓ fixmap 直写失败，但线性映射别名写入成功（可用）"
	elif echo "$V" | grep -q "DISPATCHER PATCH FAILED"; then
		echo "  ✗ 两种写法都没落地 → 已自动关闭重定向（系统仍可用，需换写入方式）"
	else
		echo "  ? 日志里没有结论行，看下面原始打点"
		R 'tail -12 /data/local/tmp/ksu331.log'
	fi

	echo "-- exec 是否被拦 --"
	if A shell 'echo alive' 2>/dev/null | grep -q alive; then
		echo "  ✓ exec 正常"
	else
		echo "  ✗ exec 已被拦（需长按电源键重启）"
	fi
}

case "${1:-deploy}" in
	status) status ;;
	*) deploy ;;
esac
