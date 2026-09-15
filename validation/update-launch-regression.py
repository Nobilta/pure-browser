#!/usr/bin/env python3
"""Launch-time update prompt: it must be offered once per process and survive a rebuild.

Needs a rooted AVD (the dedicated emulator images allow `adb root`). The check seeds the app's
cached release manifest, so it never downloads anything, and it restores the manifest cache, the
night-mode setting and the app process afterwards. The fake release is never downloaded or
installed: the check stops at the prompt.

Why it exists: the offer used to live in Activity state with the request in the Activity's
lifecycleScope, so a configuration change cancelled the check or dropped an unanswered offer
while the process-level claim stayed consumed — the prompt disappeared for the rest of the
process. See EMULATOR_TEST_REPORT.md.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)

PACKAGE = 'com.mybrowser'
PREFS = '/data/data/%s/shared_prefs/app_updates.xml' % PACKAGE
NEW_VERSION_CODE = 999_999
NEW_VERSION_NAME = '9.9.9'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default=PACKAGE)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.package == PACKAGE, 'This stage reads the release package data, so it only supports ' + PACKAGE
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator: this stage rewrites app prefs'
    ux.ADB = ['adb', '-s', args.serial]
    ux.PACKAGE = PACKAGE
    args.output.mkdir(parents=True, exist_ok=True)
    checks = []

    def shell(*command, tolerate_failure=False):
        try:
            return ux.adb('shell', *command)
        except subprocess.CalledProcessError:
            if tolerate_failure:
                return ''
            raise

    def record(name):
        checks.append(name)
        print('PASS:', name, flush=True)

    def prompt_present():
        root, _ = ux.nodes()
        return ux.match(root, 'App update') is not None

    def wait_for_prompt(timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if prompt_present():
                return True
            time.sleep(1)
        return False

    def process_id():
        return shell('pidof', PACKAGE, tolerate_failure=True).strip()

    def write_prefs(text, name):
        local = args.output / name
        local.write_text(text)
        ux.adb('push', str(local), '/data/local/tmp/' + name)
        owner = shell('stat', '-c', '%u:%g', '/data/data/' + PACKAGE).strip()
        shell('cp', '/data/local/tmp/' + name, PREFS)
        shell('chown', owner, PREFS)
        shell('chmod', '600', PREFS)
        shell('restorecon', PREFS, tolerate_failure=True)
        shell('rm', '-f', '/data/local/tmp/' + name)

    def activity_rebuilds():
        """Count what the window manager logged for this package since the last logcat clear."""
        # The window-manager lifecycle lines live in the event buffer, not the default one.
        log = shell('logcat', '-d', '-b', 'all', tolerate_failure=True)
        creates = sum(1 for line in log.splitlines()
                      if 'performCreate' in line and PACKAGE in line)
        destroys = sum(1 for line in log.splitlines()
                       if 'performDestroy' in line and PACKAGE in line)
        return creates, destroys

    original_night = shell('cmd', 'uimode', 'night').split(':')[-1].strip() or 'auto'
    original_prefs = None
    rooted = False
    try:
        subprocess.run(ux.ADB + ['root'], text=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=60)
        subprocess.run(ux.ADB + ['wait-for-device'], timeout=60)
        rooted = 'uid=0' in shell('id')
        assert rooted, 'This stage needs a rooted AVD (adb root); use the dedicated emulator image'
        if shell('ls', PREFS, tolerate_failure=True).strip() == PREFS:
            original_prefs = shell('cat', PREFS)

        manifest = json.dumps({
            'schemaVersion': 1, 'channel': 'stable', 'packageName': PACKAGE,
            'versionCode': NEW_VERSION_CODE, 'versionName': NEW_VERSION_NAME, 'minSdk': 29,
            'releaseNotes': 'Fixture release used by update-launch-regression.',
            'artifacts': [{'abi': 'arm64-v8a', 'assetName': 'PureBrowser-v%s-release.apk' % NEW_VERSION_NAME,
                           'size': 1024, 'sha256': '0' * 64}],
        }, separators=(',', ':'))
        write_prefs('<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>\n<map>\n'
                    '    <long name="checked_at" value="%d" />\n'
                    '    <string name="manifest">%s</string>\n</map>\n'
                    % (int(time.time() * 1000), manifest), 'app_updates.xml')

        shell('am', 'force-stop', PACKAGE)
        shell('am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        assert wait_for_prompt(), 'The launch check did not offer the cached release'
        pid_before = process_id()
        (args.output / 'before.xml').write_text(ux.nodes()[1])
        record('the process offers the update it finds in the cached manifest')

        # A configuration change rebuilds the Activity in the same process; the offer must not be
        # cancelled or dropped with it.
        shell('logcat', '-c', tolerate_failure=True)
        night = 'no' if original_night == 'yes' else 'yes'
        shell('cmd', 'uimode', 'night', night)
        deadline = time.monotonic() + 25
        creates = destroys = 0
        while time.monotonic() < deadline:
            creates, destroys = activity_rebuilds()
            if creates and destroys:
                break
            time.sleep(1)
        survived = wait_for_prompt(10)
        pid_after = process_id()
        (args.output / 'after.xml').write_text(ux.nodes()[1])
        shell('cmd', 'uimode', 'night', original_night)

        assert creates and destroys, \
            'No Activity rebuild was observed in the logs: the check did not exercise the path'
        assert pid_before and pid_before == pid_after, \
            'The process was replaced instead of rebuilt: before=%s after=%s' % (pid_before, pid_after)
        assert survived, 'The update offer disappeared after the configuration rebuild'
        record('the offer survives a configuration rebuild in the same process')
        (args.output / 'result.json').write_text(json.dumps({
            'checks': checks, 'passed': True, 'promptBefore': True, 'promptAfter': survived,
            'pidBefore': pid_before, 'pidAfter': pid_after, 'sameProcess': pid_before == pid_after,
            'fakeVersion': NEW_VERSION_NAME,
        }, indent=2))
    finally:
        if rooted:
            shell('cmd', 'uimode', 'night', original_night, tolerate_failure=True)
            if original_prefs is not None:
                write_prefs(original_prefs, 'app_updates-original.xml')
            else:
                shell('rm', '-f', PREFS, tolerate_failure=True)
        shell('am', 'force-stop', PACKAGE, tolerate_failure=True)
        (args.output / 'last-screen.png').write_bytes(
            subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))


if __name__ == '__main__':
    main()
