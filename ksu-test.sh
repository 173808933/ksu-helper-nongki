#!/bin/bash
# ksu-test.sh —— kernelsu.ko 一键测试（自动判断要不要先提权，避免二次提权打崩内核）
#
# 用法：./ksu-test.sh            跑一次（清日志 → 推送 → insmod → 收日志）
#       ./ksu-test.sh status     只看设备/root/模块状态
#
# 判据：
#   /data/local/tmp/ksu_boot.log 最后一行是 "step: sucompat done"  → 通过
#   停在 "step: sucompat begin"                                    → sucompat 仍崩
#   设备掉线后自己重启                                             → panic，看日志最后一行
set -u

ADB=${ADB:-/c/Other/Andriod/toolchain/android-sdk/platform-tools/adb.exe}
SERIAL=${SERIAL:-ca8a710a}
HERE=$(cd "$(dirname "$0")" && pwd)
KO=${KO:-$HERE/build/ksu4_patched.ko}
if command -v cygpath >/dev/null 2>&1; then KO_PUSH=$(cygpath -w "$KO"); else KO_PUSH=$KO; fi
ROOT_SH=${ROOT_SH:-/c/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/root.sh}
LOG=/data/local/tmp/ksu_boot.log
STAMP=/data/local/tmp/ksu_root.stamp	# 记录"本开机已经提过权"，避免重跑漏洞

A() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$ADB" -s "$SERIAL" "$@"; }
online() { "$ADB" devices | grep -q "^$SERIAL"; }

wait_online() {
	local i
	for i in $(seq 1 25); do
		online && return 0
		sleep 5
	done
	return 1
}

boot_id() { A shell 'cat /proc/sys/kernel/random/boot_id' 2>/dev/null | tr -d '\r'; }

have_root() {
	A shell '[ -e /proc/rk9 ] && /data/local/tmp/rksh -c "id -u" 2>/dev/null' 2>/dev/null | grep -q '^0$'
}

status() {
	online || { echo "[-] 设备 $SERIAL 不在线"; return 1; }
	echo "boot_id : $(boot_id)"
	echo "root    : $(have_root && echo 有 || echo 无)"
	echo "模块    : $(A shell 'cat /proc/modules 2>/dev/null' | grep -E 'kernelsu|ksu' || echo 无)"
	echo "日志    : $(A shell "wc -l $LOG 2>/dev/null" || echo 无)"
}

ensure_root() {
	have_root && return 0
	local cur prev
	cur=$(boot_id)
	prev=$(A shell "cat $STAMP 2>/dev/null" 2>/dev/null | tr -d '\r')
	if [ -n "$cur" ] && [ "$cur" = "$prev" ]; then
		echo "[-] 本开机已经跑过一次提权（$cur），再跑会把内核打崩。"
		echo "    请重启手机（长按电源 / adb reboot），然后重跑本脚本。"
		return 1
	fi
	echo "[*] 提权（本开机第一次）"
	"$ROOT_SH" cmd "id -u" 2>&1 | tail -3
	A shell "echo $cur > $STAMP" >/dev/null 2>&1
	have_root
}

run() {
	online || { echo "[-] 设备不在线"; return 1; }
	[ -f "$KO" ] || { echo "[-] 找不到 $KO"; return 1; }

	ensure_root || return 1

	echo "[*] 清日志 / 推送 / 加载"
	A shell "rm -f $LOG" >/dev/null 2>&1
	A push "$KO_PUSH" /data/local/tmp/ksu_test.ko >/dev/null || return 1
	A shell '/data/local/tmp/rksh -c "rmmod kernelsu 2>/dev/null; rmmod ksu2 2>/dev/null; \
	         insmod /data/local/tmp/ksu_test.ko; echo insmod_rc=\$?"' 2>&1

	sleep 4
	if ! online; then
		echo "[!] 设备掉线 —— 内核崩了，等它自动重启…"
		wait_online || { echo "[-] 5 分钟内没回来，需要手动长按电源"; return 2; }
		echo "[*] 已重启，读崩溃前的日志："
	fi

	echo "=== $LOG ==="
	A shell "cat $LOG 2>/dev/null"
	echo "=== 模块 ==="
	A shell 'cat /proc/modules 2>/dev/null' | grep -E 'kernelsu|ksu' || echo "(无)"
}

case "${1:-run}" in
	status) status ;;
	run|*)  run ;;
esac
