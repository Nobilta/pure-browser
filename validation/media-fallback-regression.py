#!/usr/bin/env python3
"""Check native media fallback policies and attachment downloads on a local emulator."""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import importlib.util
import json
from pathlib import Path
import threading
import time
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
PORT = 8880
BASE = f'http://127.0.0.1:{PORT}'


def run(serial, package):
    assert serial.startswith('emulator-'), 'Use a dedicated local emulator'
    ux.ADB = ['adb', '-s', serial]
    ux.PACKAGE = package
    assert ux.adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    result = {'serial': serial, 'package': package, 'checks': [], 'cases': [], 'error': None,
              'androidApi': ux.adb('shell', 'getprop', 'ro.build.version.sdk'),
              'webView': ux.adb('shell', 'dumpsys', 'webviewupdate'),
              'testSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    apk = ux.adb('shell', 'pm', 'path', package).partition(':')[2].strip()
    result['apkSha256'] = ux.adb('shell', 'sha256sum', apk).split()[0]
    events = []
    samples = (ROOT / 'player-sample.mp4').read_bytes()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            parsed = urlparse(self.path)
            case = parse_qs(parsed.query).get('case', [''])[0]
            source = BASE + '/watch?case=' + case
            expected = '' if case.startswith('none-') else source
            ref = self.headers.get('Referer', '')
            events.append({'path': parsed.path, 'case': case, 'referer': ref,
                           'receivedAt': time.time()})
            if parsed.path == '/redirect.mp4':
                policy = 'origin' if case.startswith('origin-') else 'no-referrer' if case.startswith('none-') else 'unsafe-url'
                target = '/attachment.mp4' if case.startswith('redirect-attachment-') else '/landing'
                self.send_response(302)
                self.send_header('Location', target + '?case=' + case)
                self.send_header('Referrer-Policy', policy)
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            if parsed.path == '/landing':
                body = b'<h1>Fallback landing</h1>'
                kind = 'text/html'
            elif parsed.path == '/attachment.mp4':
                body = samples
                kind = 'application/octet-stream'
                self.send_response(200 if ref == expected else 403)
                self.send_header('Content-Type', kind)
                self.send_header('Content-Disposition', 'attachment; filename="fallback-' + case + '.mp4"')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            else:
                target = '/attachment.mp4' if case.startswith(('attachment-', 'none-attachment-')) else '/redirect.mp4'
                policy = 'no-referrer' if case.startswith('none-attachment-') else 'unsafe-url'
                html = ('<!doctype html><meta name="viewport" content="width=device-width">'
                        '<meta name="referrer" content="' + policy + '"><title>Fallback source</title>'
                        '<h1>Fallback source</h1><button onclick="fetch(\'' + target + '?case=' + case + '\')">Detect fallback media</button>')
                body = html.encode()
                kind = 'text/html'
            self.send_response(200)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    ux.adb('reverse', f'tcp:{PORT}', f'tcp:{PORT}')

    def record(message):
        result['checks'].append(message)
        print('PASS:', message, flush=True)

    def back():
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.4)

    try:
        for name in ('none', 'origin', 'attachment', 'none-attachment', 'redirect-attachment'):
            case = name + '-' + str(time.time_ns())
            ux.launch(BASE + '/watch?case=' + case)
            ux.expect('Fallback source')
            ux.tap('Detect fallback media')
            time.sleep(.3)
            ux.menu_item('Cast')
            ux.expect('Choose media to cast')
            start = len(events)
            labels = base64.b64encode(json.dumps(sorted(ux.resource_labels('cast_play'))).encode()).decode()
            filename = 'attachment.mp4' if name in ('attachment', 'none-attachment') else 'redirect.mp4'
            receipt = ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
                             '/system/bin', 'com.mybrowser.validation.FastUiDump', 'tapInRow', ' · ' + filename, labels)
            assert 'Tapped row action' in receipt, receipt
            if 'attachment' in name:
                ux.expect('Download file?')
                before_confirm = len(events)
                ux.tap('Download')
                deadline = time.monotonic() + 15
                while not any(e['path'] == '/attachment.mp4' and e['case'] == case for e in events[before_confirm:]):
                    assert time.monotonic() < deadline, 'Download did not reach the fixture'
                    time.sleep(.2)
                expected = '' if name == 'none-attachment' else BASE + '/watch?case=' + case
                downloads = [e for e in events[before_confirm:] if e['path'] == '/attachment.mp4' and e['case'] == case]
                assert downloads and all(e['referer'] == expected for e in downloads), downloads
                record(name + ': confirmed download retains the media request referrer')
            else:
                ux.expect('Fallback landing')
                ux.tap('Refresh')
                ux.expect('Fallback landing')
                time.sleep(.4)
                expected = '' if name == 'none' else BASE + '/'
                landings = [e for e in events[start:] if e['path'] == '/landing' and e['case'] == case]
                assert landings and all(e['referer'] == expected for e in landings), landings
                record(name + ': HTML fallback and reload retain the response policy')
            result['cases'].append({'name': name, 'requests': events[start:]})
            for _ in range(4):
                back()
                if ux.match(ux.nodes()[0], 'Fallback source') is not None:
                    break
            ux.expect('Fallback source')
    except Exception as error:
        result['error'] = repr(error)
        raise
    finally:
        result['events'] = events
        out = ROOT / 'results'
        out.mkdir(exist_ok=True)
        (out / ('media-fallback-' + serial + '.json')).write_text(json.dumps(result, indent=2), encoding='utf-8')
        server.shutdown()
        server.server_close()
        ux.adb('reverse', '--remove', f'tcp:{PORT}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser')
    args = parser.parse_args()
    run(args.serial, args.package)
