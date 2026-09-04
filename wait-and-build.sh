#!/usr/bin/env bash
# Compatibility name retained for old notes. NDK resolution is now handled by the
# canonical build script, so there is no polling of another agent's temporary output.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/build-and-test.sh" "$@"
