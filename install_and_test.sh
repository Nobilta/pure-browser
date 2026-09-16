#!/usr/bin/env bash
# Install and launch the signed Pure 浏览器 APK on one connected device.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
release_version="$(awk -F '\"' '/^[[:space:]]*versionName[[:space:]]*=/ { print $2; exit }' "$SCRIPT_DIR/app/build.gradle.kts")"
APK="${1:-$SCRIPT_DIR/PureBrowser-v${release_version}-release.apk}"
PACKAGE="${PACKAGE:-com.mybrowser}"
ACTIVITY="${ACTIVITY:-$PACKAGE/com.mybrowser.MainActivity}"

if [[ ! -f "$APK" ]]; then
    echo "找不到 APK：$APK" >&2
    echo "先运行 ./build-and-test.sh，或把 APK 路径作为第一个参数传入。" >&2
    exit 1
fi
if ! command -v adb >/dev/null 2>&1; then
    echo "找不到 adb。" >&2
    exit 1
fi
if ! adb devices | tr -d '\r' | awk '$2 == "device" { found=1 } END { exit !found }'; then
    echo "未找到已授权设备或模拟器。" >&2
    adb devices
    exit 1
fi

echo "安装：$APK"
adb install -r "$APK"
adb shell am force-stop "$PACKAGE" || true
adb shell am start -W -n "$ACTIVITY" >/dev/null
echo "已安装并启动 ${PACKAGE}。"
echo "查看崩溃：adb logcat -d | grep -E 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV'"
