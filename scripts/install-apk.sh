#!/usr/bin/env bash
# Install and launch the signed Pure 浏览器 APK on one connected device.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${1:-}" == "--help" ]]; then
    echo "Usage: [ANDROID_SERIAL=<device>] bash scripts/install-apk.sh [APK]"
    exit 0
fi
if [[ $# -gt 1 ]]; then
    echo "只接受一个 APK 路径；用 ANDROID_SERIAL 指定设备。" >&2
    exit 2
fi
release_version="$(awk -F '\"' '/^[[:space:]]*versionName[[:space:]]*=/ { print $2; exit }' "$PROJECT_DIR/app/build.gradle.kts")"
APK="${1:-$PROJECT_DIR/PureBrowser-v${release_version}-release.apk}"
PACKAGE="${PACKAGE:-com.mybrowser}"
ACTIVITY="${ACTIVITY:-$PACKAGE/com.mybrowser.MainActivity}"

if [[ ! -f "$APK" ]]; then
    echo "找不到 APK：$APK" >&2
    echo "先运行 ./build-and-test.sh --release，或把 APK 路径作为第一个参数传入。" >&2
    exit 1
fi
if ! command -v adb >/dev/null 2>&1; then
    echo "找不到 adb。" >&2
    exit 1
fi
adb get-state >/dev/null

echo "安装：$APK"
adb install -r "$APK"
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" >/dev/null
echo "已安装并启动 ${PACKAGE}。"
echo "查看崩溃：adb logcat -d | grep -E 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV'"
