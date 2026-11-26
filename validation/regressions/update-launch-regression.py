#!/usr/bin/env python3
"""Launch update offers survive Activity recreation and return in a new browser session.

Needs a rooted dedicated AVD, startup update checks enabled, and a reachable published release
newer than the installed APK. No manifest cache or fake production entry point is used. The test
stops at the offer and never downloads or installs a release. With no newer published release,
this stage cannot exercise the prompt and fails with an explicit prerequisite message.
Run through validation/run-regressions.py; see docs/testing.md.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)

PACKAGE = 'com.mybrowser'
PREFS = '/data/data/%s/shared_prefs/app_updates.xml' % PACKAGE


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
        local.write_text(text, encoding="utf-8")
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
        subprocess.run(ux.ADB + ['root'], text=True, encoding="utf-8", stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=60)
        subprocess.run(ux.ADB + ['wait-for-device'], timeout=60)
        rooted = 'uid=0' in shell('id')
        assert rooted, 'This stage needs a rooted AVD (adb root); use the dedicated emulator image'
        if shell('ls', PREFS, tolerate_failure=True).strip() == PREFS:
            original_prefs = shell('cat', PREFS)

        shell('am', 'force-stop', PACKAGE)
        write_prefs("<?xml version='1.0' encoding='utf-8'?><map />", 'app_updates.xml')

        shell('am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        assert wait_for_prompt(60), ('No startup offer: this stage requires startup checks enabled and a reachable '
                                     'published release newer than the installed APK; prompt lifecycle was not exercised')
        pid_before = process_id()
        (args.output / 'before.xml').write_text(ux.nodes()[1], encoding="utf-8")
        record('the session offers the update returned by a fresh network check')

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
        (args.output / 'after.xml').write_text(ux.nodes()[1], encoding="utf-8")
        shell('cmd', 'uimode', 'night', original_night)

        assert creates and destroys, \
            'No Activity rebuild was observed in the logs: the check did not exercise the path'
        assert pid_before and pid_before == pid_after, \
            'The process was replaced instead of rebuilt: before=%s after=%s' % (pid_before, pid_after)
        assert survived, 'The update offer disappeared after the configuration rebuild'
        record('the offer survives a configuration rebuild in the same process')
        ux.tap('Close')
        shell('cmd', 'uimode', 'night', night)
        time.sleep(2)
        assert not prompt_present(), 'An answered offer was shown again after Activity recreation'
        record('an answered offer stays dismissed across recreation')
        # The menu exit finishes the Activity without killing its process.
        ux.menu_item('Exit')
        shell('am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        assert process_id() == pid_before, 'Reopening replaced the process; session reset was not exercised'
        assert wait_for_prompt(60), 'Reopening the browser did not check and offer the release again'
        record('a new window checks again in the same process')
        (args.output / 'result.json').write_text(json.dumps({
            'checks': checks, 'passed': True, 'promptBefore': True, 'promptAfter': survived,
            'pidBefore': pid_before, 'pidAfter': pid_after, 'sameProcess': pid_before == pid_after,
        }, indent=2), encoding="utf-8")
    finally:
        shell('am', 'force-stop', PACKAGE, tolerate_failure=True)
        if rooted:
            shell('cmd', 'uimode', 'night', original_night, tolerate_failure=True)
            if original_prefs is not None:
                write_prefs(original_prefs, 'app_updates-original.xml')
            else:
                shell('rm', '-f', PREFS, tolerate_failure=True)
        (args.output / 'last-screen.png').write_bytes(
            subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))


if __name__ == '__main__':
    main()
