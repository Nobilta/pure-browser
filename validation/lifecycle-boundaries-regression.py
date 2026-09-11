#!/usr/bin/env python3
"""Signed-APK checks for single-window media and private Activity recreation."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--package', default='com.mybrowser')
    p.add_argument('--section', choices=['media', 'private'], required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    assert a.serial.startswith('emulator-'), 'Use a dedicated test emulator'
    ux.ADB = ['adb', '-s', a.serial]
    ux.PACKAGE = a.package
    a.output.mkdir(parents=True, exist_ok=True)
    key = 'lifecycle-' + str(time.time_ns())
    base = 'http://127.0.0.1:8875/'
    checks = []
    old_night = ux.adb('shell', 'cmd', 'uimode', 'night').split(':')[-1].strip()
    installed = ux.adb('shell', 'pm', 'path', a.package).partition(':')[2].strip()
    result = {'passed': False, 'section': a.section, 'checks': checks,
              'apkSha256': ux.adb('shell', 'sha256sum', installed).split()[0]}
    private_visit = None

    def record(name, **details):
        checks.append({'check': name, **details})
        (a.output / 'result.json').write_text(json.dumps(result, indent=2))
        print('PASS', name, details, flush=True)

    def wait(condition, since=0):
        end = time.monotonic() + 20
        last = None
        while time.monotonic() < end:
            rows = json.load(urllib.request.urlopen(base + '__state?case=' + key, timeout=4))
            last = rows[-1] if rows else None
            if last and last['receivedAt'] > since and (a.section != 'private' or last.get('visit') == private_visit) and condition(last):
                return last
            time.sleep(.3)
        raise AssertionError(last)

    def background(value):
        ux.open_settings('视频播放')
        root, _ = ux.nodes()
        row = next(n for n in root.iter('node') if n.get('checkable') == 'true'
                   and ux.match(n, 'Allow background media') is not None)
        if row.get('checked') != str(value).lower():
            ux.tap_node(row)

    def player():
        ux.launch(base + 'player-fixture.html?case=' + key + '&visit=' + str(time.time_ns()))

    def private_page():
        nonlocal private_visit
        private_visit = str(time.time_ns())
        ux.launch(base + 'private-lifecycle-fixture.html?case=' + key + '&visit=' + private_visit)

    def has_service():
        services = ux.adb('shell', 'dumpsys', 'activity', 'services', a.package)
        return 'MediaPlaybackService' in services and 'isForeground=true' in services

    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        ux.adb('shell', 'am', 'force-stop', a.package)
        if a.section == 'media':
            player()
            background(True)
            player()
            ux.tap('Play inline')
            first = wait(lambda s: not s['paused'] and s['currentTime'] > 0)
            ux.adb('shell', 'input', 'keyevent', '3')
            after = wait(lambda s: not s['paused'] and s['currentTime'] > first['currentTime'] + 1)
            assert has_service(), 'Explicit background media did not retain its service'
            ux.launch()
            record('Enabled background playback survives Home and return', before=first['currentTime'], after=after['currentTime'])
            ux.adb('shell', 'cmd', 'media_session', 'dispatch', 'pause')
            wait(lambda s: s['paused'])
            ux.menu_item('Enter incognito mode')
            player()
            ux.tap('Play fullscreen')
            wait(lambda s: s['fullscreen'] and not s['paused'])
            ux.expect('Picture in picture', False)
            record('Private fullscreen has no picture-in-picture control')
            left = time.time()
            ux.adb('shell', 'input', 'keyevent', '3')
            wait(lambda s: s['paused'], left)
            activity = ux.adb('shell', 'dumpsys', 'activity', 'activities')
            assert 'mode=pinned' not in activity, 'Private video entered PiP'
            time.sleep(1)
            assert not has_service(), 'Private page retained a media foreground service'
            ux.adb('shell', 'cmd', 'media_session', 'dispatch', 'play')
            time.sleep(1)
            rows = json.load(urllib.request.urlopen(base + '__state?case=' + key, timeout=4))
            assert rows[-1]['paused'], rows[-1]
            record('Private Home pauses video even with background playback enabled; system Play cannot resume it')
            ux.launch()
            ux.adb('shell', 'input', 'keyevent', '4')
            ux.menu_item('Exit incognito mode')
            player()
            background(False)
            player()
        else:
            private_page()
            ux.menu_item('Enter incognito mode')
            private_page()
            wait(lambda s: s['ready'] == 'complete')
            ux.tap('Store private marker')
            before = wait(lambda s: s['cookie'] == key and s['local'] == key)
            pid = ux.adb('shell', 'pidof', a.package)
            ux.adb('shell', 'cmd', 'uimode', 'night', 'no' if old_night == 'yes' else 'yes')
            after = wait(lambda s: s['token'] != before['token'] and s['ready'] == 'complete')
            assert ux.adb('shell', 'pidof', a.package) == pid
            assert after['cookie'] == key and after['local'] == key, after
            ux.tap('Menu')
            root, _ = ux.nodes()
            assert ux.match(root, 'Open other window') is None, 'Removed window entry was present'
            ux.adb('shell', 'input', 'keyevent', '4')
            ux.expect('Private lifecycle fixture')
            root, _ = ux.nodes()
            assert any('Incognito' in n.get('text', '') or '无痕' in n.get('text', '')
                       or '無痕' in n.get('text', '') for n in root.iter('node'))
            record('Activity recreation retains the private session and its storage; removed window entry stays absent',
                   oldDocument=before['token'], newDocument=after['token'])
            ux.menu_item('Exit incognito mode')
            private_page()
            normal = wait(lambda s: s['token'] != after['token'] and s['ready'] == 'complete')
            assert normal['cookie'] != key and normal['local'] != key, normal
            record('Returning to normal browsing never exposes the private marker')
            ux.menu_item('Enter incognito mode')
            private_page()
            fresh = wait(lambda s: s['token'] != normal['token'] and s['ready'] == 'complete')
            assert fresh['cookie'] != key and fresh['local'] != key, fresh
            root, _ = ux.nodes()
            assert any('Incognito' in n.get('text', '') or '无痕' in n.get('text', '')
                       or '無痕' in n.get('text', '') for n in root.iter('node')), 'New session was not private'
            record('A new private session cannot recover the closed session marker')
            ux.menu_item('Exit incognito mode')
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        raise
    finally:
        (a.output / 'result.json').write_text(json.dumps(result, indent=2))
        (a.output / 'last-screen.xml').write_text(ux.nodes()[1])
        (a.output / 'last-screen.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        ux.adb('shell', 'cmd', 'uimode', 'night', old_night)


if __name__ == '__main__':
    main()
