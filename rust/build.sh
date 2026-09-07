#!/usr/bin/env bash
# Build and stage the Rust JNI libraries used by the Android app.
#
# The application currently links adblock and url_utils. cache, downloader and
# filename_parser are retained as source-compatible legacy integrations and can be included
# explicitly with INCLUDE_LEGACY_RUST=1.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$SCRIPT_DIR"

TARGET="${TARGET:-aarch64-linux-android}"
ABI="${ABI:-arm64-v8a}"
android_api="$(awk -F '\"' '/^minSdk[[:space:]]*=/ {print $2}' "$PROJECT_DIR/gradle/libs.versions.toml")"
if [[ ! "$android_api" =~ ^[0-9]+$ ]]; then
    echo "Cannot read minSdk from the version catalog" >&2
    exit 1
fi
export CARGO_TARGET_DIR="$SCRIPT_DIR/target/android-api-$android_api"

case "$TARGET:$ABI" in
    aarch64-linux-android:arm64-v8a)
        linker_name="aarch64-linux-android${android_api}-clang"
        ;;
    x86_64-linux-android:x86_64)
        linker_name="x86_64-linux-android${android_api}-clang"
        ;;
    *)
        echo "Unsupported Android target/ABI pair: $TARGET / $ABI" >&2
        exit 1
        ;;
esac

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ANDROID_NDK_HOME="$("$SCRIPT_DIR/resolve-android-ndk.sh" "$PROJECT_DIR")"
    export ANDROID_NDK_HOME
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then
    echo "ANDROID_NDK_HOME could not be resolved" >&2
    exit 1
fi

toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64"
if [[ ! -x "$toolchain/bin/$linker_name" ]]; then
    toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-aarch64"
fi
if [[ ! -x "$toolchain/bin/$linker_name" ]]; then
    echo "No Android clang toolchain found under $ANDROID_NDK_HOME" >&2
    exit 1
fi

export PATH="$toolchain/bin:${PATH:-}"
if [[ "$TARGET" == "aarch64-linux-android" ]]; then
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/bin/$linker_name"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$toolchain/bin/llvm-ar"
else
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$toolchain/bin/$linker_name"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_AR="$toolchain/bin/llvm-ar"
fi

echo "Building Rust libraries for $TARGET..."
rustup target add "$TARGET" >/dev/null 2>&1 || true
cargo build --release --target "$TARGET" -p adblock -p url_utils

out_dir="$PROJECT_DIR/app/build/rustJniLibs/$ABI"
rm -rf "$out_dir"
mkdir -p "$out_dir"

cp "$CARGO_TARGET_DIR/$TARGET/release/libadblock.so" "$out_dir/libmybrowser_adblock.so"
cp "$CARGO_TARGET_DIR/$TARGET/release/liburl_utils.so" "$out_dir/libmybrowser_url_utils.so"

if [[ "${INCLUDE_LEGACY_RUST:-0}" == "1" ]]; then
    cargo build --release --target "$TARGET" -p cache -p downloader -p filename_parser
    cp "$CARGO_TARGET_DIR/$TARGET/release/libcache.so" "$out_dir/libmybrowser_cache.so"
    cp "$CARGO_TARGET_DIR/$TARGET/release/libdownloader.so" "$out_dir/libmybrowser_downloader.so"
    cp "$CARGO_TARGET_DIR/$TARGET/release/libfilename_parser.so" "$out_dir/libmybrowser_filename_parser.so"
fi

echo "All Rust libraries built successfully:"
ls -lh "$out_dir"
