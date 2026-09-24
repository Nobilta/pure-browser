#!/usr/bin/env bash
# Print the Android NDK directory to stdout.
#
# Resolution is shared by Gradle and build.sh so a clean checkout does not depend on a
# developer's absolute home-directory path.  The project local.properties file is the
# first source of truth; standard SDK environment variables and the two common macOS SDK
# locations are fallbacks for command-line builds.

set -euo pipefail

project_dir="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    exit 0
fi

sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
local_properties="$project_dir/local.properties"
if [[ -f "$local_properties" ]]; then
    # local.properties follows Java properties escaping, so a Windows path arrives as
    # C\:\\Users\\me\\AppData\\Local\\Android\\Sdk; unescape before testing the directory.
    configured_sdk="$(sed -n 's/^sdk\.dir=//p' "$local_properties" | head -n 1 | tr -d '\r' | sed -e 's/\\\(.\)/\1/g')"
    if [[ -n "$configured_sdk" && -d "$configured_sdk" ]]; then
        sdk_dir="$configured_sdk"
    fi
fi

roots=()
[[ -n "$sdk_dir" ]] && roots+=("$sdk_dir")
# Fallbacks for a host where the SDK exists but is not exported: Windows, then the two common
# macOS installations.
[[ -n "${LOCALAPPDATA:-}" ]] && roots+=("$LOCALAPPDATA/Android/Sdk")
roots+=("$HOME/Library/Android/sdk" "/opt/homebrew/share/android-commandlinetools")

best=""
for root in "${roots[@]}"; do
    [[ -d "$root/ndk" ]] || continue
    for candidate in "$root"/ndk/*; do
        [[ -d "$candidate" ]] || continue
        # Compare versions numerically. String order puts 26.3 above 26.11, so a host with
        # both installed silently built against the older NDK.
        if [[ -z "$best" ]]; then
            best="$candidate"
            continue
        fi
        highest="$(printf '%s\n%s\n' "$(basename "$best")" "$(basename "$candidate")" | sort -V | tail -n 1)"
        [[ "$(basename "$candidate")" == "$highest" ]] && best="$candidate"
    done
done

if [[ -z "$best" ]]; then
    echo "Android NDK not found. Set ANDROID_NDK_HOME or sdk.dir in local.properties." >&2
    exit 1
fi

printf '%s\n' "$best"
