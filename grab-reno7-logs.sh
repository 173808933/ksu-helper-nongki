#!/bin/bash
# grab-reno7-logs.sh —— 把 Reno7 重启前的现场捞回来（只读，不改设备）
#
#   ./grab-reno7-logs.sh [serial]
#
# 取四类东西，按价值排序：
#   1) /sys/fs/pstore/*  —— panic 时的 console 输出，重启后仍在（需要 root）
#   2) App 私有目录 files/ —— khelper.log、run-*.log、ko，重启不丢，最可能拿得到
#   3) /sdcard/root.log、/sdcard/plog.txt —— p7 自己的日志
#   4) 本次开机的 dmesg（dmesg_restrict=0 或能 root 才有）
set -u
ADB="/c/Other/Andriod/toolchain/android-sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
S="${1:-}"
OUT="/c/Other/Andriod/ksu/logs/reno7-$(date +%m%d-%H%M%S)"
mkdir -p "$OUT"

if [ -z "$S" ]; then
  S=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')
fi
if [ -z "$S" ]; then echo "!! 没有在线设备"; "$ADB" devices; exit 1; fi
echo "设备 $S  ->  $OUT"

s()   { "$ADB" -s "$S" shell "$1" 2>&1 | tr -d '\r'; }
hdr() { printf '\n===== %s =====\n' "$*"; }

hdr "0 基本信息"
s 'uname -a; getprop ro.product.model; getprop ro.build.display.id; getprop ro.boot.bootreason; cat /proc/sys/kernel/random/boot_id; cat /proc/uptime' | tee "$OUT/00-env.txt"

hdr "1 pstore 列表"
s 'ls -l /sys/fs/pstore/ 2>&1' | tee "$OUT/10-pstore-ls.txt"

hdr "2 pstore 内容（shell 权限，可能为空）"
for f in console-ramoops-0 console-ramoops dmesg-ramoops-0; do
  s "cat /sys/fs/pstore/$f 2>/dev/null" > "$OUT/11-$f.txt"
  if [ -s "$OUT/11-$f.txt" ]; then
    echo "  $f  $(wc -c < "$OUT/11-$f.txt") 字节"
  else
    rm -f "$OUT/11-$f.txt"
  fi
done

hdr "3 用 root 再取一次 pstore"
s "su -c 'cat /sys/fs/pstore/console-ramoops-0' 2>/dev/null" > "$OUT/12-root-pstore.txt"
[ -s "$OUT/12-root-pstore.txt" ] || s "/data/local/tmp/rksh -c 'cat /sys/fs/pstore/console-ramoops-0' 2>/dev/null" > "$OUT/12-root-pstore.txt"
if [ -s "$OUT/12-root-pstore.txt" ]; then
  echo "  拿到 $(wc -c < "$OUT/12-root-pstore.txt") 字节"
else
  rm -f "$OUT/12-root-pstore.txt"
  echo "  取不到（没 root，或 ramoops 未挂 console）"
fi

hdr "4 App 私有目录"
PKG=com.xxl.ksuhelper
s "ls -lR /sdcard/Android/data/$PKG/files 2>/dev/null | head -40"
"$ADB" -s "$S" pull "/sdcard/Android/data/$PKG/files" "$OUT/files" 2>&1 | tail -3

hdr "5 p7 / 内核侧日志"
for f in /sdcard/root.log /sdcard/plog.txt; do
  "$ADB" -s "$S" pull "$f" "$OUT/$(basename "$f")" 2>&1 | tail -1
done
s "ls -l /data/local/tmp/p7 /data/local/tmp/rk9.ko /data/local/tmp/kernelsu.ko /data/adb/ksud 2>&1" | tee "$OUT/50-components.txt"
s "cat /proc/modules 2>/dev/null | grep -E '^(kernelsu|rk9)' || echo '无 kernelsu/rk9'" | tee "$OUT/51-modules.txt"

hdr "6 本次开机 dmesg"
s "dmesg 2>/dev/null | tail -200" > "$OUT/60-dmesg.txt"
[ -s "$OUT/60-dmesg.txt" ] || { echo "  无权限（dmesg_restrict=1）"; rm -f "$OUT/60-dmesg.txt"; }

hdr "7 关键行筛选"
for f in "$OUT"/12-root-pstore.txt "$OUT"/11-console-ramoops-0.txt "$OUT"/60-dmesg.txt \
         "$OUT"/files/*.log "$OUT"/root.log "$OUT"/plog.txt; do
  [ -f "$f" ] && [ -s "$f" ] || continue
  hits=$(grep -nE "CFI failure|cfi_check_fail|brk|Unable to handle|Internal error|BUG:|Kernel panic|Call trace|kernelsu:|rk9:|insmod|vermagic|unknown symbol" "$f" 2>/dev/null | tail -30)
  if [ -n "$hits" ]; then
    echo "--- $f"
    echo "$hits"
  fi
done

hdr "完成"
echo "全部文件在: $OUT"
echo "重点看: 12-root-pstore.txt（有就是铁证）、files/ 下的 khelper.log 和 run-*.log"
