#!/usr/bin/env bash
# Cross-compile and stage the JNI libraries for every ABI given as an argument.
#
# This lives in a file rather than in Gradle's "bash -c" so no quoting has to survive the Windows
# command line, and so the same loop can be run by hand:
#   bash rust/build-abis.sh arm64-v8a x86_64
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ $# -eq 0 ]]; then
    echo "Usage: bash rust/build-abis.sh <abi> [abi...]" >&2
    exit 1
fi

for abi in "$@"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android ;;
        x86_64) target=x86_64-linux-android ;;
        *)
            echo "Unmapped ABI: $abi" >&2
            exit 1
            ;;
    esac
    TARGET="$target" ABI="$abi" bash ./build.sh
done
