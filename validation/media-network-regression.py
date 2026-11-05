#!/usr/bin/env python3
"""Exercise selected media authentication, certificate decisions and failed seeks on an emulator."""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import ssl
import threading
import time
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
PORT, TLS_PORT = 8881, 8882
BASE = f'http://127.0.0.1:{PORT}'


def run(serial, cases):
    assert serial.startswith('emulator-')
    ux.ADB = ['adb', '-s', serial]
    assert ux.adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'serial': serial, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0],
              'webView': ux.adb('shell', 'dumpsys', 'webviewupdate'), 'checks': [], 'requests': [],
              'testSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'error': None}
    armed, failed_ranges, telemetry = set(), set(), []
    media = ROOT / 'results/large.mp4'
    assert media.exists(), 'Run create-large-media-fixtures.py first'

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass

        def do_POST(self):
            if urlparse(self.path).path != '/state': self.send_error(404); return
            count = int(self.headers.get('Content-Length', '0'))
            assert count <= 8192
            telemetry.append(json.loads(self.rfile.read(count)))
            self.send_response(204); self.end_headers()

        def do_GET(self):
            parsed = urlparse(self.path)
            case = parse_qs(parsed.query).get('case', [''])[0]
            if parsed.path == '/watch':
                body = ('<!doctype html><meta name="viewport" content="width=device-width">'
                        '<meta name="referrer" content="unsafe-url"><h1>Network media fixture</h1>'
                        '<button onclick="fetch(\'/media.mp4?case=' + case + '\')">Detect media</button>'
                        '<button onclick="location.href=\'/cookie-start?case=' + case + '\'">Native redirect</button>').encode()
                self.send_response(200); self.send_header('Content-Type', 'text/html')
                if case.startswith('cookies-'):
                    self.send_header('Set-Cookie', 'return_strict=1; SameSite=Strict; Path=/')
                    self.send_header('Set-Cookie', 'return_lax=1; SameSite=Lax; Path=/')
                self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body); return
            ref = self.headers.get('Referer', '')
            rng = self.headers.get('Range', '')
            authorization = self.headers.get('Authorization', '')
            authenticated = authorization == 'Basic ' + base64.b64encode(b'test:pure').decode()
            event = {'case': case, 'referer': ref, 'range': rng, 'authenticated': authenticated,
                     'host': self.headers.get('Host', ''),
                     'tls': self.server.server_port == TLS_PORT, 'armed': case in armed,
                     'path': parsed.path, 'cookie': self.headers.get('Cookie', ''), 'hasAuthorization': bool(authorization)}
            result['requests'].append(event)
            if parsed.path == '/cookie-start' or (case.startswith('cookies-') and parsed.path == '/media.mp4' and case in armed):
                self.send_response(302); self.send_header('Location', f'http://localhost:{PORT}/cookie-hop?case=' + case)
                self.send_header('Content-Length', '0'); self.end_headers(); return
            if parsed.path == '/cookie-hop':
                self.send_response(302); self.send_header('Location', BASE + '/cookie-final.mp4?case=' + case)
                self.send_header('Content-Length', '0'); self.end_headers(); return
            if parsed.path == '/cookie-final.mp4':
                allowed = 'return_strict=1' in event['cookie'] and 'return_lax=1' in event['cookie']
                event['status'] = 200 if allowed else 403
                body = b'<h1>Cookie returned</h1>' if allowed else b'<h1>Cookie lost</h1>'
                self.send_response(event['status']); self.send_header('Content-Type', 'text/html')
                self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body); return
            if parsed.path != '/media.mp4': self.send_error(404); return
            if case not in armed:
                self.send_response(200); self.send_header('Content-Type', 'video/mp4')
                self.send_header('Content-Length', '0'); self.end_headers(); return
            if case.startswith('tls-') and not event['tls']:
                self.send_response(302)
                self.send_header('Location', f'https://localhost:{TLS_PORT}/media.mp4?case=' + case)
                self.send_header('Referrer-Policy', 'unsafe-url')
                self.send_header('Content-Length', '0'); self.end_headers(); return
            if case.startswith('auth-redirect-') and event['host'].startswith('127.0.0.1:'):
                self.send_response(302)
                self.send_header('Location', f'http://localhost:{PORT}/media.mp4?case=' + case)
                self.send_header('Referrer-Policy', 'unsafe-url')
                self.send_header('Content-Length', '0'); self.end_headers(); return
            if case.startswith('auth-') and not authenticated:
                event['status'] = 401
                self.send_response(401); self.send_header('WWW-Authenticate', 'Basic realm="Network media"')
                self.send_header('Content-Length', '0'); self.end_headers(); return
            if ref != BASE + '/watch?case=' + case:
                event['status'] = 403
                self.send_error(403, 'Original source required'); return
            if case in failed_ranges and rng:
                event['status'] = 403
                self.send_error(403, 'Seek failed'); return
            size = media.stat().st_size
            match = re.fullmatch(r'bytes=(\d+)-(\d*)', rng)
            start, end = (int(match[1]), min(int(match[2]) if match[2] else size - 1, size - 1)) if match else (0, size - 1)
            if match: end = min(end, start + 64 * 1024 - 1)
            event['status'] = 206 if match else 200
            self.send_response(event['status']); self.send_header('Content-Type', 'video/mp4')
            self.send_header('Content-Length', str(end-start+1)); self.send_header('Cache-Control', 'no-store')
            self.send_header('Accept-Ranges', 'bytes')
            if match: self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.end_headers()
            try:
                with media.open('rb') as source:
                    source.seek(start); remaining = end-start+1
                    while remaining:
                        block = source.read(min(65536, remaining))
                        if not block: break
                        self.wfile.write(block); self.wfile.flush(); remaining -= len(block)
                        if not match: time.sleep(.01)
            except (BrokenPipeError, ConnectionResetError): pass

    servers = [ThreadingHTTPServer(('127.0.0.1', PORT), Handler)]
    if 'tls' in cases:
        tls = ThreadingHTTPServer(('127.0.0.1', TLS_PORT), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(ROOT / 'results/referrer-tls-cert.pem', ROOT / 'results/referrer-tls-key.pem')
        tls.socket = context.wrap_socket(tls.socket, server_side=True)
        servers.append(tls)
    for server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        ux.adb('reverse', f'tcp:{server.server_port}', f'tcp:{server.server_port}')

    def set_text(value, index=0):
        fields = [n for n in ux.nodes()[0].iter('node') if ux.visible(n) and n.get('class') == 'android.widget.EditText']
        ux.tap_node(fields[index])
        deadline = time.monotonic() + 4
        while True:
            fields = [n for n in ux.nodes()[0].iter('node') if ux.visible(n) and n.get('class') == 'android.widget.EditText']
            if fields[index].get('focused') == 'true': break
            assert time.monotonic() < deadline, 'Input did not receive focus'
            time.sleep(.1)
        if value in ('test', 'pure'):
            ux.adb('shell', 'input', 'text', value)
        else:
            receipt = ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
                             'com.mybrowser.validation.FastUiDump', 'setText', base64.b64encode(value.encode()).decode())
            assert 'Text entered' in receipt, receipt


    def probe(case, seek=False, expect_playing=True, reset=False):
        ux.tap('Menu'); ux.expect_menu(); time.sleep(.5)
        ux.menu_item('More tools'); ux.expect('Developer tools'); time.sleep(.4)
        ux.tap('Developer tools'); ux.expect('Console'); time.sleep(.4); ux.tap('Console')
        sample = str(time.time_ns())
        # The small encoded samples can all be buffered despite the sparse 1 GiB file.
        # Clear that buffer when testing a failed Range, so seeking must reach the server.
        script = "(()=>{const v=document.querySelector('video');" + ('v?.load();' if reset else '') + (
            'if(v&&Number.isFinite(v.duration))v.currentTime=v.duration*.85;' if seek else '') + \
            "v?.play().catch(()=>{});let n=0;const t=setInterval(()=>{fetch('/state',{method:'POST',body:JSON.stringify({sample:" + json.dumps(sample) + \
            ",video:!!v,ready:v?.readyState,playing:v?.paused===false,position:v?.currentTime,error:v?.error?.code})});if(++n>=20)clearInterval(t)},500)})()"
        set_text(script); ux.tap('Run')
        deadline = time.monotonic() + 15
        while True:
            state = next((s for s in reversed(telemetry) if s.get('sample') == sample), None)
            if state and (not expect_playing or state.get('playing') and state.get('ready', 0) >= 3 and state.get('position', 0) > .2): break
            assert time.monotonic() < deadline, {'state': state, 'requests': result['requests'][-10:]}
            time.sleep(.2)
        for _ in range(6):
            ux.adb('shell', 'input', 'keyevent', '4'); time.sleep(.3)
            if ux.match(ux.nodes()[0], 'Edit address') is not None: break
        return state

    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    try:
        prompted = set()
        for name in cases:
            case = name + '-' + str(time.time_ns())
            ux.launch(BASE + '/watch?case=' + case); ux.expect('Network media fixture')
            if name == 'cookies':
                ux.tap('Native redirect'); ux.expect('Cookie returned')
                ux.launch(BASE + '/watch?case=' + case); ux.expect('Network media fixture')
            ux.tap('Detect media'); time.sleep(.3)
            ux.menu_item('Cast'); ux.expect('Choose media to cast'); armed.add(case)
            labels = base64.b64encode(json.dumps(sorted(ux.resource_labels('cast_play'))).encode()).decode()
            receipt = ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
                             'com.mybrowser.validation.FastUiDump', 'tapInRow', ' · media.mp4', labels)
            assert 'Tapped row action' in receipt, receipt
            if name.startswith('auth') and name not in prompted:
                prompted.add(name)
                ux.expect('Authentication required'); set_text('test'); set_text('pure', 1)
                ux.adb('shell', 'input', 'keyevent', '4'); ux.tap('Sign in')
            elif name == 'tls' and name not in prompted:
                prompted.add(name)
                ux.expect('SSL certificate verification failed'); ux.tap('Continue anyway (unsafe)')
            if name == 'cookies':
                ux.expect('Cookie returned')
                hops = [e for e in result['requests'] if e['case'] == case and e['path'] == '/cookie-hop']
                assert len(hops) >= 2 and all('return_' not in e['cookie'] for e in hops), hops
                result['checks'].append({'case': name, 'nativeAndIntercepted': True})
                print('PASS:', name, flush=True)
                continue
            state = probe(case)
            if name == 'range': failed_ranges.add(case)
            state = probe(case, seek=True, expect_playing=name != 'range', reset=name == 'range')
            if name == 'range':
                assert state.get('video'), state
                deadline = time.monotonic() + 10
                while not any(e.get('status') == 403 and e['case'] == case for e in result['requests']):
                    assert time.monotonic() < deadline, 'Seek did not issue a failing request'
                    time.sleep(.2)
                ux.expect('Unable to open this page', present=False)
            else:
                requests = [e for e in result['requests'] if e['case'] == case and e['armed']]
                assert any(e['range'] and e.get('status') == 206 for e in requests), requests
                assert not any(e.get('status') == 403 for e in requests), requests
            result['checks'].append({'case': name, 'state': state})
            print('PASS:', name, flush=True)
    except Exception as error:
        result['error'] = repr(error); raise
    finally:
        (ROOT / ('results/media-network-' + serial + '.json')).write_text(json.dumps(result, indent=2))
        for server in servers:
            server.shutdown(); server.server_close()
            ux.adb('reverse', '--remove', f'tcp:{server.server_port}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--cases', nargs='+', choices=['auth', 'auth-redirect', 'tls', 'range', 'cookies'], default=['auth', 'auth', 'auth-redirect', 'tls', 'tls', 'range', 'cookies'])
    args = parser.parse_args()
    run(args.serial, args.cases)
