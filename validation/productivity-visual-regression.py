#!/usr/bin/env python3
"""Verify translated browser surfaces, native text selection and landscape library layout."""
import argparse
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
args = parser.parse_args()
ux.ADB = ['adb', '-s', args.serial]
assert int(ux.adb('shell', 'getprop', 'ro.build.version.sdk')) >= 33
output = ROOT / 'results'
checks = []


def snapshot(name, expected):
    root, raw = ux.nodes()
    values = {value for node in root.iter('node') if ux.visible(node)
              for value in (node.get('text'), node.get('content-desc'))}
    assert all(value in values for value in expected), (name, expected)
    stem = output / ('api34-productivity-visual-' + name)
    stem.with_suffix('.xml').write_text(raw)
    png = subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'])
    stem.with_suffix('.png').write_bytes(png)
    checks.append(name)
    print('PASS:', name, flush=True)
    return struct.unpack('>II', png[16:24])


def back():
    ux.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.5)


try:
    ux.adb('reverse', 'tcp:8875', 'tcp:8875')
    for locale, folder in (('en','values'), ('zh-Hans','values-zh'), ('zh-Hant','values-b+zh+Hant')):
        ux.adb('shell', 'cmd', 'locale', 'set-app-locales', ux.PACKAGE, '--user', '0', '--locales', locale)
        time.sleep(1)
        ux.launch('http://127.0.0.1:8875/productivity-fixture.html')
        expected = ux.resource_strings(folder)
        if locale == 'en':
            root, _ = ux.nodes()
            paragraph = next(n for n in root.iter('node') if n.get('text', '').startswith('Selectable browser text'))
            x1,y1,_,_ = ux.bounds(paragraph)
            x,y = str(x1+180),str(y1+36)
            ux.adb('shell','input','swipe',x,y,x,y,'900')
            time.sleep(.7)
            # The native toolbar lives in another accessibility window. Review its
            # screenshot; the main-window dump only exposes the underlying document.
            assert 'PopupWindow' in ux.adb('shell','dumpsys','window','windows')
            snapshot('native-selection', [])
            back()
        ux.tap('Menu')
        snapshot(locale + '-menu', [expected['menu_share_page'], expected['context_copy_link']])
        back()
        root, _ = ux.nodes()
        node = next(n for n in root.iter('node') if n.get('content-desc', '').startswith(('Tabs (','标签页（','分頁（')))
        x1,y1,x2,y2 = ux.bounds(node)
        ux.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
        time.sleep(.6)
        snapshot(locale + '-tabs', [expected['tabs_search'], expected['tabs_actions']])
        back()
        for label, title, search in (('Bookmarks','bookmarks_title','bookmarks_search'), ('History','history_title','history_search')):
            ux.menu_item(label)
            snapshot(locale + '-' + search, [expected[title], expected[search]])
            back()
    ux.adb('shell','settings','put','system','accelerometer_rotation','0')
    ux.adb('shell','settings','put','system','user_rotation','1')
    time.sleep(1.5)
    ux.menu_item('Bookmarks')
    width,height = snapshot('landscape-bookmarks', [expected['bookmarks_search']])
    assert width > height, 'Landscape rotation did not take effect'
finally:
    ux.adb('shell','settings','put','system','user_rotation','0')
    ux.adb('shell','settings','put','system','accelerometer_rotation','1')
    ux.adb('shell','cmd','locale','set-app-locales',ux.PACKAGE,'--user','0')
    (output / 'api34-productivity-visual.json').write_text(json.dumps(checks, indent=2))
