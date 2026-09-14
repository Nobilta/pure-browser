#!/usr/bin/env bash
# Reproducible local validation and Release build for Pure 浏览器.
#
# The script is intentionally rooted at its own directory. It does not edit a shell
# profile or ~/.cargo/config.toml; the Gradle/Rust build resolves the local NDK itself.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

build_mode="${1:---quick}"
case "$build_mode" in
    --quick|--release) ;;
    --help|-h)
        echo "Usage: ./build-and-test.sh [--quick|--release]"
        echo "  --quick (default): localization, Node, Rust and Android unit tests"
        echo "  --release: also run clippy, lint, signing checks and generate release assets"
        exit 0 ;;
    *) echo "Unknown build mode: $build_mode" >&2; exit 1 ;;
esac
[[ $# -le 1 ]] || { echo "Use one build mode" >&2; exit 1; }
BUILD_TASKS=(:app:testDebugUnitTest)
if [[ "$build_mode" == "--release" ]]; then
    BUILD_TASKS+=(:app:lintDebug :app:assembleRelease)
elif [[ "${INSTALL:-0}" == "1" ]]; then
    echo "INSTALL=1 requires --release" >&2
    exit 1
fi

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

echo "运行网页视频控制及用户脚本协议测试..."
node --test validation/*.test.cjs

java_version="$(java -version 2>&1 | sed -n '1s/.*version \"\([^\"]*\)\".*/\1/p')"
echo "Java: ${java_version:-unknown}"
echo "Rust: $(rustc --version)"

ndk_path="$(bash "$SCRIPT_DIR/rust/resolve-android-ndk.sh" "$SCRIPT_DIR")"
export ANDROID_NDK_HOME="$ndk_path"
echo "NDK: $ANDROID_NDK_HOME"

if [[ "${SKIP_RUST_TARGET_CHECK:-0}" != "1" ]] && ! rustup target list --installed | grep -qx 'aarch64-linux-android'; then
    rustup target add aarch64-linux-android
fi

echo "运行 Rust 格式/测试/clippy..."
(
    cd "$SCRIPT_DIR/rust"
    cargo fmt --all -- --check
)
cargo test --locked --manifest-path "$SCRIPT_DIR/rust/Cargo.toml" --all
if [[ "$build_mode" == "--release" ]]; then
    cargo clippy --locked --manifest-path "$SCRIPT_DIR/rust/Cargo.toml" --workspace --all-targets -- -D warnings
fi

echo "运行 Android 检查（${build_mode}）..."
./gradlew "${BUILD_TASKS[@]}" --console=plain
if [[ "$build_mode" == "--quick" ]]; then
    echo "快速检查完成。交付前运行 ./build-and-test.sh --release。"
    exit 0
fi

apk="$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$apk" ]]; then
    echo "未找到构建产物：$apk" >&2
    exit 1
fi

version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$SCRIPT_DIR/app/build/outputs/apk/release/output-metadata.json")"
delivery="$SCRIPT_DIR/PureBrowser-v${version}-release.apk"
if command -v apksigner >/dev/null 2>&1; then
    apksigner verify --verbose "$apk"
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
        "$signer" verify --verbose "$apk"
    else
        echo "未找到 apksigner，无法验证可安装的 Release 签名。" >&2
        exit 1
    fi
fi

# Publish only after verification, so an unsigned build cannot replace a usable delivery.
cp "$apk" "$delivery.tmp"
mv -f "$delivery.tmp" "$delivery"
size="$(wc -c < "$delivery" | tr -d ' ')"
hash="$(shasum -a 256 "$delivery" | awk '{print $1}')"
echo "Release APK: $delivery"
echo "大小: ${size} bytes"
echo "SHA-256: $hash"
python3 release/prepare.py --apk "$delivery" --notes release/notes.md

if [[ "${INSTALL:-0}" == "1" ]]; then
    require_command adb
    adb install -r "$delivery"
    adb shell am force-stop com.mybrowser || true
    adb shell am start -W -n com.mybrowser/com.mybrowser.MainActivity >/dev/null
    echo "已安装并启动 com.mybrowser。"
fi

echo "验证完成。设置 INSTALL=1 可在已连接设备上安装并启动。"
