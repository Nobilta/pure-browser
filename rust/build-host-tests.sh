#!/usr/bin/env bash
# Host-only JNI libraries for Robolectric; never staged into the Android APK.
set -euo pipefail
rust_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
destination="$1"
export CARGO_TARGET_DIR="$rust_root/target/host-tests"
cargo build --manifest-path "$rust_root/Cargo.toml" -p adblock -p url_utils
case "$(uname -s)" in
    Darwin) extension=dylib ;;
    Linux) extension=so ;;
    *) echo "Unsupported host for JNI tests" >&2; exit 1 ;;
esac
mkdir -p "$destination"
cp "$CARGO_TARGET_DIR/debug/libadblock.$extension" "$destination/libmybrowser_adblock.$extension"
cp "$CARGO_TARGET_DIR/debug/liburl_utils.$extension" "$destination/libmybrowser_url_utils.$extension"
