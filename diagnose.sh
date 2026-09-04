#!/usr/bin/env bash
# Read-only device/install diagnostic for Pure 浏览器.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="${PACKAGE:-com.mybrowser}"
ACTIVITY="$PACKAGE/com.mybrowser.MainActivity"

if ! command -v adb >/dev/null 2>&1; then
    echo "找不到 adb。" >&2
    exit 1
fi

device_line="$(adb devices | awk '$2 == "device" { print; exit }')"
if [[ -z "$device_line" ]]; then
    echo "未检测到已授权的 Android 设备或模拟器。" >&2
    adb devices
    exit 1
fi

echo "设备：$device_line"
echo "ABI：$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "Android：$(adb shell getprop ro.build.version.release | tr -d '\r') (SDK $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
echo "型号：$(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r')"

if ! adb shell pm path "$PACKAGE" >/dev/null 2>&1; then
    echo "未安装 $PACKAGE。可先运行 INSTALL=1 ./build-and-test.sh。"
    exit 0
fi

echo "已安装：$PACKAGE"
adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell am start -n "$ACTIVITY" >/dev/null
sleep 3

log_file="$SCRIPT_DIR/validation/diagnose-logcat.txt"
mkdir -p "$SCRIPT_DIR/validation"
adb logcat -d > "$log_file"
if rg -n "FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV" "$log_file"; then
    echo "检测到崩溃关键词，完整日志：$log_file" >&2
    exit 1
fi

echo "启动检查通过；日志中未发现 FATAL EXCEPTION、UnsatisfiedLinkError 或 SIGSEGV。"
echo "日志：$log_file"
