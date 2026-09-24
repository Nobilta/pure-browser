#!/usr/bin/env python3
"""Generate GitHub Release attachments from the verified, signed delivery APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def unescape_property(value):
    """local.properties is a Java properties file: a Windows path arrives as C\\:\\\\Users\\\\me."""
    return re.sub(r'\\(.)', r'\1', value)


def android_tool(name):
    configured = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    properties = ROOT / 'local.properties'
    if properties.is_file():
        for line in properties.read_text(encoding='utf-8').splitlines():
            if line.startswith('sdk.dir='):
                configured = unescape_property(line.partition('=')[2].strip())
    if configured:
        build_tools = Path(configured) / 'build-tools'
        # The bare name is what macOS and Linux ship; Windows uses apksigner.bat, zipalign.exe and
        # aapt2.exe. Trying the bare name first keeps the POSIX result byte-identical.
        candidates = []
        for suffix in ('', '.bat', '.exe'):
            candidates.extend(build_tools.glob('*/' + name + suffix))
        candidates.sort(key=lambda p: tuple(map(int, re.findall(r'\d+', p.parent.name))), reverse=True)
        if candidates:
            return str(candidates[0])
    command = shutil.which(name)
    if command:
        return command
    raise ValueError('Android build tool not found: ' + name)


def run(*command):
    return subprocess.check_output(command, text=True, encoding='utf-8', stderr=subprocess.STDOUT)


def changelog_body(version):
    """The section CHANGELOG.md holds for this version, without its heading.

    The change list lives in the changelog and nowhere else. The release body is composed from it
    plus the verification summary, so publishing a release cannot leave the same twenty bullets
    written out in two files that then drift apart.
    """
    lines = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8').splitlines()
    named = re.compile(r'^##\s+' + re.escape(version) + r'\s*(?:-|$)')
    unreleased = re.compile(r'^##\s+未发布\s*$')
    # A named section is what the version bump writes; `未发布` is where the same work sits until
    # then, so a release prepared before the rename still finds its notes.
    start = next((i for i, line in enumerate(lines) if named.match(line)), None)
    if start is None:
        start = next((i for i, line in enumerate(lines) if unreleased.match(line)), None)
    if start is None:
        raise ValueError('CHANGELOG.md has no "## ' + version + '" or "## 未发布" section')
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith('## ')), len(lines))
    body = '\n'.join(lines[start + 1:end]).strip()
    if not body:
        raise ValueError('The CHANGELOG.md section for ' + version + ' is empty')
    return body


def compose_notes(version):
    """The release body: the changelog section for this version, and nothing else.

    The release page and the update prompt inside the app both render this same text, so it holds
    only what a user can act on. The verification record lives in release/notes.md: the process
    requires it and checks that it belongs to this version, but it is not published.
    """
    return '# Pure Browser ' + version + '\n\n' + changelog_body(version) + '\n'


def prepare(apk, output, notes=None):
    if not apk.is_file() or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,150}\.apk', apk.name):
        raise ValueError('Choose the signed delivery APK with a filename safe for release assets')
    size = apk.stat().st_size
    if not 0 < size <= 128 * 1024 * 1024:
        raise ValueError('APK exceeds the updater size limit')
    certificate = run(android_tool('apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in certificate:
        raise ValueError('The delivery APK must have a valid v2 signature')
    signers = set(re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', certificate))
    if len(signers) != 1 or not re.search(r'^Number of signers: 1$', certificate, re.M):
        raise ValueError('Expected one release signing certificate')
    run(android_tool('zipalign'), '-c', '-P', '16', '4', str(apk))
    badging = run(android_tool('aapt2'), 'dump', 'badging', str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    if not package or package[1] != 'com.mybrowser' or not sdk:
        raise ValueError('Only the com.mybrowser release package can be published')
    code, version, min_sdk = int(package[2]), package[3], int(sdk[1])
    if not 0 < code <= 2_100_000_000 or not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z.+_-]{0,99}', version):
        raise ValueError('Invalid release version')
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
    if abis != {'arm64-v8a'}:
        raise ValueError('The official release currently ships arm64-v8a only: ' + str(sorted(abis)))
    checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
    # `--notes` is the verification record (release/notes.md) the release process requires. It is
    # checked against the version being shipped and kept as a local record; it is not part of the
    # published body, which comes from CHANGELOG.md alone.
    verification = notes.read_text(encoding='utf-8').strip() if notes else ''
    if verification:
        # The record is prose with a heading, so the version is looked for anywhere in it — bounded,
        # so a record left over from 0.1 is not accepted as 0.13's.
        if not re.search(r'(?<![\d.])' + re.escape(version) + r'(?!\d)', verification):
            raise ValueError('the verification record does not name version ' + version)
    release_notes = compose_notes(version)
    if len(release_notes) > 12_000:
        raise ValueError('Release notes exceed 12,000 characters')
    # These notes become the body of update.json, which is generated here and then published as an
    # attachment: a file still describing the previous version would go out with this release and be
    # shown in the in-app update prompt. The composed body always names its version first, and this
    # stays as the check that it does.
    first_line = release_notes.splitlines()[0]
    if version not in first_line:
        raise ValueError('release notes must name version ' + version + ' in their first line: ' + first_line)
    manifest = {'schemaVersion': 1, 'channel': 'stable', 'packageName': package[1], 'versionCode': code,
                'versionName': version, 'minSdk': min_sdk,
                'releaseNotes': release_notes,
                'artifacts': [{'abi': 'arm64-v8a', 'assetName': apk.name, 'size': size, 'sha256': checksum}]}
    output.mkdir(parents=True, exist_ok=True)
    # Written for publish.sh to upload: this composed body is what the release page shows.
    notes_path = output / 'release-notes.md'
    notes_temporary = output / 'release-notes.md.tmp'
    notes_temporary.write_text(release_notes, encoding='utf-8')
    notes_temporary.replace(notes_path)
    destination = output / 'update.json'
    temporary = output / 'update.json.tmp'
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(destination)
    manifest_hash = hashlib.sha256(destination.read_bytes()).hexdigest()
    (output / 'SHA256SUMS').write_text(f'{checksum}  {apk.name}\n{manifest_hash}  update.json\n', encoding='utf-8')
    details = {'apk': str(apk), 'sha256': checksum, 'signerSha256': next(iter(signers)).lower(), 'tag': 'v' + version,
               'size': size, 'versionCode': code, 'versionName': version, 'minSdk': min_sdk, 'abi': 'arm64-v8a',
               'releaseNotes': str(notes_path),
               'verification': str(notes.resolve()) if notes else None}
    (output / 'package-info.json').write_text(json.dumps(details, indent=2) + '\n', encoding='utf-8')
    return details


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'outputs/release')
    parser.add_argument('--notes', type=Path,
                        help='验证记录（release/notes.md）：核对版本并留档，不进入发布说明；'
                             '发布说明由本脚本从 CHANGELOG.md 取对应版本段落生成')
    args = parser.parse_args()
    try:
        details = prepare(args.apk.resolve(), args.output.resolve(), args.notes)
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        parser.exit(1, 'Release preparation failed: ' + str(error) + '\n')
    print(json.dumps(details, ensure_ascii=False, indent=2))
    print('Release attachments:', args.output / 'update.json', args.output / 'SHA256SUMS')


if __name__ == '__main__':
    main()
