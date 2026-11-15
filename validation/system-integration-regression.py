#!/usr/bin/env python3
"""Credential cancellation and private boundaries."""
import argparse, importlib.util, json, subprocess, time, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec); spec.loader.exec_module(ux)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True); p.add_argument('--package', default='com.mybrowser')
    p.add_argument('--section', choices=['login'], required=True)
    p.add_argument('--output', type=Path, required=True); a = p.parse_args()
    assert a.serial.startswith('emulator-'); ux.ADB = ['adb', '-s', a.serial]; ux.PACKAGE = a.package
    a.output.mkdir(parents=True, exist_ok=True); checks = []
    key = 'integration-' + str(time.time_ns()); base = 'http://127.0.0.1:8875/'
    def record(name, **details):
        checks.append({'check': name, **details}); print('PASS', name, details, flush=True)
    def wait(condition, since=0):
        end = time.monotonic() + 20; last = None
        while time.monotonic() < end:
            rows = json.load(urllib.request.urlopen(base + '__state?case=' + key, timeout=4)); last = rows[-1] if rows else None
            if last and last['receivedAt'] > since and condition(last): return last
            time.sleep(.3)
        raise AssertionError(last)
    def find(title):
        for _ in range(10):
            root, _ = ux.nodes()
            if ux.match(root, title) is not None: ux.tap(title); return
            ux.swipe(root, downward=True)
        raise AssertionError(title)
    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875'); ux.adb('shell', 'am', 'force-stop', a.package)
        if a.section == 'login':
            def page(): ux.launch(base.replace('127.0.0.1', 'localhost') + 'login-fixture.html?case=' + key + '&visit=' + str(time.time_ns()))
            page(); ready = wait(lambda s: s['ready'] == 'complete')
            ux.tap('Start passkey request'); result = wait(lambda s: s['outcome'] not in ['ready', 'requesting'])
            assert result['outcome'] in ['AbortError', 'NotAllowedError', 'NotSupportedError', 'SecurityError', 'TypeError'], result
            ux.expect('System login fixture')
            record('Synthetic passkey request returns a visible cancellation/provider error without leaving the page', result=result, capability=ready)
            ux.open_settings('隐私与数据'); find('Passwords and passkeys')
            ux.expect('Choose login provider'); (a.output / 'login-capabilities.xml').write_text(ux.nodes()[1], encoding="utf-8")
            ux.tap('Choose login provider')
            time.sleep(1)
            settings = ux.adb('shell', 'dumpsys', 'activity', 'activities')
            assert any('com.android.settings' in line for line in settings.splitlines() if 'ResumedActivity' in line), settings[-3000:]
            (a.output / 'provider-settings.xml').write_text(ux.nodes()[1], encoding="utf-8")
            ux.adb('shell', 'input', 'keyevent', '4')
            ux.expect('Choose login provider')
            ux.tap('Close'); page()
            record('Login provider picker opens Android settings and returns to the refreshed status dialog')
            ux.menu_item(ux._translations[0]["menu_incognito_short"]); since = time.time(); page()
            private = wait(lambda s: s['ready'] == 'complete', since)
            ux.tap('Start passkey request'); rejected = wait(lambda s: s['outcome'] not in ['ready', 'requesting'], since)
            assert rejected['outcome'] != 'unexpected-credential'
            record('Private page credential request is unavailable or rejected', result=rejected, capability=private)
            ux.menu_item(ux._translations[0]["menu_incognito_exit_short"]); page()
        installed = ux.adb('shell', 'pm', 'path', a.package).partition(':')[2].strip()
        (a.output / 'result.json').write_text(json.dumps({'passed': True, 'checks': checks,
            'apkSha256': ux.adb('shell', 'sha256sum', installed).split()[0],
            'notTested': ['Real credential/account sign-in']}, indent=2), encoding="utf-8")
    finally:
        (a.output / 'last-screen.xml').write_text(ux.nodes()[1], encoding="utf-8")
        (a.output / 'last-screen.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))

if __name__ == '__main__': main()
