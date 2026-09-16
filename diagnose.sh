#!/usr/bin/env bash
# Read-only device/install diagnostic for Pure 浏览器.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="${PACKAGE:-com.mybrowser}"

if ! command -v adb >/dev/null 2>&1; then
    echo "找不到 adb。" >&2
    exit 1
fi

# Windows adb ends every line with CRLF, which would defeat the awk field comparison.
device_line="$(adb devices | tr -d '\r' | awk '$2 == "device" { print; exit }')"
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
log_file="$SCRIPT_DIR/validation/results/diagnose-logcat.txt"
mkdir -p "$SCRIPT_DIR/validation/results"
adb logcat -d > "$log_file"
search_log() {
    # ripgrep is convenient but not installed everywhere, and not by default on Windows.
    if command -v rg >/dev/null 2>&1; then
        rg -n "$1" "$log_file"
    else
        grep -nE "$1" "$log_file"
    fi
}

if search_log "FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV"; then
    echo "检测到异常关键词，请按 PID 确认所属进程；完整日志：$log_file" >&2
    exit 1
fi

echo "当前日志中未发现 FATAL EXCEPTION、UnsatisfiedLinkError 或 SIGSEGV。"
echo "日志：$log_file"
