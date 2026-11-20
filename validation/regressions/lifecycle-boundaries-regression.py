#!/usr/bin/env python3
"""Signed-APK checks for single-window media and private Activity recreation."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
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
    private_descriptions = ux.resource_labels('private_isolated') | ux.resource_labels('private_shared')
    a.output.mkdir(parents=True, exist_ok=True)
    key = 'lifecycle-' + str(time.time_ns())
    base = 'http://127.0.0.1:8875/'
    checks = []
    old_night = ux.adb('shell', 'cmd', 'uimode', 'night').split(':')[-1].strip()
    old_font = ux.adb('shell', 'settings', 'get', 'system', 'font_scale')
    sdk = int(ux.adb('shell', 'getprop', 'ro.build.version.sdk'))
    installed = ux.adb('shell', 'pm', 'path', a.package).partition(':')[2].strip()
    result = {'passed': False, 'section': a.section, 'checks': checks,
              'apkSha256': ux.adb('shell', 'sha256sum', installed).split()[0]}
    private_visit = None

    def record(name, **details):
        checks.append({'check': name, **details})
        (a.output / 'result.json').write_text(json.dumps(result, indent=2), encoding="utf-8")
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
        ux.open_settings('媒体播放')
        root, _ = ux.nodes()
        enhanced_labels = ux.resource_labels('ui_enhanced_video_controls')
        enhanced = next(n for n in root.iter('node') if n.get('checkable') == 'true'
                        and any(ux.match(n, label) is not None for label in enhanced_labels))
        if enhanced.get('checked') != 'true':
            ux.tap_node(enhanced)
            root, _ = ux.nodes()
        row = next(n for n in root.iter('node') if n.get('checkable') == 'true'
                   and ux.match(n, 'Allow background media') is not None)
        if row.get('checked') != str(value).lower():
            ux.tap_node(row)

    def player():
        nonlocal key
        key = 'lifecycle-' + str(time.time_ns())
        ux.launch(base + 'player-fixture.html?case=' + key)

    def wake_and_dismiss_keyguard():
        ux.adb('shell', 'input', 'keyevent', '224')
        ux.adb('shell', 'wm', 'dismiss-keyguard')

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
            # Inline playback has no custom surface; the window-visibility opt-in must keep it
            # decoding with the screen off.
            player()
            ux.tap('Play inline')
            inline = wait(lambda s: not s['paused'] and s['currentTime'] > 0)
            ux.adb('shell', 'input', 'keyevent', '26')
            during = wait(lambda s: not s['paused'] and s['currentTime'] > inline['currentTime'] + 1)
            ux.adb('shell', 'input', 'keyevent', '26')
            time.sleep(1)
            record('Inline background playback survives screen lock',
                   before=inline['currentTime'], after=during['currentTime'])
            # A late resume must not hide a pause that happened between fixture reports.
            player()
            ux.tap('Play fullscreen')
            locked = wait(lambda s: s['fullscreen'] and s['enhanced'] and not s['paused'] and s['currentTime'] > 0)
            ux.adb('shell', 'input', 'keyevent', '223')  # KEYCODE_SLEEP
            try:
                during = wait(lambda s: s['currentTime'] >= locked['currentTime'] + 6)
                assert during['fullscreen'] and during['enhanced'] and not during['paused'], during
                assert during['pauseCount'] == locked['pauseCount'], during
            finally:
                wake_and_dismiss_keyguard()
            restored = wait(lambda s: s['fullscreen'] and s['enhanced'] and s['currentTime'] > during['currentTime'] + 1)
            assert restored['pauseCount'] == locked['pauseCount'], restored
            rows = json.load(urllib.request.urlopen(base + '__state?case=' + key, timeout=4))
            for row in rows:
                if locked['capturedAt'] <= row['capturedAt'] <= restored['capturedAt']:
                    assert row['fullscreen'] and row['enhanced'] and not row['paused'], row
                    assert row['sameElement'] and row['source'] == locked['source'], row
                    assert row['pauseCount'] == locked['pauseCount'], row
            record('Enhanced fullscreen plays through lock and wake without pause or fullscreen exit',
                   before=locked['currentTime'], locked=during['currentTime'], unlocked=restored['currentTime'])
            # System controls must still pause playback while locked.
            player()
            ux.tap('Play fullscreen')
            locked = wait(lambda s: s['fullscreen'] and not s['paused'] and s['currentTime'] > 0)
            ux.adb('shell', 'input', 'keyevent', '26')
            time.sleep(.4)
            ux.media_dispatch('pause')
            wait(lambda s: s['paused'])
            time.sleep(2)
            rows = json.load(urllib.request.urlopen(base + '__state?case=' + key, timeout=4))
            assert rows[-1]['paused'], 'Playback restarted after an explicit pause'
            ux.adb('shell', 'input', 'keyevent', '26')
            time.sleep(1)
            record('Explicit pause during screen lock is not overridden')
            # Exit fullscreen before opening the browser menu.
            for _ in range(3):
                root, _ = ux.nodes()
                if ux.match(root, '菜单') is not None:
                    break
                ux.adb('shell', 'input', 'keyevent', '4')
                time.sleep(.5)
            else:
                raise AssertionError('Browser chrome did not return after the paused lock test')
            ux.menu_item(ux._translations[0]["menu_incognito_short"])
            player()
            ux.tap('Play fullscreen')
            wait(lambda s: s['fullscreen'] and not s['paused'])
            ux.expect('Picture in picture', True)
            record('Private fullscreen keeps the picture-in-picture control')
            started = wait(lambda s: not s['paused'] and s['currentTime'] > 0)
            ux.adb('shell', 'input', 'keyevent', '3')
            after = wait(lambda s: not s['paused'] and s['currentTime'] > started['currentTime'] + 1)
            assert has_service(), 'Private background playback did not retain its service'
            record('Enabled background playback survives Home in private mode',
                   before=started['currentTime'], after=after['currentTime'])
            ux.media_dispatch('pause')
            wait(lambda s: s['paused'])
            ux.media_dispatch('play')
            wait(lambda s: not s['paused'])
            record('System media controls still drive the private video')
            ux.launch()
            # Background playback keeps the fullscreen host attached. Back exits it; more than
            # one may be needed before the browser chrome (and its menu) is reachable again.
            for _ in range(3):
                root, _ = ux.nodes()
                if ux.match(root, '菜单') is not None:
                    break
                ux.adb('shell', 'input', 'keyevent', '4')
                time.sleep(.6)
            else:
                raise AssertionError('Browser chrome did not return')
            ux.menu_item(ux._translations[0]["menu_incognito_exit_short"])
            time.sleep(1)
            assert not has_service(), 'Exiting private mode kept a media foreground service'
            record('Private session end releases background media')
            player()
            background(False)
            player()
            ux.tap('Play fullscreen')
            wait(lambda s: s['fullscreen'] and not s['paused'])
            ux.adb('shell', 'input', 'keyevent', '223')
            try:
                wait(lambda s: s['paused'])
            finally:
                wake_and_dismiss_keyguard()
            assert not has_service(), 'Disabled background playback retained the media service'
            record('Fullscreen pauses on lock with background playback disabled')
        else:
            private_page()
            ux.menu_item(ux._translations[0]["menu_incognito_short"])
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
            assert any(n.get('content-desc') in private_descriptions for n in root.iter('node'))
            record('Activity recreation retains the private session and its storage; removed window entry stays absent',
                   oldDocument=before['token'], newDocument=after['token'],
                   configuration='night mode')
            ux.menu_item(ux._translations[0]["menu_incognito_exit_short"])
            private_page()
            normal = wait(lambda s: s['token'] != after['token'] and s['ready'] == 'complete')
            assert normal['cookie'] != key and normal['local'] != key, normal
            record('Returning to normal browsing never exposes the private marker')
            ux.menu_item(ux._translations[0]["menu_incognito_short"])
            private_page()
            fresh = wait(lambda s: s['token'] != normal['token'] and s['ready'] == 'complete')
            assert fresh['cookie'] != key and fresh['local'] != key, fresh
            root, _ = ux.nodes()
            assert any(n.get('content-desc') in private_descriptions
                       for n in root.iter('node')), 'New session was not private'
            record('A new private session cannot recover the closed session marker')
            ux.menu_item(ux._translations[0]["menu_incognito_exit_short"])
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        raise
    finally:
        (a.output / 'result.json').write_text(json.dumps(result, indent=2), encoding="utf-8")
        (a.output / 'last-screen.xml').write_text(ux.nodes()[1], encoding="utf-8")
        (a.output / 'last-screen.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        ux.adb('shell', 'cmd', 'uimode', 'night', old_night)


if __name__ == '__main__':
    main()
