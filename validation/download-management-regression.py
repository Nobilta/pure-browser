#!/usr/bin/env python3
"""Exercise download confirmation, filtering, bulk actions, details and deletion on an emulator."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    key = 'manage-' + str(time.time_ns())[-10:]
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': []}

    def record(name):
        result['checks'].append(name)
        print('PASS:', name, flush=True)
        (args.output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')

    def save(name):
        (args.output / (name + '.xml')).write_text(ux.nodes()[1], encoding='utf-8')
        (args.output / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))

    def back():
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.5)

    def tap_label(resource):
        ux.tap(next(iter(ux.resource_labels(resource))))

    def set_text(value):
        ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
               'com.mybrowser.validation.FastUiDump', 'setText', base64.b64encode(value.encode()).decode())

    def row_action(name, resource):
        ux.expect(name)
        label = next(iter(ux.resource_labels(resource)))
        rows = [n for n in ux.nodes()[0].iter('node') if ux.match(n, name) is not None and ux.match(n, label) is not None]
        assert rows, 'Missing row action: ' + name + ' / ' + resource
        ux.tap_node(ux.match(rows[-1], label))

    def select(name):
        node = ux.match(ux.nodes()[0], name)
        x1, y1, x2, y2 = ux.bounds(node)
        ux.adb('shell', 'input', 'swipe', str((x1+x2)//2), str((y1+y2)//2), str((x1+x2)//2), str((y1+y2)//2), '1200')

    def start(label, name, custom=False):
        ux.launch('http://127.0.0.1:8875/download-management-fixture.html?case=' + key + '-' + name)
        ux.tap(label)
        ux.expect(next(iter(ux.resource_labels('download_confirm_title'))), timeout=10)
        field = next(n for n in ux.nodes()[0].iter('node') if ux.visible(n) and n.get('class') == 'android.widget.EditText')
        ux.tap_node(field)
        set_text(name)
        back()  # IME
        if custom:
            tap_label('download_choose_destination')
            tap_label('ui_custom_folder')
            deadline = time.monotonic() + 10
            while 'documentsui' not in ux.nodes()[1]:
                assert time.monotonic() < deadline, 'System folder chooser did not open'
                time.sleep(.2)
            root, raw = ux.nodes()
            use_folder = ux.match(root, 'Use this folder')
            if use_folder is None or use_folder.get('enabled') != 'true' or 'PureDownloadQA' not in raw:
                if ux.match(root, 'PureDownloadQA') is None:
                    ux.tap_node(ux.match(root, 'Download'))
                    ux.expect('PureDownloadQA', timeout=10)
                ux.tap_node(ux.match(ux.nodes()[0], 'PureDownloadQA'))
                time.sleep(.5)
            ux.tap_node(ux.match(ux.nodes()[0], 'Use this folder'))
            ux.tap('Allow')
            ux.expect('Download', timeout=10)
            assert 'PureDownloadQA' in ux.nodes()[1], 'The task-specific directory was not shown'
        else:
            assert 'PureDownloadQA' not in ux.nodes()[1], 'A previous task changed the default directory'
        save('confirmation-' + ('custom' if custom else name))
        tap_label('download_confirm_action')

    def downloads():
        ux.menu_item('下载')
        ux.expect('下载管理')

    def filter_tasks(resource):
        label = next(iter(ux.resource_labels(resource)))
        if ux.match(ux.nodes()[0], label) is None:
            root = ux.nodes()[0]
            chips = ux.match(root, next(iter(ux.resource_labels('download_filter_all'))))
            _, y1, _, y2 = ux.bounds(chips)
            width = ux.stable_display_bounds(root)[2]
            ux.adb('shell', 'input', 'swipe', str(width - 60), str((y1+y2)//2), '50', str((y1+y2)//2), '300')
        tap_label(resource)

    def wait_file(path):
        expected = hashlib.sha256(bytes(range(256)) * (6 * 1024 * 1024 // 256)).hexdigest()
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            value = subprocess.run(ux.ADB + ['shell', 'sha256sum', path], capture_output=True, text=True)
            if value.returncode == 0 and value.stdout.split()[0] == expected:
                return
            time.sleep(.4)
        raise AssertionError('Downloaded file mismatch: ' + path)

    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        ux.adb('shell', 'mkdir', '-p', '/sdcard/Download/PureDownloadQA')
        ux.adb('shell', 'pm', 'grant', ux.PACKAGE, 'android.permission.POST_NOTIFICATIONS')
        first = key + '.bin'
        start('Quick file', first)
        wait_file('/sdcard/Download/' + first)
        downloads()
        row_action(first, 'download_more')
        tap_label('download_details')
        ux.expect(first)
        tap_label('download_copy_source')
        save('details')
        tap_label('ui_close')
        tap_label('download_search')
        ux.adb('shell', 'input', 'keyevent', '279')  # Paste the copied source through the real IME pipeline.
        assert 'download-test.bin' in ux.nodes()[1], 'Copy source did not reach the search field'
        set_text(first)
        back()
        ux.expect(first)
        record('Edited filename produces correct bytes; details and source copy work; filename search finds the task')
        row_action(first, 'download_more')
        tap_label('ui_delete_download_record')
        save('delete-choices')
        tap_label('ui_delete_records_only')
        wait_file('/sdcard/Download/' + first)
        record('Removing only the record preserves the downloaded file')
        back()
        back()

        custom = key + '-custom.bin'
        start('Quick file', custom, custom=True)
        path = '/sdcard/Download/PureDownloadQA/' + custom
        wait_file(path)
        downloads()
        row_action(custom, 'download_more')
        tap_label('ui_delete_download_record')
        tap_label('ui_delete_records_and_files')
        deadline = time.monotonic() + 10
        while subprocess.run(ux.ADB + ['shell', 'test', '-e', path]).returncode == 0:
            assert time.monotonic() < deadline, 'Delete-file choice retained the file'
            time.sleep(.2)
        record('A task-specific SAF folder is used, and deleting record plus file removes the file')
        back()
        back()

        a, b = key + '-A.bin', key + '-B.bin'
        start('Slow file A', a)
        start('Slow file B', b)
        downloads()
        tap_label('download_search')
        set_text(key)
        back()
        ux.expect(a)
        ux.expect(b)
        select(a)
        ux.tap(b)
        tap_label('download_pause_selected')
        filter_tasks('download_filter_paused')
        ux.expect(a)
        ux.expect(b)
        save('paused-filter')
        ux.check_library_selection(a)
        select(a)
        ux.tap(b)
        tap_label('download_resume_selected')
        ux.expect(a, present=False)
        ux.expect(b, present=False)
        record('Bulk pause and resume change both tasks; status filtering and accessible selection work; resumed tasks leave the paused filter')
        save('filtered')
        filter_tasks('download_filter_active')
        row_action(a, 'download_more')
        tap_label('ui_cancel_download')
        row_action(b, 'download_more')
        tap_label('ui_cancel_download')
        result['passed'] = True
        (args.output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    except Exception:
        save('failure')
        raise
    finally:
        # Stop only this emulator app; interrupted tasks remain available for regression inspection.
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)


if __name__ == '__main__':
    main()
