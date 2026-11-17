#!/bin/bash
# deploy-ksud.sh —— 把新编的 ksud 推上设备并原子替换
#
# 安全要点：
#   1. 旧二进制先备份到 /data/adb/ksud.bak-<stamp>，回滚一条 mv 命令；
#   2. 新二进制先以 .new 落盘并跑 --version 自检，通过后才替换；
#   3. 替换用 mv（同目录 rename，原子），不会出现半截二进制；
#   4. su / resetprop 都是 /data/adb/ksud 的符号链接，替换即同时生效。
set -eu
export MSYS_NO_PATHCONV=1

ADB=C:/Other/Andriod/toolchain/android-sdk/platform-tools/adb.exe
SERIAL=ca8a710a
NEW=C:/Other/Andriod/ksu/build-target/aarch64-linux-android/release/ksud
STAMP=$(date +%Y%m%d-%H%M)
RKSH=C:/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/root.sh

[ -f "$NEW" ] || { echo "缺少构建产物: $NEW"; exit 1; }
echo "[i] 本地二进制: $(ls -l "$NEW" | awk '{print $5}') 字节"

"$ADB" -s "$SERIAL" push "$NEW" /data/local/tmp/ksud.new >/dev/null
echo "[i] 已推送 /data/local/tmp/ksud.new"

cd "$(dirname "$RKSH")"
run() { timeout 200 ./root.sh cmd "'$1'"; }

run "cp -f /data/adb/ksud /data/adb/ksud.bak-$STAMP; ls -l /data/adb/ksud.bak-$STAMP"
run "cp -f /data/local/tmp/ksud.new /data/adb/ksud.new; chmod 755 /data/adb/ksud.new; /data/adb/ksud.new --version; echo SELFCHECK_RC=\$?"
run "mv -f /data/adb/ksud.new /data/adb/ksud; chmod 755 /data/adb/ksud; ls -l /data/adb/ksud; /data/adb/ksud --version"

echo "[✓] 替换完成。回滚：mv -f /data/adb/ksud.bak-$STAMP /data/adb/ksud"
