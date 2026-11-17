#!/usr/bin/env python3
"""Check image-search settings and real URL handoff on a dedicated emulator.

Use --live to also send a public Wikimedia sample to the default Bing provider.
"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import subprocess
import threading
import time
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
PUBLIC_IMAGE = 'https://upload.wikimedia.org/wikipedia/commons/3/3a/Cat03.jpg'
LOCAL_IMAGE = 'http://127.0.0.1:8881/image.svg?token=a%2Bb&label=cat'
FILE_BODY = b'Pure Browser file type fixture\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--live', action='store_true')
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': []}
    lookups = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            path = urlparse(self.path)
            if path.path == '/image.svg':
                content_type = 'image/svg+xml'
                body = b'<svg xmlns="http://www.w3.org/2000/svg" width="280" height="200"><rect width="280" height="200" fill="orange"/></svg>'
            elif path.path == '/lookup':
                content_type = 'text/html'
                lookups.append(parse_qs(path.query).get('image', [None])[0])
                body = b'<title>Image lookup received</title><h1>Image lookup received</h1>'
            elif path.path.startswith('/file/'):
                content_type = 'application/octet-stream'
                body = FILE_BODY
            else:
                content_type = 'text/html'
                image_url = PUBLIC_IMAGE if path.path == '/public' else LOCAL_IMAGE
                body = ('<meta name="viewport" content="width=device-width,initial-scale=1">'
                        '<title>Image search fixture</title><h1>Image search fixture</h1>'
                        f'<img src="{image_url.replace("&", "&amp;")}" alt="Lookup sample" '
                        'style="width:280px;height:200px;border:4px solid red">').encode()
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            if path.path.startswith('/file/'):
                self.send_header('Content-Disposition', 'attachment; filename="' + path.path.rsplit('/', 1)[1] + '"')
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(('127.0.0.1', 8881), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def record(name):
        result['checks'].append(name)
        print('PASS:', name, flush=True)

    def save(name):
        root, raw = ux.nodes()
        (args.output / (name + '.xml')).write_text(raw, encoding='utf-8')
        (args.output / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        return root

    def label(resource):
        return next(iter(ux.resource_labels(resource)))

    def back():
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.4)

    def settings():
        ux.launch()
        ux.menu_item(label('menu_settings'))
        ux.tap(label('settings_general'))
        ux.tap(label('ui_search_engine'))
        ux.expect(label('search_engine_web'))

    def scroll_to(text):
        for _ in range(6):
            root, _ = ux.nodes()
            if ux.match(root, text) is not None:
                return root
            ux.swipe(root, downward=False)
        raise AssertionError('Missing setting: ' + text)

    def set_text(value):
        ux.adb('shell', 'input', 'text', value.replace(' ', '%s'))

    def context(public=False):
        ux.launch('http://127.0.0.1:8881/' + ('public' if public else 'private-image'))
        ux.expect('Lookup sample', timeout=20)
        left, top, right, bottom = ux.bounds(ux.match(ux.nodes()[0], 'Lookup sample'))
        x, y = str((left+right)//2), str((top+bottom)//2)
        ux.adb('shell', 'input', 'swipe', x, y, x, y, '1000')
        ux.expect(label('context_search_image'))

    try:
        ux.adb('reverse', 'tcp:8881', 'tcp:8881')
        ux.adb('shell', 'cmd', 'locale', 'set-app-locales', ux.PACKAGE, '--locales', 'zh-CN')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.launch()
        if args.live:
            context(public=True)
            ux.tap(label('context_search_image'))
            deadline = time.monotonic() + 50
            while True:
                _, raw = ux.nodes()
                if '虎斑猫' in raw or 'tabby' in raw.lower():
                    break
                assert time.monotonic() < deadline, 'Bing did not show recognition results'
                time.sleep(.5)
            save('bing-live-result')
            record('Default Bing identifies the public cat image in the app WebView')
        settings()
        save('web-search-settings')
        root = scroll_to('Yandex')
        ux.swipe(root, downward=False)
        root = save('image-search-settings')
        selected = [n for n in root.iter('node') if n.get('selected') == 'true' or n.get('checked') == 'true']
        assert any(ux.match(n, 'Bing') is not None for n in selected), 'Bing must be the default image engine'
        # The last add row belongs to the image group; the web group may still be partially visible.
        adds = [n for n in root.iter('node') if ux.visible(n) and n.get('clickable') == 'true'
                and ux.match(n, label('search_engine_add_custom')) is not None]
        ux.tap_node(adds[-1])
        ux.expect(label('image_search_engine_add'))
        root = ux.nodes()[0]
        fields = [n for n in root.iter('node') if n.get('class') == 'android.widget.EditText']
        assert len(fields) == 2, 'Image engine form should only have name and URL'
        ux.tap_node(fields[0]); set_text('Image QA')
        back()  # Restore the dialog geometry after the keyboard hid its lower input.
        fields = [n for n in ux.nodes()[0].iter('node') if n.get('class') == 'android.widget.EditText']
        ux.tap_node(fields[1])
        set_text('http://127.0.0.1:8881/lookup?image={imageUrl}')
        back()
        save('custom-image-dialog')
        ux.tap(label('bookmark_save'))
        scroll_to('Image QA')
        ux.tap('Image QA')
        ux.expect('Yandex')  # Selection keeps the page open.
        save('custom-image-selected')
        record('Separate image group accepts a two-field custom engine and stays open on selection')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        context()
        ux.tap(label('context_search_image'))
        ux.expect('Image lookup received', timeout=15)
        assert lookups[-1] == LOCAL_IMAGE, lookups
        save('custom-image-result')
        record('Custom image engine survives restart and receives the exact encoded image URL')
        settings()
        root = ux.nodes()[0]
        assert any(ux.match(n, label('ui_baidu')) is not None for n in root.iter('node')
                   if n.get('selected') == 'true' or n.get('checked') == 'true'), 'Image choice changed web default'
        scroll_to('Image QA')
        root = ux.nodes()[0]
        rows = [n for n in root.iter('node') if ux.match(n, 'Image QA') is not None
                and ux.match(n, label('search_engine_delete')) is not None]
        ux.tap_node(ux.match(rows[-1], label('search_engine_delete')))
        ux.expect('Image QA', present=False)
        record('Web default stays independent and deleting selected image engine returns to Bing')
        # Close both settings levels and their originating menu.
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        run_id = str(time.time_ns())[-10:]
        files = [f'icon-{kind}-{run_id}.{extension}' for kind, extension in
                 [('package', 'apk'), ('archive', 'zip'), ('photo', 'png'), ('audio', 'mp3'), ('document', 'pdf')]]
        result['files'] = files
        expected = hashlib.sha256(FILE_BODY).hexdigest()
        for name in files:
            ux.launch('http://127.0.0.1:8881/file/' + name)
            ux.expect(label('download_confirm_title'), timeout=10)
            ux.tap(label('download_confirm_action'))
            deadline = time.monotonic() + 15
            while True:
                saved = subprocess.run(ux.ADB + ['shell', 'sha256sum', '/sdcard/Download/' + name],
                                       capture_output=True, text=True)
                if saved.returncode == 0 and saved.stdout.split()[0] == expected:
                    break
                assert time.monotonic() < deadline, name
                time.sleep(.3)
        ux.menu_item('Downloads')
        ux.tap(label('download_filter_completed'))
        deadline = time.monotonic() + 15
        while True:
            root, _ = ux.nodes()
            if all(ux.match(root, name) is not None for name in files):
                break
            assert time.monotonic() < deadline, 'Files were written but downloads did not complete'
            time.sleep(.3)
        save('file-type-icons')
        record('Five file types have matching bytes and appear in the completed filter')
        result['passed'] = True
    except Exception as error:
        result['error'] = str(error)
        save('failure')
        raise
    finally:
        server.shutdown()
        result['lookups'] = lookups
        (args.output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
