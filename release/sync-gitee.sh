#!/usr/bin/env bash
# Mirror the prepared release attachments to Gitee. Safe to rerun: an existing release is reused
# and attachments that are already present are skipped.
#
# Environment:
#   GITEE_TOKEN  Gitee personal access token with the projects scope (required)
#   GITEE_REPO   Gitee repository, defaults to Nobilta/pure-browser
set -euo pipefail
RELEASE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RELEASE_ROOT"

: "${GITEE_TOKEN:?Set GITEE_TOKEN to a Gitee personal access token with projects scope}"
GITEE_REPO="${GITEE_REPO:-Nobilta/pure-browser}"
API="https://gitee.com/api/v5/repos/$GITEE_REPO"
STATE_DIR="${TMPDIR:-/tmp}/pure-browser-release"
mkdir -p "$STATE_DIR"

package_info() {
    python3 -c 'import json,sys; print(json.load(open("outputs/release/package-info.json"))[sys.argv[1]])' "$1"
}
TAG="$(package_info tag)"
APK="$(package_info apk)"
NOTES="$(package_info releaseNotes)"
[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
[[ -f "$NOTES" ]] || { echo "Release notes not found: $NOTES" >&2; exit 1; }

lookup_code="$(curl -sS -o "$STATE_DIR/release.json" -w '%{http_code}' \
    -G "$API/releases/tags/$TAG" --data-urlencode "access_token=$GITEE_TOKEN")"

if [[ "$lookup_code" == 200 ]]; then
    echo "Gitee release $TAG already exists"
elif [[ "$lookup_code" == 404 ]]; then
    create_code="$(curl -sS -o "$STATE_DIR/release.json" -w '%{http_code}' \
        -X POST "$API/releases" \
        --data-urlencode "access_token=$GITEE_TOKEN" \
        --data-urlencode "tag_name=$TAG" \
        --data-urlencode "name=Pure Browser $TAG" \
        --data-urlencode "body@$NOTES" \
        --data-urlencode "target_commitish=main" \
        --data-urlencode "prerelease=false")"
    [[ "$create_code" == 201 ]] || { echo "Gitee release creation failed: HTTP $create_code" >&2; exit 1; }
    echo "Gitee release $TAG created"
else
    echo "Gitee release lookup failed: HTTP $lookup_code" >&2
    exit 1
fi

release_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$STATE_DIR/release.json")"

curl -sS -o "$STATE_DIR/release.json" -G "$API/releases/$release_id" \
    --data-urlencode "access_token=$GITEE_TOKEN"
python3 -c 'import json,sys; [print(a["name"]) for a in json.load(open(sys.argv[1])).get("assets", [])]' \
    "$STATE_DIR/release.json" > "$STATE_DIR/assets.txt"

upload() {
    local file="$1" name code
    name="$(basename "$file")"
    if grep -qxF "$name" "$STATE_DIR/assets.txt"; then
        echo "skip $name (already uploaded)"
        return
    fi
    code="$(curl -sS -o "$STATE_DIR/attach.json" -w '%{http_code}' \
        -X POST "$API/releases/$release_id/attach_files" \
        -F "access_token=$GITEE_TOKEN" -F "file=@$file")"
    [[ "$code" == 201 ]] || { echo "Upload failed for $name: HTTP $code" >&2; exit 1; }
    echo "uploaded $name"
}

upload "$APK"
upload outputs/release/update.json
upload outputs/release/SHA256SUMS

echo "Gitee release: https://gitee.com/$GITEE_REPO/releases/tag/$TAG"
