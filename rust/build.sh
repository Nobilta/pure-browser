#!/usr/bin/env bash
# Build and stage the Rust JNI libraries used by the Android app.
# Used by Gradle and standalone builds so target/API resolution and staging stay identical.

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

# Pick the prebuilt toolchain for this host first, then fall back to the other known layouts, so
# the same script works on macOS and on Linux CI.
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) host_tag=darwin-aarch64 ;;
    Darwin-*) host_tag=darwin-x86_64 ;;
    Linux-x86_64) host_tag=linux-x86_64 ;;
    Linux-aarch64|Linux-arm64) host_tag=linux-aarch64 ;;
    *) host_tag="" ;;
esac
prebuilt="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
candidates=()
[[ -n "$host_tag" ]] && candidates+=("$prebuilt/$host_tag")
candidates+=("$prebuilt/darwin-x86_64" "$prebuilt/darwin-aarch64" "$prebuilt/linux-x86_64" "$prebuilt/linux-aarch64")
toolchain=""
for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate/bin/$linker_name" ]]; then
        toolchain="$candidate"
        break
    fi
done
if [[ -z "$toolchain" ]]; then
    echo "No Android clang toolchain with $linker_name under $prebuilt (host: $(uname -s)-$(uname -m))" >&2
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
cargo_args=(build --release --locked --target "$TARGET" -p adblock -p url_utils)
case "${PURE_FILTER_OPT:-}" in
    "") ;;
    z) cargo_args+=(--config 'profile.release.package.adblock.opt-level="z"') ;;
    2|3) cargo_args+=(--config "profile.release.package.adblock.opt-level=$PURE_FILTER_OPT") ;;
    *) echo "Unsupported filter optimization level" >&2; exit 1 ;;
esac
cargo "${cargo_args[@]}"

out_dir="$PROJECT_DIR/app/build/rustJniLibs/$ABI"
rm -rf "$out_dir"
mkdir -p "$out_dir"

cp "$CARGO_TARGET_DIR/$TARGET/release/libadblock.so" "$out_dir/libmybrowser_adblock.so"
cp "$CARGO_TARGET_DIR/$TARGET/release/liburl_utils.so" "$out_dir/libmybrowser_url_utils.so"

echo "All Rust libraries built successfully:"
ls -lh "$out_dir"
