#!/usr/bin/env python3
"""Check reader controls at 200% font size and authenticated image sharing on an emulator."""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': [], 'images': []}
    font = ux.adb('shell', 'settings', 'get', 'system', 'font_scale').strip()
    locale = ux.adb('shell', 'cmd', 'locale', 'get-app-locales', ux.PACKAGE).split('[')[1].split(']')[0]
    size = ux.adb('shell', 'wm', 'size')
    original_size = size.split('Override size: ')[1].strip() if 'Override size:' in size else 'reset'
    private = False

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.path.startswith('/image.png'):
                cookie = self.headers.get('Cookie', '')
                allowed = 'share_session=ok' in cookie
                result['images'].append({'cookie': cookie, 'allowed': allowed})
                self.send_response(200 if allowed else 403)
                self.send_header('Content-Type', 'image/png')
                self.end_headers()
                self.wfile.write(PNG if allowed else b'Login required')
            else:
                body = (b'<meta name="viewport" content="width=device-width,initial-scale=1">'
                        b'<title>Image sharing fixture</title><h1>Authenticated image</h1>'
                        b'<img src="/image.png" alt="Protected image" style="width:280px;height:200px;border:4px solid red">')
                self.send_response(200)
                self.send_header('Content-Type', 'text/html')
                self.send_header('Set-Cookie', 'share_session=ok; Path=/')
                self.end_headers()
                self.wfile.write(body)

    server = ThreadingHTTPServer(('127.0.0.1', 8879), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def record(name):
        result['checks'].append(name)
        print('PASS:', name, flush=True)

    def save(name):
        root, raw = ux.nodes()
        (args.output / (name + '.xml')).write_text(raw, encoding='utf-8')
        (args.output / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        return root

    def back():
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.4)

    try:
        for port in (8875, 8879):
            ux.adb('reverse', 'tcp:' + str(port), 'tcp:' + str(port))
        ux.launch()
        private_descriptions = ux.resource_labels('private_isolated') | ux.resource_labels('private_shared')
        if any(n.get('content-desc') in private_descriptions for n in ux.nodes()[0].iter('node')):
            ux.menu_item(ux._translations[0]['menu_incognito_exit_short'])
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        for private_mode in (False, True):
            if private_mode:
                ux.menu_item(ux._translations[0]['menu_incognito_short'])
                private = True
            before = len(result['images'])
            ux.launch('http://127.0.0.1:8879/?private=' + str(private_mode))
            ux.expect('Protected image', timeout=15)
            image = ux.match(ux.nodes()[0], 'Protected image')
            x1, y1, x2, y2 = ux.bounds(image)
            ux.adb('shell', 'input', 'swipe', str((x1+x2)//2), str((y1+y2)//2),
                   str((x1+x2)//2), str((y1+y2)//2), '1000')
            ux.expect('Share image')
            search = ux.match(ux.nodes()[0], next(iter(ux.resource_labels('context_search_image'))))
            assert (search is not None) == (not private_mode), 'Search by image must be offered only in normal mode'
            record(('Private hides' if private_mode else 'Normal shows') + ' search by image')
            save('context-' + ('private' if private_mode else 'normal'))
            ux.tap('Share image')
            deadline = time.monotonic() + 15
            while not any('ResumedActivity' in line and 'ChooserActivity' in line
                          for line in ux.adb('shell', 'dumpsys', 'activity', 'activities').splitlines()):
                assert time.monotonic() < deadline, 'Image share chooser did not open'
                time.sleep(.3)
            requests = result['images'][before:]
            assert len(requests) >= 2 and all(r['allowed'] for r in requests), requests
            save('share-' + ('private' if private_mode else 'normal'))
            record(('Private' if private_mode else 'Normal') + ' image sharing carries the active cookie and opens the chooser')
            back()
        ux.menu_item(ux._translations[0]['menu_incognito_exit_short'])
        private = False
        density = int(ux.adb('shell', 'wm', 'density').strip().splitlines()[-1].split(':')[1])
        width, height = round(320 * density / 160), round(720 * density / 160)
        ux.adb('shell', 'wm', 'size', str(width) + 'x' + str(height))
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
        for language in ('en', 'zh-CN', 'zh-Hant'):
            ux.adb('shell', 'cmd', 'locale', 'set-app-locales', ux.PACKAGE, '--locales', language)
            ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
            ux.launch('http://127.0.0.1:8875/reader-fixture.html?language=' + language)
            ux.expect('Reader fixture', timeout=15)
            ux.menu_item('Reader mode')
            root = save('reader-' + language)
            descriptions = ux.resource_labels('reader_font_smaller') | ux.resource_labels('reader_font_larger')
            controls = [n for n in root.iter('node') if n.get('clickable') == 'true'
                        and any(c.get('content-desc') in descriptions for c in n.iter('node'))]
            assert len(controls) == 2, 'Missing font controls'
            parent = next(n for n in root.iter('node') if all(c in list(n) for c in controls))
            buttons = [n for n in parent if n.get('clickable') == 'true']
            assert len(buttons) == 4, 'Missing reader controls'
            for button in buttons:
                left, top, right, bottom = ux.bounds(button)
                assert 0 <= left < right <= width and 0 <= top < bottom <= height
                assert bottom - top >= 48 * density / 160, 'Reader touch target below 48dp'
                assert any(n.get('text') and '…' not in n.get('text') for n in button.iter('node'))
            messages = [n for n in root.iter('node') if n.get('text') in ux.resource_labels('reader_entered')]
            for message in messages:
                assert ux.bounds(message)[3] <= min(ux.bounds(n)[1] for n in buttons), 'Snackbar overlaps reader controls'
            record(language + ': all reader controls fit at 320dp and 200% font size')
        result['passed'] = True
    finally:
        if private:
            back()
            ux.menu_item(ux._translations[0]['menu_incognito_exit_short'])
        if font == 'null':
            ux.adb('shell', 'settings', 'delete', 'system', 'font_scale')
        else:
            ux.adb('shell', 'settings', 'put', 'system', 'font_scale', font)
        ux.adb('shell', 'wm', 'size', original_size)
        command = ['shell', 'cmd', 'locale', 'set-app-locales', ux.PACKAGE]
        if locale:
            command += ['--locales', locale]
        ux.adb(*command)
        server.shutdown()
        server.server_close()
        (args.output / 'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')


if __name__ == '__main__':
    main()
