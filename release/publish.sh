#!/usr/bin/env bash
# Create a reviewable draft; publishing and repository visibility stay explicit.
set -euo pipefail
RELEASE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RELEASE_ROOT"
if [[ $# != 2 ]]; then
    echo "Usage: bash release/publish.sh <signed-release.apk> <verification-notes.md>" >&2
    exit 1
fi
release_apk="$1"
release_notes="$2"
release_gh="${GH:-gh}"
[[ -f "$release_notes" ]] || { echo "Verification notes file not found: $release_notes" >&2; exit 1; }
git diff --quiet
git diff --cached --quiet
[[ -z "$(git ls-files --others --exclude-standard)" ]] || { echo "Commit the source before drafting a release" >&2; exit 1; }
python3 release/prepare.py --apk "$release_apk" --notes "$release_notes"
release_tag="$(python3 -c 'import json; print(json.load(open("outputs/release/package-info.json"))["tag"])')"
# prepare.py composes the body from CHANGELOG.md and the verification summary; that composed file is
# what must go out. Uploading the argument would publish a release page that lists no changes.
release_body="$(python3 -c 'import json; print(json.load(open("outputs/release/package-info.json"))["releaseNotes"])')"
[[ -f "$release_body" ]] || { echo "Composed release notes are missing: $release_body" >&2; exit 1; }
release_commit="$(git rev-parse HEAD)"
release_remote="$("$release_gh" api repos/Nobilta/pure-browser/commits/main --jq .sha)"
[[ "$release_remote" == "$release_commit" ]] || { echo "Push the validated commit to remote main first" >&2; exit 1; }
# Gates: the commit must have passed CI, and the APK must match the source, be newer than the
# published release and carry the same signing certificate (see release/gate.py).
python3 release/gate.py --repo Nobilta/pure-browser --commit "$release_commit" --apk "$release_apk"
"$release_gh" release create "$release_tag" "$release_apk" outputs/release/update.json outputs/release/SHA256SUMS \
    --repo Nobilta/pure-browser --target "$release_commit" --title "Pure Browser $release_tag" \
    --notes-file "$release_body" --draft
