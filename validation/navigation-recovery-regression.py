#!/usr/bin/env python3
"""Exercise delayed headers, failed document commits and retries on the installed signed APK."""
import argparse
import http.server
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
STRINGS = ux.resource_strings("values")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated local emulator'
    ux.ADB = ['adb', '-s', args.serial]
    output = ROOT / 'results' / ('api' + ux.adb('shell', 'getprop', 'ro.build.version.sdk') + '-navigation-recovery')
    output.parent.mkdir(exist_ok=True)
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'serial': args.serial, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0],
              'webView': ux.adb('shell', 'dumpsys', 'webviewupdate'), 'checks': [], 'error': None}
    request_seen, release_response, recover = threading.Event(), threading.Event(), threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.startswith('/recover') and not recover.is_set():
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            if self.path.startswith(('/slow', '/recover')):
                request_seen.set()
                if not release_response.wait(30):
                    return
            body = ("<!doctype html><meta name='viewport' content='width=device-width'>"
                    "<h1>Pure navigation loaded</h1><a href='/slow?link'>Slow link</a>"
                    "<form action='/slow?post' method='post'><button>Slow POST</button></form>").encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_POST(self):
            self.rfile.read(int(self.headers.get('Content-Length', '0')))
            self.do_GET()

        def log_message(self, *_args):
            pass

    def record(message):
        result['checks'].append(message)
        print('PASS:', message, flush=True)

    def snapshot(name):
        path = Path(str(output) + '-' + name)
        root, raw = ux.nodes()
        path.with_suffix('.xml').write_text(raw, encoding='utf-8')
        path.with_suffix('.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        return root

    def progress_before_headers(name):
        assert request_seen.wait(10), 'Local server did not receive the navigation'
        assert not release_response.is_set(), 'Response was released before the progress assertion'
        root = snapshot(name)
        assert any(n.get('class') == 'android.widget.ProgressBar' and ux.visible(n)
                   for n in root.iter('node')), 'No progress bar while the server withheld response headers'
        assert ux.match(root, STRINGS['cd_stop']) is not None, 'Toolbar is not in the loading state'
        record(name + ': progress bar and Stop are visible before response headers')

    def loaded():
        release_response.set()
        ux.expect('Pure navigation loaded', timeout=15)
        ux.expect(STRINGS['recovery_network'], present=False)
        ux.expect(STRINGS['cd_reload'], timeout=10)

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    port = server.server_port
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = 'http://127.0.0.1:' + str(port)
    try:
        ux.adb('reverse', 'tcp:' + str(port), 'tcp:' + str(port))
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.launch(base + '/slow?address')
        progress_before_headers('address')
        loaded()
        for label, name in [('Slow link', 'link'), ('Slow POST', 'post')]:
            request_seen.clear(); release_response.clear()
            ux.tap(label)
            progress_before_headers(name)
            loaded()
        ux.launch(base + '/recover')
        ux.expect(STRINGS['recovery_network'], timeout=15)
        time.sleep(1)
        ux.expect(STRINGS['recovery_retry'])
        snapshot('failed')
        record('first failed document commit retains the built-in recovery page')
        ux.tap(STRINGS['recovery_retry'])
        time.sleep(1)
        ux.expect(STRINGS['recovery_network'])
        ux.expect(STRINGS['recovery_retry'])
        record('a repeated failure retains the built-in recovery page')
        recover.set(); request_seen.clear(); release_response.clear()
        ux.tap(STRINGS['recovery_retry'])
        progress_before_headers('retry')
        ux.expect(STRINGS['recovery_network'])
        loaded()
        record('a successful retry dismisses recovery after replacement content is available')
        request_seen.clear(); release_response.clear()
        ux.launch(base + '/slow?stop')
        progress_before_headers('before-stop')
        ux.tap(STRINGS['cd_stop'])
        ux.expect(STRINGS['cd_reload'])
        root = snapshot('stopped')
        assert not any(n.get('class') == 'android.widget.ProgressBar' and ux.visible(n)
                       for n in root.iter('node')), 'Stopped navigation retained its progress bar'
        record('Stop ends loading while response headers are still pending')
        release_response.set()
        time.sleep(.3)
        request_seen.clear(); release_response.clear()
        ux.tap('Slow POST')
        progress_before_headers('post-after-stop')
        loaded()
        record('a POST form can start loading again after Stop')
    except Exception as error:
        result['error'] = str(error)
        snapshot('failure')
        raise
    finally:
        release_response.set()
        server.shutdown(); server.server_close()
        ux.adb('reverse', '--remove', 'tcp:' + str(port))
        output.with_suffix('.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
