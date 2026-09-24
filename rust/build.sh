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

# Under MSYS/Cygwin a path such as /c/Users/me/src is meaningless to cargo and clang, which are
# native programs; cygpath -m returns a form both they and the shell accept. Everywhere else this
# is the identity.
to_native_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

export CARGO_TARGET_DIR="$(to_native_path "$SCRIPT_DIR/target/android-api-$android_api")"

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
# the same script works on macOS, on Linux and in Git Bash on Windows.
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) host_tag=darwin-aarch64 ;;
    Darwin-*) host_tag=darwin-x86_64 ;;
    Linux-x86_64) host_tag=linux-x86_64 ;;
    Linux-aarch64|Linux-arm64) host_tag=linux-aarch64 ;;
    MINGW*|MSYS*|CYGWIN*) host_tag=windows-x86_64 ;;
    *) host_tag="" ;;
esac
prebuilt="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
candidates=()
[[ -n "$host_tag" ]] && candidates+=("$prebuilt/$host_tag")
candidates+=("$prebuilt/darwin-x86_64" "$prebuilt/darwin-aarch64" "$prebuilt/linux-x86_64" "$prebuilt/linux-aarch64" "$prebuilt/windows-x86_64")
toolchain=""
linker_file=""
for candidate in "${candidates[@]}"; do
    # The Windows NDK wraps the drivers as .cmd files; POSIX hosts ship them under the bare name.
    if [[ -x "$candidate/bin/$linker_name" ]]; then
        toolchain="$candidate"
        linker_file="$linker_name"
        break
    fi
    if [[ -f "$candidate/bin/$linker_name.cmd" ]]; then
        toolchain="$candidate"
        linker_file="$linker_name.cmd"
        break
    fi
done
if [[ -z "$toolchain" ]]; then
    echo "No Android clang toolchain with $linker_name under $prebuilt (host: $(uname -s)-$(uname -m))" >&2
    exit 1
fi

toolchain="$(to_native_path "$toolchain")"
export PATH="$toolchain/bin:${PATH:-}"

# The Windows NDK ships each API driver as a pair of wrappers (a shell script and a .cmd) around
# clang.exe. A native rustc can execute neither — the script has no meaning to CreateProcess and a
# batch file needs a command interpreter — so drive clang.exe and pass the --target the wrapper
# would have added. Elsewhere the wrapper is a real executable and stays the linker.
linker="$toolchain/bin/$linker_file"
archiver="$toolchain/bin/llvm-ar"
rust_arguments=()
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        if [[ -f "$toolchain/bin/clang.exe" ]]; then
            linker="$toolchain/bin/clang.exe"
            # Written without a space: an encoded element reaches rustc as one argument, and
            # "-C link-arg=..." would then be read as the codegen option " link-arg" and rejected
            # ("unknown codegen option"). The whitespace-split form this replaced only worked
            # because CARGO_TARGET_<triple>_RUSTFLAGS divided it into two arguments for us.
            rust_arguments+=("-Clink-arg=--target=${linker_name%-clang}")
        fi
        [[ -f "$toolchain/bin/llvm-ar.exe" ]] && archiver="$toolchain/bin/llvm-ar.exe"
        ;;
esac

# The released libraries embed the absolute path of every dependency source file — 19 hits
# across the two arm64 libraries in 0.12 — which puts the build host's home directory inside
# the APK. Remap the cargo registry and the checkout to fixed roots so the shipped artifacts
# describe no particular machine and a rebuild is comparable byte for byte.
cargo_home="${CARGO_HOME:-$HOME/.cargo}"
rust_arguments+=("--remap-path-prefix=$cargo_home/registry=/cargo/registry")
rust_arguments+=("--remap-path-prefix=$PROJECT_DIR=/build/pure-browser")

# Passed encoded, because CARGO_TARGET_<triple>_RUSTFLAGS is split on whitespace: a checkout or
# cargo home whose path contains a space arrives at rustc as two arguments and the build stops
# with "--remap-path-prefix must contain '='". The encoded form keeps the argument boundaries by
# joining with 0x1f. Cargo builds one target per invocation here, so the global name is exact.
encoded_rustflags=""
for argument in "${rust_arguments[@]}"; do
    # The separator is appended outside the quoted expansion so the loop reads the same under
    # any shell; the value itself is a plain byte, never the characters that spell it.
    [[ -n "$encoded_rustflags" ]] && encoded_rustflags+=$'\x1f'
    encoded_rustflags+="$argument"
done
export CARGO_ENCODED_RUSTFLAGS="$encoded_rustflags"

if [[ "$TARGET" == "aarch64-linux-android" ]]; then
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$linker"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$archiver"
else
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$linker"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_AR="$archiver"
fi

echo "Building Rust libraries for $TARGET (linker: $(basename "$linker"))..."
if ! rustup target add "$TARGET" >/dev/null 2>&1; then
    # Already installed is the normal case here, so this is not fatal on its own; a genuine
    # failure then surfaces from cargo. Reporting it keeps the reason instead of discarding it.
    echo "Note: rustup could not add the $TARGET target; continuing in case it is installed." >&2
fi
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
