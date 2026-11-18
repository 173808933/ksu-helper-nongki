#!/system/bin/sh
# 测试：卸掉 rk9 再装 kernelsu，看是否抢 syscall 表
# 日志写 /sdcard（uid 2000 可读），这样即使 root 通道断了也能看结果
L=/sdcard/try.log
K=/data/local/tmp/d.ko

: > "$L"
echo "== start ==" >> "$L"

echo "--- before ---" >> "$L"
grep -E '^(kernelsu|rk9) ' /proc/modules >> "$L" 2>&1

rmmod kernelsu >> "$L" 2>&1
echo "rmmod kernelsu rc=$?" >> "$L"

rmmod rk9 >> "$L" 2>&1
echo "rmmod rk9 rc=$?" >> "$L"
sleep 2

echo "--- after rmmod ---" >> "$L"
grep -E '^(kernelsu|rk9) ' /proc/modules >> "$L" 2>&1
echo "rk9_count=$(grep -c '^rk9 ' /proc/modules)" >> "$L"

KLN=$(grep " kallsyms_lookup_name$" /proc/kallsyms | cut -d" " -f1)
echo "kln=$KLN" >> "$L"

insmod "$K" kln=0x$KLN level=3 >> "$L" 2>&1
echo "insmod rc=$?" >> "$L"
sleep 1
grep '^kernelsu ' /proc/modules >> "$L" 2>&1

echo "--- ksud ---" >> "$L"
/data/adb/ksud feature check adb_root >> "$L" 2>&1
echo "--- end ---" >> "$L"
