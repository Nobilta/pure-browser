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

# Windows installs the interpreter as python or through the py launcher; POSIX hosts use python3.
PYTHON="${PYTHON:-}"
if [[ -z "$PYTHON" ]]; then
    for candidate in python3 python; do
        if command -v "$candidate" >/dev/null 2>&1; then
            PYTHON="$candidate"
            break
        fi
    done
fi
if [[ -z "$PYTHON" ]]; then
    echo "缺少必需工具：python3" >&2
    exit 1
fi
export PYTHON
# The validation harness reads and writes UTF-8 artifacts (device dumps, JSON with Chinese
# labels). Without this, Windows falls back to a legacy code page and can fail to encode them.
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8

# Git Bash has no shasum, and minimal Linux images may lack it too; Python is already required.
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        "$PYTHON" -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
    fi
}

require_command java
require_command cargo
require_command rustup
require_command node

echo "检查中英文提示资源..."
"$PYTHON" validation/check-localization.py

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

version="$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$SCRIPT_DIR/app/build/outputs/apk/release/output-metadata.json")"
delivery="$SCRIPT_DIR/PureBrowser-v${version}-release.apk"
if command -v apksigner >/dev/null 2>&1; then
    apksigner verify --verbose "$apk"
else
    # Android command-line installations often keep apksigner outside PATH. Resolve it
    # from the same SDK selected by local.properties/environment so the default local
    # setup still performs a real signature check.
    sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -f "$SCRIPT_DIR/local.properties" ]]; then
        # Java properties escaping: a Windows path arrives as C\:\\Users\\me\\AppData\\Local\\Android\\Sdk.
        configured_sdk="$(sed -n 's/^sdk\.dir=//p' "$SCRIPT_DIR/local.properties" | head -n 1 | sed -e 's/\\\(.\)/\1/g')"
        if [[ -n "$configured_sdk" ]]; then
            sdk_dir="$configured_sdk"
        fi
    fi
    [[ -z "$sdk_dir" && -n "${LOCALAPPDATA:-}" ]] && sdk_dir="$LOCALAPPDATA/Android/Sdk"
    signer=""
    if [[ -d "$sdk_dir/build-tools" ]]; then
        signer="$(find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -perm -111 -print 2>/dev/null | sort -V | tail -n 1)"
        # Windows build-tools ship apksigner.bat, which has neither the bare name nor an exec bit.
        if [[ -z "$signer" ]]; then
            signer="$(find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f \( -name 'apksigner.bat' -o -name 'apksigner.exe' \) -print 2>/dev/null | sort -V | tail -n 1)"
        fi
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
hash="$(sha256_of "$delivery")"
echo "Release APK: $delivery"
echo "大小: ${size} bytes"
echo "SHA-256: $hash"
"$PYTHON" release/prepare.py --apk "$delivery" --notes release/notes.md

if [[ "${INSTALL:-0}" == "1" ]]; then
    require_command adb
    adb install -r "$delivery"
    adb shell am force-stop com.mybrowser || true
    adb shell am start -W -n com.mybrowser/com.mybrowser.MainActivity >/dev/null
    echo "已安装并启动 com.mybrowser。"
fi

echo "验证完成。设置 INSTALL=1 可在已连接设备上安装并启动。"
