#!/usr/bin/env python3
"""Run signed-APK regressions serially and bind every stage to one verified artifact."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from hostencoding import ensure_utf8

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'validation/results'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    artifact = parser.add_mutually_exclusive_group(required=True)
    artifact.add_argument('--apk', type=Path, help='Signed APK whose SHA-256 must match the installed package')
    artifact.add_argument('--build-manifest', type=Path, help='JSON containing the expected sha256 of the signed APK')
    parser.add_argument('--label', default='capabilities')
    parser.add_argument('--resume', action='store_true', help='Reuse passed stages only for the same APK and emulator')
    parser.add_argument('--stages', nargs='+', help='Run named stages only; recorded in the suite manifest')
    parser.add_argument('--profile', choices=['smoke', 'tabs', 'full'], default='smoke',
                        help='smoke (default), focused tab lifecycle, or the complete release matrix; --stages overrides it')
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator: regressions seed test data'
    assert re.fullmatch(r'[a-z0-9-]+', args.label), 'Use a short filename-safe label'
    OUT.mkdir(exist_ok=True)
    adb = ['adb', '-s', args.serial]

    def device(*command):
        return subprocess.check_output(adb + list(command), text=True, encoding="utf-8", timeout=30).strip()

    def apk_hash():
        path = device('shell', 'pm', 'path', 'com.mybrowser').partition(':')[2].strip()
        return device('shell', 'sha256sum', path).split()[0]

    sdk = device('shell', 'getprop', 'ro.build.version.sdk')
    if int(sdk) < 30:
        parser.error('API 29 regression support has been removed; use the API 37 emulator.')
    expected = (hashlib.sha256(args.apk.read_bytes()).hexdigest() if args.apk
                else json.loads(args.build_manifest.read_text(encoding="utf-8"))['sha256'])
    assert apk_hash() == expected, 'Installed APK does not match the build manifest'
    prefix = 'api' + sdk + '-' + args.label
    summary = OUT / (prefix + '-suite.json')
    avd = device('emu', 'avd', 'name').splitlines()[0]
    result = {'serial': args.serial, 'sdk': sdk, 'apkSha256': expected,
              'startedAt': time.strftime('%Y-%m-%d %H:%M:%S'), 'stages': [], 'priorAttempts': []}
    if summary.exists():
        old = json.loads(summary.read_text(encoding="utf-8"))
        if args.resume:
            assert old['apkSha256'] == expected and old['device']['avd'] == avd, 'Cannot mix artifacts or devices'
            result = old
        else:
            summary.rename(OUT / (prefix + '-suite-' + str(time.time_ns()) + '.json'))
    scripts = sorted((ROOT / 'validation').glob('*.py')) + sorted((ROOT / 'validation').glob('*.java')) + sorted((ROOT / 'validation').glob('*.html'))
    test_hash = hashlib.sha256(b''.join(p.name.encode() + b'\0' + p.read_bytes() for p in scripts)).hexdigest()
    if args.resume:
        assert result.get('testSourcesSha256') == test_hash, 'Test definitions changed; start a new suite'
    result['testSourcesSha256'] = test_hash
    result['device'] = {'avd': avd, 'android': device('shell', 'getprop', 'ro.build.version.release'),
                        'webView': device('shell', 'dumpsys', 'webviewupdate')}

    # Exercise new capabilities and recent lifecycle fixes before the older feature matrix.
    upgrade = [('update-launch', 'update-launch-regression.py'),
               ('system-media', 'system-media-regression.py'),
               ('capture', 'capture-regression.py'), ('resources', 'script-resources-regression.py'),
               ('organization', 'system-upgrade-regression.py'), ('resident', 'resident-regression.py')]
    if int(sdk) >= 37:
        upgrade.insert(0, ('profiles', 'profile-cleanup-regression.py'))
    stages = [('media-lifecycle', ['media-lifecycle-regression.py', '--output', str(OUT / (prefix + '-media-lifecycle'))]),
              ('omnibar', ['omnibar-regression.py', '--output', str(OUT / (prefix + '-omnibar'))]),
              ('private-lifecycle', ['lifecycle-boundaries-regression.py', '--section', 'private', '--output', str(OUT / (prefix + '-private-lifecycle'))]),
              ('download-opening', ['download-opening-regression.py', '--output', str(OUT / (prefix + '-download-opening'))])]
    stages += [(name, [script, '--package', 'com.mybrowser', '--output', str(OUT / (prefix + '-' + name))])
               for name, script in upgrade]
    stages += [(name, ['system-integration-regression.py', '--section', name, '--package', 'com.mybrowser',
                       '--output', str(OUT / (prefix + '-' + name))]) for name in ['login']]
    stages += [('site-storage', ['site-storage-regression.py']),
              ('desktop-mode', ['desktop-mode-regression.py']),
              ('menu-navigation', ['menu-navigation-regression.py']),
              ('security', ['security-regression.py']),
              ('navigation-recovery', ['navigation-recovery-regression.py']), ('download', ['download-regression.py']),
              ('developer-tools', ['developer-tools-regression.py']), ('browser', ['emulator-ux.py', 'regress']),
              ('video-blob', ['video-regression.py', '--variant', 'blob'])]
    stages += [(part, ['capabilities-regression.py', '--section', part])
               for part in ['site', 'permissions', 'bookmarks', 'downloads', 'layout']]
    stages += [('features-' + part, ['features-regression.py', '--section', part])
               for part in ['scripts', 'imports', 'filters', 'dialogs', 'media']]
    stages += [('video-' + variant, ['video-regression.py', '--variant', variant] +
                (['--expect-enhanced'] if int(sdk) >= 34 and variant in ['cross', 'popup-cross', 'custom-cross'] else []))
               for variant in ['standard', 'custom', 'custom-blob', 'custom-cross', 'custom-csp', 'cross', 'square', 'popup', 'popup-cross']]
    stages += [('settings-back', ['settings-back-regression.py']), ('settings', ['settings-regression.py']),
               ('productivity', ['productivity-regression.py']), ('home-shortcut', ['home-shortcut-regression.py'])]
    if int(sdk) >= 33:
        stages += [('locale', ['locale-regression.py']), ('productivity-visual', ['productivity-visual-regression.py'])]
    if args.stages:
        unknown = set(args.stages) - {name for name, _ in stages}
        assert not unknown, 'Unknown regression stages: ' + ', '.join(sorted(unknown))
        stages = [(name, command) for name, command in stages if name in args.stages]
    elif args.profile != 'full':
        profiles = {
            'smoke': {'omnibar', 'resident', 'browser', 'features-dialogs'},
            'tabs': {'resident', 'media-lifecycle', 'private-lifecycle', 'menu-navigation', 'video-popup', 'video-popup-cross'},
        }
        stages = [(name, command) for name, command in stages if name in profiles[args.profile]]
    selected = [name for name, _ in stages]
    if args.resume:
        assert result.get('selectedStages', selected) == selected, 'Cannot resume with a different regression scope'
    result['selectedStages'] = selected
    result['profile'] = 'custom' if args.stages else args.profile

    def save():
        summary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding="utf-8")

    result.update(status='running', error=None)
    save()
    for name, arguments in stages:
        if any(stage['stage'] == name and stage['exitCode'] == 0 for stage in result['stages']):
            continue
        assert apk_hash() == expected, 'Installed APK changed during regression'
        for old in list(result['stages']):
            if old['stage'] == name:
                result['priorAttempts'].append(old)
                result['stages'].remove(old)
        log = OUT / (prefix + '-' + name + '-' + str(time.time_ns()) + '.log')
        command = [sys.executable, str(ROOT / 'validation' / arguments[0]), *arguments[1:]]
        if arguments[0] != 'emulator-ux.py':
            command += ['--serial', args.serial]
        result['activeStage'] = name
        save()
        print('START:', name, flush=True)
        started = time.monotonic()
        with log.open('w', encoding='utf-8') as output:
            try:
                completed = subprocess.run(
                    command, cwd=ROOT,
                    env={**os.environ, 'ANDROID_SERIAL': args.serial,
                         'PYTHONUTF8': '1', 'PYTHONIOENCODING': 'utf-8'},
                    stdout=output, stderr=subprocess.STDOUT,
                    timeout=900 if name == 'menu-navigation' else 600)
                code = completed.returncode
            except subprocess.TimeoutExpired:
                code = -1
        result['stages'].append({'stage': name, 'exitCode': code, 'apkSha256': expected,
                                 'seconds': round(time.monotonic() - started, 1), 'log': log.name})
        save()
        print(('PASS:' if code == 0 else 'FAIL:'), name, 'log=' + log.name, flush=True)
        if code != 0:
            result.update(status='failed', error=name)
            save()
            raise SystemExit(code if code > 0 else 1)
    result.pop('activeStage', None)
    result.pop('error', None)
    result.update(status='completed', completedAt=time.strftime('%Y-%m-%d %H:%M:%S'))
    save()
    print('COMPLETED:', len(result['stages']), 'stages; APK', expected, flush=True)


if __name__ == '__main__':
    ensure_utf8()
    main()
