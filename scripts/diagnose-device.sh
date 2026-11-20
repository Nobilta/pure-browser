#!/usr/bin/env bash
# Read-only device/install diagnostic for Pure 浏览器.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="${PACKAGE:-com.mybrowser}"
if [[ "${1:-}" == "--help" ]]; then
    echo "Usage: [ANDROID_SERIAL=<device>] [PACKAGE=<package>] bash scripts/diagnose-device.sh"
    exit 0
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "找不到 adb。" >&2
    exit 1
fi

adb get-state >/dev/null
device_serial="$(adb get-serialno | tr -d '\r')"

echo "设备：$device_serial"
echo "ABI：$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "Android：$(adb shell getprop ro.build.version.release | tr -d '\r') (SDK $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
echo "型号：$(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r')"

if ! adb shell pm path "$PACKAGE" >/dev/null 2>&1; then
    echo "未安装 $PACKAGE。可先运行 INSTALL=1 ./build-and-test.sh --release。"
    exit 0
fi

echo "已安装：$PACKAGE"
log_file="$PROJECT_DIR/validation/results/diagnose-logcat.txt"
mkdir -p "$PROJECT_DIR/validation/results"
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
