#!/usr/bin/env bash
# Install the Android NDK required by Pure 浏览器 when it is not already present.
# This is opt-in and only changes the SDK selected by local.properties/environment;
# it never edits the user's shell profile or global Cargo configuration.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"

configured_sdk=""
if [[ -f "$PROJECT_DIR/local.properties" ]]; then
    configured_sdk="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_DIR/local.properties" | head -n 1)"
fi
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${configured_sdk:-}}}"
if [[ -z "$SDK_DIR" ]]; then
    SDK_DIR="$HOME/Library/Android/sdk"
fi

NDK_VERSION="${ANDROID_NDK_VERSION:-29.0.14206865}"
SDKMANAGER="${SDKMANAGER:-$SDK_DIR/cmdline-tools/latest/bin/sdkmanager}"
if [[ ! -x "$SDKMANAGER" ]] && command -v sdkmanager >/dev/null 2>&1; then
    SDKMANAGER="$(command -v sdkmanager)"
fi
if [[ ! -x "$SDKMANAGER" ]]; then
    echo "找不到 sdkmanager：$SDKMANAGER" >&2
    echo "请先安装 Android command-line tools，或设置 SDKMANAGER。" >&2
    exit 1
fi

if [[ -d "$SDK_DIR/ndk/$NDK_VERSION" ]]; then
    echo "NDK $NDK_VERSION 已存在：$SDK_DIR/ndk/$NDK_VERSION"
else
    echo "安装 NDK $NDK_VERSION 到 $SDK_DIR"
    yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" "platform-tools" "ndk;$NDK_VERSION"
fi

if command -v rustup >/dev/null 2>&1; then
    rustup target add aarch64-linux-android
    rustup target add x86_64-linux-android || true
fi

echo
echo "NDK 已准备好。构建时运行："
echo "  ./build-and-test.sh"
echo "或显式指定："
echo "  ANDROID_NDK_HOME=\"$SDK_DIR/ndk/$NDK_VERSION\" ./build-and-test.sh"
