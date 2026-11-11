#!/usr/bin/env python3
"""Exercise real WebView translation against a local LibreTranslate stub (debug emulator only)."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import threading
import time
from urllib.parse import urlparse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec); spec.loader.exec_module(ux)
PORT = 8880


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser.debug')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]; ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    reports = {}; requests = []; delay = 0
    base = f'http://127.0.0.1:{PORT}'

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def reply(self, body, kind):
            self.send_response(200); self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(body))); self.end_headers()
            try: self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError): pass
        def do_GET(self):
            self.reply((ROOT / 'translation-fixture.html').read_bytes(), 'text/html; charset=utf-8')
        def do_POST(self):
            data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if urlparse(self.path).path == '/translate':
                request = {'text': data['q'], 'started': time.time(), 'finished': False}
                requests.append(request)
                time.sleep(delay)
                self.reply(json.dumps({'translatedText': '译文: ' + data['q']}).encode(), 'application/json')
                request['finished'] = True
            else:
                reports[data['page']] = data
                self.send_response(204); self.end_headers()

    server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    prefs = 'shared_prefs/translation_settings.xml'
    saved = subprocess.run(ux.ADB + ['exec-out', 'run-as', args.package, 'cat', prefs], capture_output=True)
    original = saved.stdout if saved.returncode == 0 else None
    checks = []
    result = {'passed': False, 'checks': checks, 'requests': requests}
    installed = ux.adb('shell', 'pm', 'path', args.package).partition(':')[2].strip()
    result['apkSha256'] = ux.adb('shell', 'sha256sum', installed).split()[0]

    def write_prefs(data):
        command = 'run-as ' + shlex.quote(args.package) + ' sh -c ' + shlex.quote('cat > ' + prefs)
        subprocess.run(ux.ADB + ['shell', command], input=data, check=True)

    def wait(condition, timeout=25):
        deadline = time.monotonic() + timeout
        while not condition():
            assert time.monotonic() < deadline, json.dumps(reports, ensure_ascii=False)
            time.sleep(.2)

    def translated(page, field, text):
        state = reports.get(page, {})
        return (state.get(field, '').startswith('译文: ') and text in state[field]) or any(
            '译文: ' in item and text in item for item in state.get('comparison', []))

    def record(name):
        checks.append(name); print('PASS:', name, flush=True)

    def page_control(page, label):
        # Fixed elements can retain stale accessibility bounds after browser chrome animates.
        # Use the fixture's current DOM geometry and inject a real touch inside the WebView.
        x1, y1, x2, y2 = ux.bounds(ux.web_view())
        state = reports[page]; rect = state['controls'][label]
        scale = (x2 - x1) / state['viewportWidth']
        x = x1 + (rect['x'] + rect['width'] / 2) * scale
        y = y1 + (rect['y'] + rect['height'] / 2) * scale
        assert x1 < x < x2 and y1 < y < y2
        ux.adb('shell', 'input', 'tap', str(round(x)), str(round(y)))

    def start():
        ux.tap(ux._translations[0]['cd_menu'])
        ux.expect_menu()
        ux.tap(ux._translations[0]['menu_translate'])
        ux.tap(ux._translations[0]['translate_page'])

    def restore():
        root, _ = ux.nodes()
        stop = ux._translations[0]['translate_stop']
        ux.tap(stop if ux.match(root, stop) is not None else ux._translations[0]['translate_restore'])

    try:
        ux.adb('reverse', f'tcp:{PORT}', f'tcp:{PORT}')
        for mode in ['REPLACE', 'COMPARE']:
            ux.adb('shell', 'am', 'force-stop', args.package)
            values = ET.Element('map')
            for key, value in {'provider':'LIBRETRANSLATE', 'libre_endpoint':base, 'target':'zh-CN',
                               'source':'en', 'page_mode':mode}.items():
                ET.SubElement(values, 'string', name=key).text = value
            write_prefs(ET.tostring(values, encoding='utf-8', xml_declaration=True))
            page = mode + '-A'; other = mode + '-B'
            ux.launch(base + '/page?page=' + page)
            wait(lambda: reports.get(page, {}).get('ready') == 'complete')
            start(); wait(lambda: translated(page, 'first', 'first paragraph'))
            if mode == 'COMPARE': assert not reports[page]['first'].startswith('译文: ')
            record(mode + ': initial viewport translates with the expected DOM mode')
            page_control(page, 'Add paragraph'); wait(lambda: translated(page, 'dynamic', 'newly inserted'))
            page_control(page, 'Change paragraph'); wait(lambda: translated(page, 'first', 'has changed'))
            record(mode + ': inserted and changed paragraphs translate automatically')
            page_control(page, 'Scroll down'); wait(lambda: translated(page, 'later', 'last paragraph'))
            page_control(page, 'Scroll top')
            wait(lambda: reports[page]['scroll'] < 1)
            record(mode + ': scrolling schedules offscreen paragraphs')
            restore(); wait(lambda: not reports[page]['first'].startswith('译文: ') and not reports[page]['comparison'])
            record(mode + ': restoring removes translated content')
            page_control(page, 'Scroll top'); wait(lambda: reports[page]['scroll'] < 1)

            delay = 3
            previous = len(requests); start(); wait(lambda: len(requests) > previous)
            pending = requests[previous]
            ux.adb('shell', 'input', 'keycombination', '113', '48')  # Ctrl+T
            ux.launch(base + '/page?page=' + other)
            wait(lambda: reports.get(other, {}).get('ready') == 'complete' and pending['finished'])
            assert not reports[other]['first'].startswith('译文: ') and not reports[other]['comparison']
            delay = 0
            ux.adb('shell', 'input', 'keycombination', '113', '51')  # Ctrl+W returns to its opener/recent tab.
            wait(lambda: translated(page, 'first', 'has changed'))
            record(mode + ': switching during a slow request leaves the other tab intact and resumes the source')
            old_token = reports[page]['token']
            page_control(page, 'Next document')
            wait(lambda: reports[page]['token'] != old_token and reports[page]['ready'] == 'complete')
            assert not reports[page]['first'].startswith('译文: ') and not reports[page]['comparison']
            record(mode + ': navigation starts a document without the previous translation')
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        raise
    finally:
        try:
            (args.output / 'last-screen.xml').write_text(ux.nodes()[1], encoding='utf-8')
            (args.output / 'last-screen.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        finally:
            ux.adb('shell', 'am', 'force-stop', args.package)
            if original is None: ux.adb('shell', 'run-as', args.package, 'rm', '-f', prefs)
            else: write_prefs(original)
            server.shutdown(); server.server_close()
            (args.output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__': main()
