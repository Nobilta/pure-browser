#!/usr/bin/env python3
"""TalkBack focus traversal and activation using real swipes and double taps."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    assert a.serial.startswith('emulator-')
    ux.ADB = ['adb', '-s', a.serial]
    a.output.mkdir(parents=True, exist_ok=True)
    settings = ['enabled_accessibility_services', 'accessibility_enabled', 'touch_exploration_enabled']
    original = {k: ux.adb('shell', 'settings', 'get', 'secure', k).strip() for k in settings}
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'checks': [], 'focusSequence': [],
              'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0]}
    service = 'com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService'

    def probe(*args):
        return ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
                      '/system/bin', 'com.mybrowser.validation.FastUiDump', *args)

    def nodes():
        raw = probe('a11y')
        return ET.fromstring(raw), raw

    def snapshot(name):
        root, raw = nodes()
        (a.output / (name + '.xml')).write_text(raw)
        (a.output / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        return root

    def focus_and_activate(label):
        variants = ux.labels(label)
        for _ in range(40):
            root, _ = nodes()
            focused = next((n for n in root.iter('node') if n.get('accessibility-focused') == 'true'), None)
            texts = [] if focused is None else [v for n in focused.iter('node')
                for v in (n.get('text'), n.get('content-desc')) if v]
            if texts:
                result['focusSequence'].append(texts)
            if variants.intersection(texts):
                assert focused.get('enabled') == 'true'
                x1, y1, x2, y2 = ux.bounds(focused)
                probe('a11yDoubleTap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(.6)
                return
            _, _, width, height = ux.bounds(next(root.iter('node')))
            ux.adb('shell', 'input', 'swipe', str(int(width * .35)), str(int(height * .55)),
                   str(int(width * .70)), str(int(height * .55)), '150')
            time.sleep(.3)
        raise AssertionError('TalkBack focus never reached ' + label)

    try:
        assert ux.adb('shell', 'pm', 'path', 'com.google.android.marvin.talkback').startswith('package:')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.launch()
        ux.tap('Homepage')
        enabled = [] if original['enabled_accessibility_services'] == 'null' else original['enabled_accessibility_services'].split(':')
        ux.adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(dict.fromkeys(enabled + [service])))
        ux.adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
        time.sleep(2)
        assert ux.adb('shell', 'settings', 'get', 'secure', 'touch_exploration_enabled') == '1'
        status = ux.adb('shell', 'dumpsys', 'accessibility')
        (a.output / 'accessibility-service.txt').write_text(status)
        focus_and_activate('Menu')
        root = snapshot('menu')
        assert ux.match(root, 'Bookmarks') is not None and ux.match(root, 'Settings') is not None
        result['checks'].append('TalkBack swipes reach the labeled menu control and a double tap opens it')
        focus_and_activate('Settings')
        root = snapshot('settings')
        assert ux.match(root, '浏览与启动') is not None
        result['checks'].append('TalkBack focus reaches Settings in the menu and activates the settings page')
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.5)
        root = snapshot('returned-menu')
        assert ux.match(root, 'Bookmarks') is not None
        ux.adb('shell', 'input', 'keyevent', '4')
        result['checks'].append('Hardware Back returns to the menu and browser while TalkBack stays enabled')
        result['passed'] = True
        print(json.dumps(result, indent=2), flush=True)
    except Exception as error:
        result['error'] = repr(error)
        snapshot('failure')
        raise
    finally:
        (a.output / 'result.json').write_text(json.dumps(result, indent=2))
        for name, value in original.items():
            if value == 'null':
                ux.adb('shell', 'settings', 'delete', 'secure', name)
            else:
                ux.adb('shell', 'settings', 'put', 'secure', name, value)


if __name__ == '__main__':
    main()
