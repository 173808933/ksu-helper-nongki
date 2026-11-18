#!/bin/bash
# 用指定机型的真实 config 编 KernelSU 模块（4.19）
#
# 用法：build-ksu-419.sh <内核源码树> <config 文件>
# 例：  build-ksu-419.sh /home/dev/mt6889-kern /tmp/ren5p-config.txt
#
# 背景：OPPO 的主内核仓库缺一批私有 Kconfig（drivers/soc/oplus 等，
# 放在 android_kernel_modules_and_devicetree_* 另一仓库），直接 olddefconfig
# 会报 "can't open file"。**不要**按路径存在性批量注释 —— source 路径是相对
# 当前 Kconfig 目录的，批量处理会把 drivers/ fs/ net/ 一起切断，
# .config 从 6000+ 行掉到 636 行。正确做法：报错只注释那一行，迭代到收敛。

set -e

KERN=${1:?需要内核源码目录}
CONF=${2:?需要 config 文件}
KSU=${KSU_SRC:-/home/dev/KernelSU/kernel}
export ARCH=arm64
export CROSS_COMPILE=aarch64-linux-gnu-
export PATH=/usr/local/bin:$PATH   # 内核脚本要 python，见下方软链

echo "=== 1/5 铺 config ==="
cd "$KERN"
cp "$CONF" .config
echo "  .config $(wc -l < .config) 行"

echo "=== 2/5 迭代掉缺失的 Kconfig 引用 ==="
for i in $(seq 1 80); do
    out=$(make ARCH=arm64 CROSS_COMPILE=$CROSS_COMPILE syncconfig 2>&1 || true)
    hit=$(echo "$out" | grep -oE "^[A-Za-z0-9_./-]+Kconfig:[0-9]+: can.t open" | head -1)
    if [ -z "$hit" ]; then
        echo "  第 $i 轮收敛，.config $(wc -l < .config) 行"
        break
    fi
    f=$(echo "$hit" | cut -d: -f1)
    n=$(echo "$hit" | cut -d: -f2)
    sed -i "${n}s|^|# |" "$f"
done

echo "=== 3/5 校验关键项（决定 struct module 大小 / 符号可用性）==="
for k in CONFIG_MODULE_UNLOAD CONFIG_TRACEPOINTS CONFIG_KALLSYMS_ALL \
         CONFIG_MODVERSIONS CONFIG_MODULE_SIG_FORCE CONFIG_HZ CONFIG_ARM64_4K_PAGES; do
    printf "  %-30s " "$k"
    grep -E "^$k=" .config >/dev/null 2>&1 && grep -E "^$k=" .config | head -1 || echo "未启用"
done

echo "=== 4/5 生成 flask.h（4.19 的 selinux 头是构建期生成的）==="
# 不做的话编到 infra/file_wrapper.c 会报 security/selinux/include/flask.h 不存在
if [ ! -f security/selinux/flask.h ]; then
    mkdir -p scripts/selinux/genheaders
    gcc -o scripts/selinux/genheaders/genheaders \
        scripts/selinux/genheaders/genheaders.c 2>/dev/null || true
    if [ -x scripts/selinux/genheaders/genheaders ]; then
        ./scripts/selinux/genheaders/genheaders \
            security/selinux/include/classmap.h > /tmp/flask.h 2>/dev/null || true
    fi
fi
ls -la security/selinux/flask.h security/selinux/av_permissions.h 2>/dev/null || \
    echo "  ! flask.h 仍未生成，编到 file_wrapper.c 时会失败（见 DEVLOG）"

echo "=== 5/5 编 KernelSU ==="
cd "$KSU"
make -C "$KERN" M="$KSU" src="$KSU" CONFIG_KSU=m \
     ARCH=arm64 CROSS_COMPILE=$CROSS_COMPILE modules -j"$(nproc)" 2>&1 \
  | grep -vE "OplusKernelEnvConfig|to be a macro" | tail -25

echo
echo "=== 产物 ==="
ls -la "$KSU"/*.ko 2>/dev/null || echo "  没有 .ko（看上面的 error 行）"
