#!/usr/bin/env bash
# Host-only JNI libraries for Robolectric; never staged into the Android APK.
set -euo pipefail
rust_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
destination="$1"

# A path the MSYS shell uses (/c/...) is not a path native cargo understands, and vice versa;
# cygpath -m produces the one form both accept. POSIX hosts pass their paths through untouched.
to_native_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}
export CARGO_TARGET_DIR="$(to_native_path "$rust_root/target/host-tests")"

# System.loadLibrary("mybrowser_adblock") maps to libmybrowser_adblock.{dylib,so} on macOS and
# Linux, but to mybrowser_adblock.dll on Windows, where cargo also drops the lib prefix.
case "$(uname -s)" in
    Darwin) extension=dylib; prefix=lib ;;
    Linux) extension=so; prefix=lib ;;
    MINGW*|MSYS*|CYGWIN*) extension=dll; prefix= ;;
    *) echo "Unsupported host for JNI tests" >&2; exit 1 ;;
esac

# --locked keeps the host-test dependency graph identical to the one recorded in Cargo.lock,
# which the release path already enforces; without it a host test can resolve differently from
# the library that actually ships.
cargo build --locked --manifest-path "$(to_native_path "$rust_root/Cargo.toml")" -p adblock -p url_utils

mkdir -p "$destination"
cp "$CARGO_TARGET_DIR/debug/${prefix}adblock.$extension" "$destination/${prefix}mybrowser_adblock.$extension"
cp "$CARGO_TARGET_DIR/debug/${prefix}url_utils.$extension" "$destination/${prefix}mybrowser_url_utils.$extension"
