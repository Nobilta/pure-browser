#!/usr/bin/env python3
"""Touch actual inline controls, then verify decoder state and exclusive UI ownership."""
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--variants', nargs='+', default=['standard', 'custom', 'blob', 'small', 'csp', 'cross'])
    args = parser.parse_args()
    assert args.serial.startswith('emulator-')
    ux.ADB = ['adb', '-s', args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': []}
    sdk = int(ux.adb('shell', 'getprop', 'ro.build.version.sdk'))
    key, variant = '', ''

    def events():
        return json.load(urllib.request.urlopen('http://127.0.0.1:8875/__state?case=' + key, timeout=4))

    def wait(condition, timeout=15):
        deadline, latest = time.monotonic() + timeout, None
        while time.monotonic() < deadline:
            rows = sorted((r for r in events() if r.get('kind') == 'player'), key=lambda r: r['capturedAt'])
            latest = rows[-1] if rows else None
            if latest and condition(latest):
                return latest
            time.sleep(.15)
        raise AssertionError(latest)

    def touch(action, fraction=.5):
        root, _ = ux.nodes()
        web = next(n for n in root.iter('node') if n.get('class') == 'android.webkit.WebView' and ux.visible(n))
        left, top, right, bottom = ux.bounds(web)
        # Read DOM geometry after the accessibility query, which can outlast a scroll.
        state = wait(lambda s: action in s['buttons'] and s['buttons'][action]['width'] > 0)
        x_offset = y_offset = 0
        viewport = state['viewport']['width']
        if variant == 'cross':
            frame = [r for r in events() if r.get('kind') == 'frame'][-1]
            x_offset, y_offset, viewport = frame['x'], frame['y'], frame['viewportWidth']
        rect = state['buttons'][action]
        scale = (right - left) / viewport
        x = int(left + (x_offset + rect['x'] + rect['width'] * fraction) * scale)
        y = int(top + (y_offset + rect['y'] + rect['height'] / 2) * scale)
        assert left <= x < right and top <= y < bottom, (action, (x, y), (left, top, right, bottom))
        ux.adb('shell', 'input', 'tap', str(x), str(y))
        time.sleep(.25)

    def record(name, state=None):
        result['checks'].append({'variant': variant, 'check': name, 'state': state})
        print('PASS', variant, name, flush=True)
        (args.output / 'result.json').write_text(json.dumps(result, indent=2))

    def page():
        nonlocal key
        key = 'inline-' + variant + '-' + str(time.time_ns())
        filename = 'inline-player-frame.html' if variant == 'cross' else 'inline-player-fixture.html'
        ux.launch('http://127.0.0.1:8875/' + filename + '?variant=' + variant + '&case=' + key)
        # Android can stop preloading at metadata until the first user gesture.
        return wait(lambda s: s['ready'] >= 1)

    def enhancement(value):
        ux.open_settings('视频播放')
        root, _ = ux.nodes()
        row = next(n for n in root.iter('node') if n.get('checkable') == 'true'
                   and ux.match(n, '增强视频控件') is not None)
        if row.get('checked') != str(value).lower():
            ux.tap_node(row)
        for _ in range(3):
            ux.adb('shell', 'input', 'keyevent', '4')
            time.sleep(.35)

    try:
        for port in (8875, 8876):
            ux.adb('reverse', 'tcp:' + str(port), 'tcp:' + str(port))
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        for variant in args.variants:
            page()
            fallback = variant == 'csp' or (variant == 'cross' and sdk < 34)
            if fallback:
                state = wait(lambda s: s['hosts'] == 0 and s['siteVisible'] and s['markers'] == 0)
                touch('site-play')
                wait(lambda s: not s['paused'] and s['position'] > .5)
                record('Unsupported source/style capability leaves a working website player', state)
                continue
            state = wait(lambda s: s['hosts'] == 1 and not s['controls'] and not s['siteVisible'])
            assert state['nodeUnchanged'] and state['sourceUnchanged']
            for axis in ('x', 'y', 'width', 'height'):
                assert abs(state['videoRect'][axis] - state['hostRect'][axis]) <= 2
            for action in ('play', 'speed', 'fullscreen', 'webControls'):
                button, picture = state['buttons'][action], state['videoRect']
                assert picture['x'] <= button['x'] and button['x'] + button['width'] <= picture['x'] + picture['width'] + 1
            record('One enhanced inline layer controls the original video; website controls are hidden', state)
            touch('play'); wait(lambda s: not s['paused'] and s['position'] > .5)
            touch('play'); wait(lambda s: s['paused'])
            touch('speed'); ux.tap('1.5×'); wait(lambda s: s['rate'] == 1.5)
            touch('seek', .7); state = wait(lambda s: s['position'] > 18 and s['paused'])
            assert state['siteClicks'] == 0 and state['sourceUnchanged'] and state['nodeUnchanged']
            record('Real play, pause, speed and seek touches affect playback exactly once')
            if variant == 'custom':
                enhancement(False)
                wait(lambda s: s['hosts'] == 0 and s['markers'] == 0 and s['siteVisible'])
                page()
                state = wait(lambda s: s['hosts'] == 0 and s['markers'] == 0 and s['siteVisible'])
                time.sleep(1)
                assert wait(lambda s: s['ready'] >= 1)['hosts'] == 0
                enhancement(True)
                wait(lambda s: s['hosts'] == 1 and not s['siteVisible'])
                record('The enhancement setting restores the live website, persists across navigation and can reattach')
            if variant not in ('cross', 'small'):
                touch('mute'); wait(lambda s: s['muted'])
                touch('mute'); wait(lambda s: not s['muted'])
                touch('dynamic'); wait(lambda s: s['hosts'] == 1 and not s['siteVisible'])
                touch('resize'); wait(lambda s: s['hosts'] == 1 and abs(s['hostRect']['width'] - s['videoRect']['width']) <= 2)
                touch('cover-open'); wait(lambda s: s['hosts'] == 0)
                touch('cover-close'); wait(lambda s: s['hosts'] == 1)
                web = next(n for n in ux.nodes()[0].iter('node') if n.get('class') == 'android.webkit.WebView' and ux.visible(n))
                left, top, right, bottom = ux.bounds(web)
                x, low, high = (left + right) // 2, int(top + (bottom - top) * .85), int(top + (bottom - top) * .12)
                ux.adb('shell', 'input', 'swipe', str(x), str(low), str(x), str(high), '450')
                wait(lambda s: s['hosts'] == 0 and s['videoRect']['y'] + s['videoRect']['height'] <= 0)
                for _ in range(3):
                    ux.adb('shell', 'input', 'swipe', str(x), str(high), str(x), str(low), '450')
                    time.sleep(.5)
                    if wait(lambda s: s['ready'] >= 1)['scrollY'] <= 1:
                        break
                wait(lambda s: s['hosts'] == 1 and s['scrollY'] <= 1)
                record('Mute, new website controls, resizing and a page dialog retain exclusive control')
                record('Scrolling off screen releases the overlay; returning restores one aligned layer')
            touch('webControls')
            state = wait(lambda s: s['hosts'] == 0 and s['markers'] == 0 and (s['controls'] if variant == 'standard' else s['siteVisible']))
            time.sleep(1.2)
            assert wait(lambda s: s['ready'] >= 1)['hosts'] == 0
            record('Explicit restore removes enhanced UI and stays with the original website player', state)
            page(); wait(lambda s: s['hosts'] == 1)
            if variant != 'cross':
                touch('stylesheet'); wait(lambda s: s['hosts'] == 0 and s['markers'] == 0)
                time.sleep(1.2); assert wait(lambda s: s['ready'] >= 1)['hosts'] == 0
                record('Deleted control stylesheet restores the website without an automatic retry loop')
                touch('replace'); wait(lambda s: s['hosts'] == 1 and s['replacements'] == 1)
                record('Replacing a video releases the old target and attaches once to the new video')
            touch('fullscreen'); wait(lambda s: s['fullscreen'] and s['hosts'] == 0)
            ux.expect('退出全屏')
            ux.adb('shell', 'input', 'keyevent', '4')
            state = wait(lambda s: not s['fullscreen'] and s['hosts'] == 1)
            record('Fullscreen hands over to native controls and returns to one inline layer', state)
            (args.output / (variant + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        (args.output / 'failure.xml').write_text(ux.nodes()[1])
        (args.output / 'failure.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        raise
    finally:
        (args.output / 'result.json').write_text(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
