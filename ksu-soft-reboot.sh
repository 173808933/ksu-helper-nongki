#!/system/bin/sh
# KernelSU 软重启（ColorOS 适配版）
#
# 为什么不能用 KSU 自带的：它执行 `stop`/`start` 让属性服务去停/起所有服务，
# 而 ColorOS 的属性服务只允许 u:r:init:s0 写 ctl.stop/ctl.start；
# 内核虽然是 permissive，但 init 的 userspace AVC 在开机时缓存了 enforcing，
# 运行时 setenforce 0 不会刷新它 —— 所以 su / shell / untrusted_app 域一律被拒。
# KSU 原本靠 sepolicy 注入给 su 域补这条权限，本移植把注入层置空了，因此失败。
#
# 这里改成显式以 init 域执行 stop/start，用的都是系统已有策略，无需注入。
set -u
LOG=/data/local/tmp/ksu_soft_reboot.log
exec >>"$LOG" 2>&1
echo "=== soft reboot $(date) ==="
runcon u:r:init:s0 /system/bin/setprop sys.boot_completed 0
sleep 1
echo "stop"
runcon u:r:init:s0 /system/bin/stop
sleep 3
echo "start"
runcon u:r:init:s0 /system/bin/start
sleep 5
runcon u:r:init:s0 /system/bin/setprop sys.boot_completed 1
echo "done"

# 用法（设备上，需 root）：
#   nohup setsid /data/local/tmp/ksu_soft_reboot.sh >/dev/null 2>&1 </dev/null &
# 本机（adb）：
#   adb push ksu-soft-reboot.sh /data/local/tmp/ && adb shell 'su -c "chmod 755 /data/local/tmp/ksu_soft_reboot.sh; nohup setsid /data/local/tmp/ksu_soft_reboot.sh >/dev/null 2>&1 </dev/null &"'
