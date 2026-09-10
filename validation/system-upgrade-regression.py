#!/usr/bin/env python3
"""Real-touch organization, address bar, search and reading regression on a dedicated AVD."""
import argparse
import base64
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
labels = ux.resource_strings('values')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-')
    ux.ADB = ['adb', '-s', args.serial]
    ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    checks = []
    font = ux.adb('shell', 'settings', 'get', 'system', 'font_scale')
    ux.adb('reverse', 'tcp:8875', 'tcp:8875')

    def tap(key): ux.tap(labels[key], timeout=12)
    def confirm(key):
        ux.expect(labels[key])
        root, _ = ux.nodes()
        choices = [n for n in root.iter('node') if ux.visible(n) and
                   (n.get('text') in ux.labels(labels[key]) or n.get('content-desc') in ux.labels(labels[key]))]
        assert choices
        ux.tap_node(max(choices, key=lambda n: ux.bounds(n)[1]))
    def back(): ux.adb('shell', 'input', 'keyevent', '4'); time.sleep(.6)
    def text(value):
        # Real keyboard events also exercise focus when a non-focusable popup is the
        # active accessibility window. These fixture queries intentionally use ASCII.
        ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
               'com.mybrowser.validation.FastUiDump', 'selectAll')
        ux.adb('shell', 'input', 'text', value.replace(' ', '%s'))
        time.sleep(.7)
    def record(name):
        checks.append(name)
        _, xml = ux.nodes()
        (args.output / f'{len(checks):02d}.xml').write_text(xml)
        (args.output / 'result.json').write_text(json.dumps({'checks': checks, 'passed': False}, indent=2))
        print('PASS', name, flush=True)

    try:
        ux.adb('shell', 'am', 'force-stop', args.package)
        ux.launch('http://127.0.0.1:8875/capabilities-fixture.html')
        ux.menu_item('书签')
        tap('bookmarks_transfer'); tap('bookmarks_import')
        ux.adb('push', str(ROOT / 'organization-bookmarks.html'), '/sdcard/Download/organization-bookmarks.html')
        ux.choose_download_document('organization-bookmarks.html')
        confirm('bookmarks_import')
        ux.tap('QA folder', timeout=12)
        ux.expect('QA empty folder')
        ux.tap('Docs')
        ux.expect('QA nested bookmark')
        record('Netscape import preserves nested and empty folders in the visible library')
        back()
        ux.expect('QA empty folder')
        back()
        ux.expect('QA folder')
        back(); back()

        ux.open_settings('外观')
        root, _ = ux.nodes()
        # Read the switch node, not the separate text child, so repeat runs are idempotent.
        setting = next((n for n in root.iter('node') if n.get('checkable') == 'true' and
                        any(ux.match(n, label) is not None for label in ux.labels(labels['bottom_address_bar']))), None)
        assert setting is not None, 'Bottom address preference has no switch semantics'
        if setting.get('checked') != 'true': tap('bottom_address_bar')
        back(); back(); back()
        root, _ = ux.nodes()
        address = ux.match(root, labels['ui_edit_address'])
        assert address is not None
        height = max(ux.bounds(n)[3] for n in root.iter('node') if ux.visible(n))
        assert ux.bounds(address)[1] > height / 2, 'Bottom address bar stayed at the top'
        ux.tap_node(address)
        text('QA nested')
        raw = ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
                     'com.mybrowser.validation.FastUiDump', 'windows')
        root = ET.fromstring(raw)
        suggestion = ux.match(root, 'QA nested bookmark')
        assert suggestion is not None, 'Bookmark suggestion missing with IME open'
        # Suggestions use their own non-focusable popup; the editable field keeps focus.
        record('Bottom address bar opens bookmark suggestions while the input method remains usable')
        back(); back()

        ux.open_settings()
        tap('settings_search')
        text('screenshot')
        tap('private_screenshot_protection')
        ux.expect(labels['private_screenshot_summary'])
        record('Settings search opens and highlights the matching privacy setting')
        back(); back(); back()

        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
        time.sleep(1)
        ux.launch('http://127.0.0.1:8875/capabilities-fixture.html')
        ux.menu_item('阅读模式')
        ux.expect('Pure capability article')
        root, _ = ux.nodes()
        safe = ux.stable_display_bounds(root)
        for key in ('cd_back', 'reading_text_size', 'reading_copy'):
            node = ux.match(root, labels[key])
            assert node is not None
            x1, y1, x2, y2 = ux.bounds(node)
            assert y1 >= safe[1] and y2 <= safe[3], (key, ux.bounds(node), safe)
        record('Reader controls remain reachable at 200 percent system font scale')
        back()
        (args.output / 'result.json').write_text(json.dumps({'checks': checks, 'passed': True}, indent=2))
    finally:
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', font)
        (args.output / 'last-screen.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))

if __name__ == '__main__':
    main()
