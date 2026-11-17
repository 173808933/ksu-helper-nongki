#!/bin/bash
# backup-partitions.sh —— 解锁前备份 Find X3 (PEDM00) 关键分区
#
# 备份集合不含 userdata / super / cache：
#   - userdata 解锁必清，且 242G；
#   - super 11.2G 是系统本体，官方固件可刷回，不属于「不可再生」；
#   - cache 无意义。
# 含 persist / modemst* / fsg 这些丢了不可恢复的校准与基带数据，
# 以及 boot / dtbo / vbmeta* / devinfo 这些恢复系统与回锁状态需要的分区。
set -eu
export MSYS_NO_PATHCONV=1

ADB=C:/Other/Andriod/toolchain/android-sdk/platform-tools/adb.exe
SERIAL=ca8a710a
RKSH=C:/Users/XXL/WorkBuddy/2026-10-15-03-17-27/_deliverables/findx3-4.19/root.sh
OUT=C:/Other/Andriod/ksu/partitions-backup-20261112

PARTS="persist modemst1 modemst2 fsg boot dtbo vbmeta vbmeta_system vbmeta_vendor devinfo metadata misc frp secdata oplus_sec oplusreserve1 recovery"

cd "$(dirname "$RKSH")"
run() { timeout 900 ./root.sh cmd "'$1'"; }

echo "=== [1/4] 设备端 dd ==="
run "rm -rf /data/local/tmp/bak; mkdir -p /data/local/tmp/bak; cd /data/local/tmp/bak; for p in $PARTS; do dd if=/dev/block/by-name/\$p of=\$p.img bs=4M 2>&1 | tail -1; done; echo ---; ls -l"

echo "=== [2/4] 设备端校验 ==="
run "cd /data/local/tmp/bak; sha256sum *.img > SHA256SUMS; cat SHA256SUMS"

echo "=== [3/4] 拉回 PC ==="
mkdir -p "$OUT"
"$ADB" -s "$SERIAL" pull /data/local/tmp/bak/. "$OUT/"
ls -l "$OUT"

echo "=== [4/4] 本地复核 ==="
cd "$OUT" && sha256sum -c SHA256SUMS

echo "[✓] 备份完成: $OUT"
