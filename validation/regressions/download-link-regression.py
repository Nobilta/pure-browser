#!/usr/bin/env python3
"""Verify expired download links, replacement validation and retained bytes on a test emulator."""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser.debug')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated test emulator'
    ux.ADB = ['adb', '-s', args.serial]
    ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    port = 8886
    origin = f'http://127.0.0.1:{port}'
    name = 'pure-link-' + str(time.time_ns()) + '.png'
    # A real image lets the existing Save image action start a normal HTTP download.
    png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=')
    content = png + bytes(range(256)) * (16 * 1024 * 1024 // 256)
    expired = False
    stopped = threading.Event()
    requests = []
    result = {'passed': False, 'checks': [], 'requests': requests}
    installed = ux.adb('shell', 'pm', 'path', args.package).partition(':')[2].strip()
    result['apkSha256'] = ux.adb('shell', 'sha256sum', installed).split()[0]

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if self.path in ('/', '/denied-fixture'):
                source = 'denied' if self.path == '/denied-fixture' else 'old'
                body = ('<!doctype html><meta name="viewport" content="width=device-width">'
                        f'<img alt="Resume fixture" src="/{source}/{name}" width="240" height="240">').encode()
                self.send_response(200)
                self.send_header('Content-Type', 'text/html')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            kind = self.path.split('/')[1]
            header = self.headers.get('Range')
            requests.append({'kind': kind, 'range': header, 'ifRange': self.headers.get('If-Range')})
            if (kind == 'old' and expired) or (kind == 'denied' and self.headers.get('Sec-Fetch-Dest') != 'image'):
                self.send_response(403)
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            if kind == 'limited' and header:
                first, last = map(int, re.fullmatch(r'bytes=(\d+)-(\d+)', header).groups())
                if last - first >= 1024 * 1024:  # Probe and retained-byte comparison succeed first.
                    self.send_response(429)
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
            first, last = 0, len(content) - 1
            if header and kind != 'no-range':
                match = re.fullmatch(r'bytes=(\d+)-(\d*)', header)
                first = int(match[1])
                last = int(match[2]) if match[2] else last
                self.send_response(206)
                self.send_header('Content-Range', f'bytes {first}-{last}/{len(content)}')
            else:
                self.send_response(200)
            self.send_header('Content-Type', 'image/png')
            self.send_header('Content-Length', str(last - first + 1))
            self.send_header('ETag', '"same-validator"')
            self.end_headers()
            if kind == 'stall' and last > first:
                stopped.wait(30)
                return
            try:
                for start in range(first, last + 1, 32768):
                    if kind == 'old' and header and last > first and start - first >= 262144:
                        while not expired and not stopped.wait(.1):
                            pass
                        break
                    body = content[start:min(start + 32768, last + 1)]
                    if kind == 'wrong' and start <= 100000 < start + len(body):
                        body = bytearray(body)
                        body[100000 - start] ^= 1
                    self.wfile.write(body)
                    self.wfile.flush()
                    if kind == 'old' and self.headers.get('Sec-Fetch-Dest') != 'image' and header != 'bytes=0-0' and stopped.wait(.06):
                        break
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def wait(condition, timeout=30):
        deadline = time.monotonic() + timeout
        while not condition():
            assert time.monotonic() < deadline, 'Condition timed out'
            time.sleep(.2)

    def record(message):
        result['checks'].append(message)
        print('PASS:', message, flush=True)

    def shot(label):
        (args.output / (label + '.xml')).write_text(ux.nodes()[1], encoding='utf-8')
        (args.output / (label + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))

    def back():
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.4)

    def tap(resource):
        label = next(iter(ux.resource_labels(resource)))
        ux.expect(label)
        ux.tap_node(ux.match(ux.nodes()[0], label))

    def row_action(resource):
        ux.expect(name)
        label = next(iter(ux.resource_labels(resource)))
        rows = [n for n in ux.nodes()[0].iter('node') if ux.match(n, name) is not None and ux.match(n, label) is not None]
        assert rows, 'Missing row action: ' + resource
        ux.tap_node(ux.match(rows[-1], label))

    def entry():
        xml = ux.adb('shell', 'run-as', args.package, 'cat', 'shared_prefs/downloads.xml')
        entries = json.loads(ET.fromstring(xml).find("string[@name='entries']").text)
        return next((item for item in entries if item['filename'] == name), None)

    def parts():
        directory = f'no_backup/browser-downloads/{entry()["id"]}'
        listing = ux.adb('shell', 'run-as', args.package, 'find', directory, '-type', 'f')
        return {path: ux.adb('shell', 'run-as', args.package, 'sha256sum', path).split()[0]
                for path in listing.splitlines()}

    def details():
        row_action('download_more')
        tap('download_details')

    def save_image(page):
        ux.launch(page)
        ux.expect('Resume fixture', timeout=15)
        image = ux.match(ux.nodes()[0], 'Resume fixture')
        x1, y1, x2, y2 = ux.bounds(image)
        ux.adb('shell', 'input', 'swipe', str((x1+x2)//2), str((y1+y2)//2), str((x1+x2)//2), str((y1+y2)//2), '1200')
        tap('context_save_image')
        tap('download_confirm_action')
        ux.menu_item('下载')
        ux.expect(name)

    def update(kind, capture=False):
        details()
        tap('download_update_link')
        if capture:
            shot('update-link-light')
        field = next(n for n in ux.nodes()[0].iter('node') if n.get('class') == 'android.widget.EditText')
        ux.tap_node(field)
        url = f'{origin}/{kind}/{name}'
        ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
               'com.mybrowser.validation.FastUiDump', 'setText', base64.b64encode(url.encode()).decode())
        assert url in ux.nodes()[1]
        if capture:
            shot('update-link-keyboard')
        back()
        tap('download_update_and_resume')

    try:
        ux.adb('reverse', f'tcp:{port}', f'tcp:{port}')
        ux.adb('shell', 'pm', 'grant', args.package, 'android.permission.POST_NOTIFICATIONS')
        save_image(origin + '/')
        wait(lambda: entry()['bytesDownloaded'] > 200000)
        row_action('download_pause')
        wait(lambda: entry()['status'] == 'PAUSED')
        task = entry()
        retained = parts()
        assert any(path.endswith('resume.properties') for path in retained), retained
        expired = True
        row_action('download_resume')
        wait(lambda: entry()['status'] == 'FAILED')
        assert entry()['failure'] == 'AUTHORIZATION'
        assert parts() == retained
        notifications = ux.adb('shell', 'dumpsys', 'notification', '--noredact')
        assert 'Download needs attention' in notifications or '下载需要处理' in notifications, notifications[-2000:]
        details()
        shot('expired-details')
        tap('ui_close')
        record('Expired address reports HTTP authorization failure and notification; every checkpoint byte survives')

        update('wrong', capture=True)
        wait(lambda: entry()['status'] == 'FAILED')
        assert entry()['failure'] == 'CANNOT_RESUME'
        assert entry()['url'] == task['url'] and parts() == retained
        record('Equal length and ETag cannot join a different file; old address and bytes remain intact')

        update('no-range')
        wait(lambda: entry()['status'] == 'FAILED')
        assert entry()['failure'] == 'CANNOT_RESUME' and parts() == retained
        record('A server ignoring Range cannot overwrite retained data')

        update('stall')
        wait(lambda: any(item['kind'] == 'stall' and item['range'] != 'bytes=0-0' for item in requests))
        before = time.monotonic()
        row_action('download_pause')
        wait(lambda: entry()['status'] == 'PAUSED', timeout=5)
        assert parts() == retained and entry()['url'] == task['url']
        record(f'Pausing a stalled verification retains the original checkpoint ({time.monotonic()-before:.1f}s including UI)')

        details()
        tap('download_update_link')
        ux.adb('shell', 'cmd', 'uimode', 'night', 'yes')
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
        ux.adb('shell', 'wm', 'size', '840x1920')
        ux.expect('Check and resume', timeout=15)
        shot('update-link-dark-320dp-200percent')
        tap('action_cancel')
        ux.adb('shell', 'wm', 'size', 'reset')
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        ux.adb('shell', 'cmd', 'uimode', 'night', 'no')
        update('limited')
        wait(lambda: entry()['status'] == 'FAILED', timeout=45)
        assert entry()['failure'] == 'SERVER'
        assert entry()['url'] == f'{origin}/limited/{name}'
        assert {path: digest for path, digest in parts().items() if not path.endswith('resume.properties')} == {
            path: digest for path, digest in retained.items() if not path.endswith('resume.properties')}
        record('HTTP 429 after replacement validation remains a server failure and preserves retained bytes')
        update('valid')
        wait(lambda: entry()['status'] == 'COMPLETED', timeout=45)
        complete = entry()
        assert complete['id'] == task['id'] and complete['filename'] == name
        digest = ux.adb('shell', 'sha256sum', '/sdcard/Download/' + name).split()[0]
        assert digest == hashlib.sha256(content).hexdigest()
        assert all(item['range'] for item in requests if item['kind'] == 'valid')
        shot('resumed-complete')
        record('Correct replacement preserves task ID/name, uses Range throughout, and produces the exact original SHA-256')
        for method in ('header', 'system-back', 'scrim', 'drag'):
            x1, y1, x2, y2 = ux.bounds(ux.match(ux.nodes()[0], name))
            ux.adb('shell', 'input', 'swipe', str((x1+x2)//2), str((y1+y2)//2),
                   str((x1+x2)//2), str((y1+y2)//2), '800')
            ux.expect('Selected: 1')
            if method == 'header':
                ux.tap_resource('cd_back')
            elif method == 'system-back':
                back()
            else:
                root, _ = ux.nodes()
                if method == 'scrim':
                    _, top, right, _ = ux.stable_display_bounds(root)
                    ux.adb('shell', 'input', 'tap', str(right//2), str(top+2))
                else:
                    _, top, right, bottom = ux.stable_display_bounds(root)
                    title = ux.match(root, 'Download manager')
                    _, header_y, _, _ = ux.bounds(title)
                    density = int(re.findall(r'(?:Physical|Override) density: (\d+)', ux.adb('shell', 'wm', 'density'))[-1]) / 160
                    # The 44dp drag strip is above the title row; the title itself does not drag.
                    handle_y = header_y - int(40 * density)
                    ux.adb('shell', 'input', 'swipe', str(right//2), str(handle_y),
                           str(right//2), str(bottom-20), '600')
                ux.expect(name, present=False)
                ux.menu_item('Downloads')
            ux.expect('Selected: 1', present=False)
            ux.expect(name)
            assert entry()['status'] == 'COMPLETED'
        record('Download Back exits selection; scrim/drag close the sheet and preserve the completed file')
        row_action('download_more')
        tap('download_delete')
        tap('ui_delete_records_and_files')
        tap('cd_delete')
        wait(lambda: entry() is None)
        save_image(origin + '/denied-fixture')
        wait(lambda: entry()['status'] == 'FAILED')
        assert entry()['failure'] == 'AUTHORIZATION' and entry()['bytesDownloaded'] == 0
        details()
        for label in ux.resource_labels('download_update_link'):
            ux.expect(label, present=False)
        shot('initial-403-details')
        tap('ui_close')
        record('An initial HTTP 403 without a checkpoint keeps the failure details and offers no link replacement')
        row_action('download_more')
        tap('download_delete')
        tap('cd_delete')
        result['passed'] = True
    except Exception:
        shot('failure')
        raise
    finally:
        (args.output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        stopped.set()
        server.shutdown()
        server.server_close()
        ux.adb('reverse', '--remove', f'tcp:{port}')
        ux.adb('shell', 'wm', 'size', 'reset')
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        ux.adb('shell', 'cmd', 'uimode', 'night', 'no')
        ux.adb('shell', 'am', 'force-stop', args.package)
        ux.adb('shell', 'rm', '-f', '/sdcard/Download/' + name)


if __name__ == '__main__':
    main()
