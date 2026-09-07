#!/usr/bin/env bash
# Reproducible local validation and Release build for Pure 浏览器.
#
# The script is intentionally rooted at its own directory. It does not edit a shell
# profile or ~/.cargo/config.toml; the Gradle/Rust build resolves the local NDK itself.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

BUILD_TASKS=(
    :app:testDebugUnitTest
    :app:lintDebug
    :app:assembleRelease
)

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "缺少必需工具：$1" >&2
        exit 1
    fi
}

require_command java
require_command cargo
require_command rustup
require_command shasum
require_command node
require_command python3

echo "检查中英文提示资源..."
python3 validation/check-localization.py

echo "运行网页视频控制协议测试..."
node --test validation/playback-probe.test.cjs

java_version="$(java -version 2>&1 | sed -n '1s/.*version \"\([^\"]*\)\".*/\1/p')"
echo "Java: ${java_version:-unknown}"
echo "Rust: $(rustc --version)"

ndk_path="$(bash "$SCRIPT_DIR/rust/resolve-android-ndk.sh" "$SCRIPT_DIR")"
export ANDROID_NDK_HOME="$ndk_path"
echo "NDK: $ANDROID_NDK_HOME"

if [[ "${SKIP_RUST_TARGET_CHECK:-0}" != "1" ]]; then
    rustup target add aarch64-linux-android >/dev/null 2>&1 || true
fi

echo "运行 Rust 格式/测试/clippy..."
cargo fmt --manifest-path "$SCRIPT_DIR/rust/Cargo.toml" --all -- --check
cargo test --manifest-path "$SCRIPT_DIR/rust/Cargo.toml" --all
cargo clippy --manifest-path "$SCRIPT_DIR/rust/Cargo.toml" --workspace --all-targets -- -D warnings

echo "运行 Android 单元测试、Lint 并构建 Release APK..."
./gradlew "${BUILD_TASKS[@]}" --console=plain

apk="$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$apk" ]]; then
    echo "未找到构建产物：$apk" >&2
    exit 1
fi

delivery="$SCRIPT_DIR/PureBrowser-v0.3.0-release.apk"
cp "$apk" "$delivery"
size="$(wc -c < "$delivery" | tr -d ' ')"
hash="$(shasum -a 256 "$delivery" | awk '{print $1}')"
echo "Release APK: $delivery"
echo "大小: ${size} bytes"
echo "SHA-256: $hash"

if command -v apksigner >/dev/null 2>&1; then
    apksigner verify --verbose "$delivery"
else
    # Android command-line installations often keep apksigner outside PATH. Resolve it
    # from the same SDK selected by local.properties/environment so the default local
    # setup still performs a real signature check.
    sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -f "$SCRIPT_DIR/local.properties" ]]; then
        configured_sdk="$(sed -n 's/^sdk\.dir=//p' "$SCRIPT_DIR/local.properties" | head -n 1)"
        if [[ -n "$configured_sdk" ]]; then
            sdk_dir="$configured_sdk"
        fi
    fi
    signer=""
    if [[ -d "$sdk_dir/build-tools" ]]; then
        signer="$(find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -perm -111 -print 2>/dev/null | sort -V | tail -n 1)"
    fi
    if [[ -n "$signer" ]]; then
        "$signer" verify --verbose "$delivery"
    else
        echo "提示：未找到 apksigner，跳过签名检查。"
    fi
fi

if [[ "${INSTALL:-0}" == "1" ]]; then
    require_command adb
    adb install -r "$delivery"
    adb shell am force-stop com.mybrowser || true
    adb shell am start -W -n com.mybrowser/com.mybrowser.MainActivity >/dev/null
    echo "已安装并启动 com.mybrowser。"
fi

echo "验证完成。设置 INSTALL=1 可在已连接设备上安装并启动。"
