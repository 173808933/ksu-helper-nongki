#!/bin/bash
# flash-unlock.sh —— 用临时 root 直接 dd 写入解锁镜像（不走 fastboot）
#
# 分区对照：
#   abl_unlock.img  225,280 B  -> /dev/block/by-name/abl  (1 MB，尾部 823 KB 原厂全零)
#   ocdt_unlock.img 131,072 B  -> /dev/block/by-name/ocdt (128 KB，正好整分区)
# conv=notrunc 保证不会截断块设备，也不会动到 abl 分区尾部。
set -eu
export MSYS_NO_PATHCONV=1

ADB=C:/Other/Andriod/toolchain/android-sdk/platform-tools/adb.exe
SERIAL=ca8a710a
RKSH=C:/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/root.sh
SRC="C:/Other/Andriod/解锁文件/OPPOFindX3，OPPOReno6Pro+，OPPOPad（870）"
STAGE=C:/Other/Andriod/ksu/unlock-stage

ABL_SHA=65e53ab89265132f9a4071ae4239f513d2acc3630d0f73d6f458d4abbeac3cb6
OCDT_SHA=25fd633d363418ca0b00a8390cbb8c2856f96bb54cec92c6faba7c2c92a037c6

mkdir -p "$STAGE"
cp -f "$SRC/abl_unlock.img" "$SRC/ocdt_unlock.img" "$STAGE/"

echo "=== [0/5] 本地镜像校验 ==="
cd "$STAGE"
echo "$ABL_SHA  abl_unlock.img"  | sha256sum -c -
echo "$OCDT_SHA  ocdt_unlock.img" | sha256sum -c -

echo "=== [1/5] 确认 root 通道 ==="
"$ADB" -s "$SERIAL" shell 'ls /proc/rk9 >/dev/null 2>&1 && echo RK9_OK || echo RK9_MISSING'

echo "=== [2/5] 推送镜像 ==="
"$ADB" -s "$SERIAL" push "$STAGE/abl_unlock.img"  /data/local/tmp/ >/dev/null
"$ADB" -s "$SERIAL" push "$STAGE/ocdt_unlock.img" /data/local/tmp/ >/dev/null
echo "pushed"

cd "$(dirname "$RKSH")"
run() { timeout 300 ./root.sh cmd "'$1'"; }

echo "=== [3/5] 写前原厂哈希（对照用） ==="
run "sha256sum /dev/block/by-name/abl /dev/block/by-name/ocdt"

echo "=== [4/5] 写入 abl + ocdt ==="
run "dd if=/data/local/tmp/abl_unlock.img  of=/dev/block/by-name/abl  bs=4M conv=notrunc 2>&1 | tail -1; sync"
run "dd if=/data/local/tmp/ocdt_unlock.img of=/dev/block/by-name/ocdt bs=4M conv=notrunc 2>&1 | tail -1; sync"

echo "=== [5/5] 回读校验 ==="
echo "abl  期望 $ABL_SHA"
run "dd if=/dev/block/by-name/abl bs=225280 count=1 2>/dev/null | sha256sum"
echo "ocdt 期望 $OCDT_SHA"
run "sha256sum /dev/block/by-name/ocdt"

echo "[✓] 写入完成，重启后新 abl 生效"
