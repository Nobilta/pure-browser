#!/usr/bin/env python3
"""Touch the real soft keyboard while address suggestions and editor actions are visible."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    installed = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'apkSha256': ux.adb('shell', 'sha256sum', installed).split()[0], 'checks': []}
    original = {name: ux.adb('shell', 'settings', 'get', 'system', name)
                for name in ('accelerometer_rotation', 'user_rotation')}
    old_ime = ux.adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard')

    def windows():
        return ux.window_nodes()

    def wait(condition, timeout=12):
        deadline = time.monotonic() + timeout
        while True:
            root, _ = windows()
            value = condition(root)
            if value is not None and value is not False:
                return value
            assert time.monotonic() < deadline, 'Editor/keyboard condition not met'
            time.sleep(.2)

    def editor(root):
        return next((n for n in root.iter('node') if n.get('class') == 'android.widget.EditText'
                     and n.get('focused') == 'true' and ux.visible(n)), None)

    def expect_text(text):
        return wait(lambda root: (n := editor(root)) is not None and n.get('text') == text and n)

    def soft_key(label):
        node = wait(lambda root: next((n for n in root.iter('node') if ux.visible(n)
                    and n.get('clickable') == 'true' and n.get('content-desc', '').lower() == label.lower()
                    and 'inputmethod' in n.get('resource-id', '')), None))
        ux.tap_node(node)

    def set_text(text):
        raw = ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
                     '/system/bin', 'com.mybrowser.validation.FastUiDump', 'setText',
                     base64.b64encode(text.encode()).decode())
        assert 'Text entered' in raw
        expect_text(text)

    def clear():
        node = wait(lambda root: ux.match(root, 'Clear'))
        ux.tap_node(node)
        expect_text('')

    def suggestion(root):
        candidates = []
        for n in root.iter('node'):
            if not ux.visible(n) or n.get('clickable') != 'true':
                continue
            descendants = list(n.iter('node'))
            fill = [child for child in descendants if child.get('content-desc', '').startswith(
                ('Fill address field:', '补全到输入框：', '補全至輸入欄：'))]
            urls = [child.get('text') for child in descendants if child.get('class') == 'android.widget.TextView'
                    and child.get('text', '').startswith('http://127.0.0.1:8875/browser-ux.html')]
            if len(fill) == len(urls) == 1:
                candidates.append((len(descendants), n, fill[0], urls[0]))
        return min(candidates, key=lambda item: item[0])[1:] if candidates else None

    def snapshot(name):
        _, raw = windows()
        (args.output / (name + '.xml')).write_text(raw)
        (args.output / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))

    def record(name):
        result['checks'].append(name)
        print('PASS', name, flush=True)
        (args.output / 'result.json').write_text(json.dumps(result, indent=2))

    def position(bottom):
        ux.open_settings('Appearance')
        root, _ = ux.nodes()
        row = next(n for n in root.iter('node') if n.get('checkable') == 'true'
                   and ux.match(n, 'Address bar at bottom') is not None)
        previous = row.get('checked') == 'true'
        if previous != bottom:
            ux.tap_node(row)
        return previous

    old_position = None
    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        ux.adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
        ux.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        ux.adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
        ux.launch('http://127.0.0.1:8875/browser-ux.html?case=omnibar-setup')
        for bottom in (False, True):
            previous = position(bottom)
            if old_position is None:
                old_position = previous
            label = 'bottom' if bottom else 'top'
            url = 'http://127.0.0.1:8875/browser-ux.html?case=omnibar-' + label + '-' + str(time.time_ns())
            ux.launch(url)
            ux.expect('Pure UX First Page')
            ux.tap('Edit address')
            expect_text(url)
            for index, letter in enumerate('qwe'):
                soft_key(letter)
                expect_text('qwe'[:index + 1])
            count = len(re.findall(r'Window #\d+ Window\{[^}\n]*com\.mybrowser/com\.mybrowser\.MainActivity',
                                  ux.adb('shell', 'dumpsys', 'window', 'windows')))
            assert count == 1, 'Suggestions created a second application window'
            snapshot(label + '-typing')
            record(label + ': real soft-key taps keep editor focus and commit each character with suggestions visible')
            clear()
            soft_key('a')
            expect_text('a')
            soft_key('Delete')
            expect_text('')
            record(label + ': clear and keyboard deletion keep the same editor active')

            set_text(url)
            row, fill, expected = wait(suggestion)
            ux.tap_node(fill)
            expect_text(expected)
            soft_key('z')
            expect_text(expected + 'z')
            clear()
            set_text(url)
            row, _, expected = wait(suggestion)
            ux.tap_node(row)
            ux.expect('Edit address')
            ux.tap('Edit address')
            expect_text(expected)
            record(label + ': suggestion fill retains the caret; selecting its row navigates and ends editing')

            clear()
            set_text('rotationdraft')
            ux.adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
            time.sleep(1.2)
            expect_text('rotationdraft')
            soft_key('x')
            expect_text('rotationdraftx')
            snapshot(label + '-landscape')
            ux.adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
            time.sleep(1.2)
            expect_text('rotationdraftx')
            record(label + ': rotation with the IME open preserves draft and continued typing')

            target = url + '-go'
            set_text(target)
            soft_key('Go')
            ux.expect('Edit address')
            ux.tap('Edit address')
            expect_text(target)
            clear()
            # Tapping blank page space dismisses editing without navigating the page.
            root, _ = windows()
            # While editing, the same-window page overlay owns taps and the
            # covered WebView is intentionally absent from accessibility.
            candidates = [n for n in root.iter('node') if ux.visible(n)
                          and n.get('class') == 'android.view.View' and n.get('clickable') == 'true'
                          and not any(c.get('text') or c.get('content-desc') for c in n.iter('node'))]
            area = lambda n: (ux.bounds(n)[2] - ux.bounds(n)[0]) * (ux.bounds(n)[3] - ux.bounds(n)[1])
            assert candidates, 'Page editing overlay is missing'
            ux.tap_node(max(candidates, key=area))
            ux.expect('Edit address')
            record(label + ': the real IME Go key navigates; an outside page tap cancels editing')
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        snapshot('failure')
        raise
    finally:
        if result['passed'] and old_position is not None:
            position(old_position)
            ux.launch('http://127.0.0.1:8875/browser-ux.html?case=omnibar-complete')
        for name, value in original.items():
            ux.adb('shell', 'settings', 'delete' if value == 'null' else 'put', 'system', name,
                   *([] if value == 'null' else [value]))
        ux.adb('shell', 'settings', 'delete' if old_ime == 'null' else 'put', 'secure',
               'show_ime_with_hard_keyboard', *([] if old_ime == 'null' else [old_ime]))
        (args.output / 'result.json').write_text(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
