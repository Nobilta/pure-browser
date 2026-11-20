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


def verified_signer(apk):
    """Verify the whole APK before trusting the certificate printed by apksigner."""
    certificate = run(android_tool('apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in certificate:
        raise ValueError('The delivery APK must have a valid v2 signature')
    signers = {value.lower() for value in re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', certificate)}
    if len(signers) != 1 or not re.search(r'^Number of signers: 1$', certificate, re.M):
        raise ValueError('Expected one release signing certificate')
    return next(iter(signers))


def compose_notes(version, notes_file=None):
    """Local builds need no release prose; public releases supply their reviewed change list."""
    heading = 'Pure Browser ' + version + '\n'
    if notes_file is None:
        return heading
    body = notes_file.read_text(encoding='utf-8').strip()
    if not body:
        raise ValueError('The public release notes file is empty')
    return heading + '\n' + body + '\n'


def prepare(apk, output, notes_file=None):
    if not apk.is_file() or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,150}\.apk', apk.name):
        raise ValueError('Choose the signed delivery APK with a filename safe for release assets')
    size = apk.stat().st_size
    if not 0 < size <= 128 * 1024 * 1024:
        raise ValueError('APK exceeds the updater size limit')
    signer = verified_signer(apk)
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
    release_notes = compose_notes(version, notes_file)
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
    details = {'apk': str(apk), 'sha256': checksum, 'signerSha256': signer, 'tag': 'v' + version,
               'size': size, 'versionCode': code, 'versionName': version, 'minSdk': min_sdk, 'abi': 'arm64-v8a',
               'releaseNotes': str(notes_path)}
    (output / 'package-info.json').write_text(json.dumps(details, indent=2) + '\n', encoding='utf-8')
    return details


def verify_prepared(apk, output):
    """Ensure the mirror metadata still describes the exact APK being uploaded."""
    apk = apk.resolve()
    output = output.resolve()
    info = json.loads((output / 'package-info.json').read_text(encoding='utf-8'))
    manifest = json.loads((output / 'update.json').read_text(encoding='utf-8'))
    if Path(info['apk']).resolve() != apk:
        raise ValueError('package-info.json points to a different APK')
    if info['abi'] != 'arm64-v8a' or info['tag'] != 'v' + info['versionName']:
        raise ValueError('package-info.json has inconsistent release metadata')

    signer = verified_signer(apk)
    badging = run(android_tool('aapt2'), 'dump', 'badging', str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    if not package or package.groups() != ('com.mybrowser', str(info['versionCode']), info['versionName']):
        raise ValueError('APK package version differs from package-info.json')
    if not sdk or int(sdk[1]) != info['minSdk']:
        raise ValueError('APK minimum SDK differs from package-info.json')
    apk_bytes = apk.read_bytes()
    apk_hash = hashlib.sha256(apk_bytes).hexdigest()
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
    if abis != {info['abi']}:
        raise ValueError('APK ABI differs from package-info.json')
    if signer != info['signerSha256']:
        raise ValueError('APK signing certificate differs from package-info.json')

    artifacts = manifest.get('artifacts', [])
    if (manifest.get('packageName') != 'com.mybrowser' or
            manifest.get('versionCode') != info['versionCode'] or
            manifest.get('versionName') != info['versionName'] or
            len(artifacts) != 1 or artifacts[0].get('abi') != info['abi'] or
            artifacts[0].get('assetName') != apk.name or
            artifacts[0].get('size') != apk.stat().st_size or
            artifacts[0].get('sha256') != apk_hash or manifest.get('minSdk') != info['minSdk']):
        raise ValueError('update.json does not describe the prepared APK')

    expected = {
        apk.name: apk_hash,
        'update.json': hashlib.sha256((output / 'update.json').read_bytes()).hexdigest(),
    }
    checksums = {}
    for line in (output / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        checksum, separator, name = line.partition('  ')
        if not separator or name in checksums:
            raise ValueError('SHA256SUMS is malformed')
        checksums[name] = checksum
    if checksums != expected:
        raise ValueError('SHA256SUMS does not match the APK and update.json')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'outputs/release')
    parser.add_argument('--release-notes', type=Path,
                        help='public change list without a version heading; omit for a local test build')
    parser.add_argument('--verify-prepared', action='store_true',
                        help='verify existing mirror attachments against the signed APK')
    args = parser.parse_args()
    try:
        if args.verify_prepared:
            verify_prepared(args.apk, args.output)
            print('Prepared release attachments match the signed APK')
            return
        details = prepare(args.apk.resolve(), args.output.resolve(), args.release_notes)
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        parser.exit(1, 'Release preparation failed: ' + str(error) + '\n')
    print(json.dumps(details, ensure_ascii=False, indent=2))
    print('Release attachments:', args.output / 'update.json', args.output / 'SHA256SUMS')


if __name__ == '__main__':
    main()
