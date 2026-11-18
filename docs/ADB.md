# ADB 命令行接口

用有序广播把结果**同步**回给 adb —— `am broadcast` 会打印
`Broadcast completed: result=0, data="..."`，一条命令一个返回值，不用再 `cat` 文件。

## 短命令（广播，10s 内返回）

```bash
A=com.xxl.ksuhelper.CMD

# 一览：设备 / 内核 / Shizuku / 模块 / rksh / .glhold / ko 候选
adb shell am broadcast -a $A --es cmd status

# 包名与 uid（写 allowlist 时要用）
adb shell am broadcast -a $A --es cmd appid

# 执行命令：rksh 存在时自动用 root
adb shell am broadcast -a $A --es cmd shell --es arg "id"
adb shell am broadcast -a $A --es cmd shell --es arg "ls -l /data/adb/ksu"

# 列出 /sdcard/Download/*.ko 及各自的 vermagic
adb shell am broadcast -a $A --es cmd kos

# 清场：删 .glhold + ko 缓存（测试前用）
adb shell am broadcast -a $A --es cmd clean

# 内置说明
adb shell am broadcast -a $A --es cmd doc
```

返回示例：

```
Broadcast completed: result=0, data="pkg=com.xxl.ksuhelper uid=10234
device=OPD2101 model=OPPO Pad
kernel=4.19.157-perf+
boot_id=c5710c2e-a812-42ed-8b71-64556bc73012
shizuku=binder-ok perm=granted
kptr=2
module=kernelsu(no)
rksh=yes
glhold=c5710c2e-...
ko:
  kernelsu.ko  4195232 B  vermagic=4.19.157-perf+"
```

多行内容里的换行会以 `\n` 转义，脚本里用 `sed 's/\\n/\n/g'` 还原即可。

## 长命令（Activity，会拉起界面）

提权和探测动辄 40~75s，超出广播的 10s 限制，走 Activity：

```bash
# 一键提权
adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run root

# 推送 + 探测（不跑漏洞）
adb shell am start -n com.xxl.ksuhelper/.MainActivity --es run probe

# 指定档案 / 模块路径 / 单条 root 命令
adb shell am start -n com.xxl.ksuhelper/.MainActivity \
  --es run root --es profile oppo_pad_opd2101 \
  --es ko "/sdcard/Download/kernelsu.ko"
```

结果看界面日志，或用 `verifyKsu`/日志导出。

## 注意

- **`appid` 拿到的 uid 才是 allowlist 该写的那个**。Shizuku 通道是 uid 2000，
  写进 allowlist 会被 KSU 的 `track_throne` prune 掉。
- `shell` 在没有 rksh 的机型（5.4）上退化为普通 shell（uid 2000），
  `id` 会显示 `uid=2000` 而不是 0 —— 这不是 bug。
- `clean` 只删标记文件，**不会**恢复 `init_cred` 的预算（那个只有重启能重置）。
  清完 `.glhold` 后重跑漏洞，等于把剩余预算再消耗一格。
- 广播没有加权限，同设备任意应用都能发。所有实际操作仍要过 Shizuku 授权，
  但 `clean`/`status` 这类不需要授权的命令对任何应用开放 —— 介意的话可以加
  `android:permission` 换 signature 级。
