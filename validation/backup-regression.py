#!/usr/bin/env python3
"""Exercise SAF backup preview, cancellation, restore-process restart and re-export."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import time

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
    ux.ADB = ['adb', '-s', args.serial]; ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    checks = []
    def record(name):
        checks.append(name); print('PASS', name, flush=True)
        (args.output / 'result.json').write_text(json.dumps({'checks': checks, 'passed': False}, indent=2))
    def tap(key): ux.tap(labels[key], timeout=10)
    def back(): ux.adb('shell', 'input', 'keyevent', '4'); time.sleep(.6)
    def text(value):
        root, _ = ux.nodes()
        editor = next(n for n in root.iter('node') if ux.visible(n) and n.get('class') == 'android.widget.EditText')
        ux.tap_node(editor)
        ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
               'com.mybrowser.validation.FastUiDump', 'selectAll')
        ux.adb('shell', 'input', 'text', value.replace(' ', '%s'))
        time.sleep(.6)
    def open_backup():
        ux.open_settings('隐私与过滤')
        root, _ = ux.nodes()
        for _ in range(8):
            if ux.match(root, labels['backup_title']) is not None: break
            ux.adb('shell', 'input', 'swipe', '550', '1760', '550', '700', '300')
            root, _ = ux.nodes()
        tap('backup_title')

    document = {'format': 'pure-browser-backup', 'version': 1, 'createdAt': 1700000000000,
        'sections': {'BOOKMARKS': {'folders': [{'id': 100, 'parentId': 0, 'title': 'Restored QA', 'position': 0}],
            'bookmarks': [{'id': 101, 'title': 'Backup restored bookmark', 'url': 'https://backup-fixture.test/item',
                           'createdAt': 123456789, 'folderId': 100, 'position': 0}]}}}
    fixture = args.output / 'restore-fixture.json'; fixture.write_text(json.dumps(document))
    ux.adb('push', str(fixture), '/sdcard/Download/pure-restore-fixture.json')
    try:
        ux.adb('shell', 'am', 'force-stop', args.package)
        ux.launch('http://127.0.0.1:8875/capabilities-fixture.html')
        open_backup(); tap('backup_import'); ux.choose_download_document('pure-restore-fixture.json')
        ux.expect(labels['backup_preview']); ux.expect(labels['backup_merge'])
        # An explicit cancel cannot stop/restart the browser or consume the archive.
        pid = ux.adb('shell', 'pidof', args.package)
        tap('action_cancel')
        assert ux.adb('shell', 'pidof', args.package) == pid
        record('Restore preview cancellation keeps the live browsing process')
        tap('backup_import'); ux.choose_download_document('pure-restore-fixture.json')
        ux.expect(labels['backup_preview']); tap('backup_restore')
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            current = subprocess.run(ux.ADB + ['shell', 'pidof', args.package], capture_output=True, text=True).stdout.strip()
            if current and current != pid: break
            time.sleep(.5)
        assert current and current != pid, 'Restore never restarted the browser process'
        time.sleep(2)
        ux.menu_item('书签'); ux.tap('Restored QA'); ux.expect('Backup restored bookmark')
        record('Confirmed restore restarts the process and presents the restored folder and bookmark')
        back(); back(); back()
        open_backup(); tap('backup_export')
        ux.open_downloads_directory()
        name = 'pure-backup-roundtrip-' + str(time.time_ns()) + '.json'
        text(name); ux.tap('Save')
        deadline = time.monotonic() + 15
        exported = None
        while time.monotonic() < deadline:
            data = subprocess.run(ux.ADB + ['exec-out', 'cat', '/sdcard/Download/' + name], capture_output=True)
            try:
                exported = json.loads(data.stdout); break
            except (ValueError, UnicodeDecodeError): time.sleep(.4)
        assert exported and exported['format'] == 'pure-browser-backup'
        rows = exported['sections']['BOOKMARKS']['bookmarks']
        row = next(row for row in rows if row['url'] == 'https://backup-fixture.test/item')
        assert row['createdAt'] == 123456789
        folder = next(row for row in exported['sections']['BOOKMARKS']['folders'] if row['title'] == 'Restored QA')
        assert row['folderId'] == folder['id']
        assert 'history' not in exported['sections']
        record('SAF re-export preserves restored folder identity and creation time')
        # The file is a test artifact in the dedicated AVD; keep only compact evidence.
        ux.adb('shell', 'rm', '/sdcard/Download/' + name)
        (args.output / 'result.json').write_text(json.dumps({'checks': checks, 'passed': True,
            'exportedSections': list(exported['sections'])}, indent=2))
    finally:
        (args.output / 'last-screen.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))

if __name__ == '__main__': main()
