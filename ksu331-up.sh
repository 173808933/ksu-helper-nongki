#!/bin/bash
# ksu331-up.sh —— 每次开机后一条命令：提权 + 部署 KSU 3.3.1
#
# 依赖：
#   ../2026-10-15-03-17-27/_deliverables/findx3-4.19/root.sh   临时 root（每次开机只能跑一次）
#   ./ksu331-deploy.sh                                          守卫 + 模块 + permissive
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOTSH=${ROOTSH:-/c/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/root.sh}

if "$HERE/ksu331-deploy.sh" status 2>/dev/null | grep -q "rk9\|kernelsu"; then
	:
fi

if ! /c/Other/Andriod/toolchain/android-sdk/platform-tools/adb.exe -s ca8a710a shell 'ls /proc/rk9 >/dev/null 2>&1' 2>/dev/null; then
	echo "[1/2] 没有 root 通道，跑 root.sh（本次开机唯一一次）"
	(cd "$(dirname "$ROOTSH")" && timeout 280 ./root.sh cmd "id -u" 2>&1 | tail -2)
fi

echo "[2/2] 部署 KSU"
"$HERE/ksu331-deploy.sh"
