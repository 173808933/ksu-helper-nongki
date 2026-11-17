#!/bin/bash
# build-ksud.sh —— 交叉编译 ksud (aarch64-linux-android)
#
# 目的：修复 4.19/ColorOS 上 soft_reboot 的 stop/start 被 property_service 拒绝的问题。
# 依赖：真实 Rust 工具链（~/.cargo/bin 的 shim 已损坏）、NDK 27.2、libclang。
set -eu

RUSTBIN=/c/Users/XXL/.rustup/toolchains/stable-x86_64-pc-windows-msvc/bin
NDK=C:/Other/Andriod/toolchain/android-sdk/ndk/27.2.12479018
NDKBIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
SRC=/c/Other/Andriod/ksu/KernelSU-3.3.1

export PATH="$RUSTBIN:$PATH"
export ANDROID_NDK_HOME="$NDK"
export LIBCLANG_PATH="C:/Users/XXL/.workbuddy/binaries/python/versions/3.13.12/Lib/site-packages/clang/native"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDKBIN/aarch64-linux-android33-clang.exe"
export CC_aarch64_linux_android="$NDKBIN/aarch64-linux-android33-clang.exe"
export AR_aarch64_linux_android="$NDKBIN/llvm-ar.exe"
export CXX_aarch64_linux_android="$NDKBIN/aarch64-linux-android33-clang.exe"
export KSU_LKM_BOOTSTRAP_CC="$NDKBIN/clang.exe"

# bindgen 通过 pip 的 libclang 运行，不会自动继承 NDK 的 sysroot，
# 必须显式告知，否则 uapi/supercall.h 找不到 linux/ioctl.h。
NDKSYSROOT="$NDK/toolchains/llvm/prebuilt/windows-x86_64/sysroot"
BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--sysroot=$NDKSYSROOT -I$NDKSYSROOT/usr/include -I$NDKSYSROOT/usr/include/aarch64-linux-android"
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android

# 与设备上的管理器/内核保持一致，避免管理器提示版本不符
export KSU_VERSION_CODE="${KSU_VERSION_CODE:-32715}"
export KSU_VERSION_NAME="${KSU_VERSION_NAME:-3.3.1}"

# 构建缓存放在工作区，不污染源码树
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-/c/Other/Andriod/ksu/build-target}"

cd "$SRC"
exec cargo build --release --target aarch64-linux-android -p ksud "$@"
