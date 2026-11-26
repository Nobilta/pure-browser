#!/usr/bin/env python3
"""Check local SAF isolation from remote documents/frames and local tab titles on a debug emulator."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import ssl
import subprocess
import threading
import time
from urllib.parse import parse_qs, quote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='com.mybrowser.debug')
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
assert args.serial.startswith('emulator-') and args.package.endswith('.debug')
ux.ADB = ['adb', '-s', args.serial]
ux.PACKAGE = args.package
args.output.mkdir(parents=True, exist_ok=True)
folder = 'PureLocalIsolation-' + str(time.time_ns())
fixture = args.output / folder
(fixture / 'assets').mkdir(parents=True)
requests, reports, checks = [], {}, []
result = {'passed': False, 'checks': checks, 'requests': requests}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        route = urlsplit(self.path)
        query = parse_qs(route.query)
        case = query.get('case', [''])[0]
        requests.append({'path': route.path, 'case': case, 'referer': self.headers.get('Referer', '')})
        if route.path == '/report':
            reports[case] = query['value'][0]
            body = b'ok'
        elif route.path == '/probe':
            # Deliberately disclose the address: isolation must not depend on an unknown UUID.
            base = query['base'][0]
            body = '''<!doctype html><meta name="viewport" content="width=device-width"><title>Remote isolation probe</title>
<h1>Remote isolation probe</h1><p id=result>WAIT</p><script>
const base=BASE, probeCase=CASE;
if(probeCase==='frame-no-referrer'){const meta=document.createElement('meta');meta.name='referrer';meta.content='no-referrer';document.head.appendChild(meta)}
const js=new Promise(resolve=>{const s=document.createElement('script');s.src=base+'/assets/canary.js';
s.onload=()=>resolve('script='+window.localCanary);s.onerror=()=>resolve('script=blocked');document.head.appendChild(s)});
const img=new Promise(resolve=>{const i=new Image();i.src=base+'/assets/shape.svg';
i.onload=()=>resolve('image='+i.naturalWidth);i.onerror=()=>resolve('image=blocked');document.body.appendChild(i)});
Promise.all([js,img]).then(v=>{document.querySelector('#result').textContent=v.join('|');
fetch('/report?case='+probeCase+'&value='+encodeURIComponent(v.join('|')))});
</script>'''.replace('BASE', json.dumps(base)).replace('CASE', json.dumps(case)).encode()
        else:
            body = b'<!doctype html><h1>TLS setup</h1>'
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def page_contains(text):
    deadline = time.monotonic() + 20
    while text not in ux.webview_text():
        assert time.monotonic() < deadline, 'Missing page text: ' + text
        time.sleep(.2)


def record(message):
    checks.append(message)
    print('PASS:', message, flush=True)


def shot(name):
    (args.output / (name + '.xml')).write_text(ux.nodes()[1], encoding='utf-8')
    (args.output / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))


def blocked(case):
    deadline = time.monotonic() + 20
    while case not in reports:
        assert time.monotonic() < deadline, 'Probe did not finish: ' + case
        time.sleep(.2)
    assert reports[case] == 'script=blocked|image=blocked', (case, reports[case])
    navigation = [r for r in requests if r['path'] == '/probe' and r['case'] == case]
    assert navigation and all(not r['referer'] for r in navigation), navigation


def tab_titles(expected):
    root, _ = ux.nodes()
    tabs = next(n for n in root.iter('node') if n.get('content-desc', '').startswith('Tabs ('))
    ux.tap_node(tabs)
    raw = ux.nodes()[1]
    assert '.local.pure-browser.invalid' not in raw, 'Local tab title exposes its virtual origin'
    ux.expect(expected)
    shot('tab-' + expected)
    ux.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.5)


cert, key = args.output / 'cert.pem', args.output / 'key.pem'
subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(key),
                '-out', str(cert), '-days', '1', '-subj', '/CN=localhost'], check=True, capture_output=True)
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
port = server.server_port
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert, key)
server.socket = context.wrap_socket(server.socket, server_side=True)
threading.Thread(target=server.serve_forever, daemon=True).start()
remote = f'https://127.0.0.1:{port}'
try:
    ux.adb('reverse', f'tcp:{port}', f'tcp:{port}')
    html = '''<!doctype html><meta name="viewport" content="width=device-width"><h1>Local isolation fixture</h1>
<p id=ready>UNGRANTED</p><script src="assets/canary.js"></script>
<script>if(window.localCanary)document.querySelector('#ready').textContent='GRANTED';
function probe(c){return REMOTE+'/probe?case='+c+'&base='+encodeURIComponent(location.origin)}</script>
<p><a id=remoteLink>Remote same tab</a></p><script>remoteLink.href=probe('same')</script>
<p><button onclick="window.open(probe('popup'))">Remote popup</button></p>
<p><button onclick="const f=document.createElement('iframe');f.src=probe('frame');document.body.appendChild(f)">Remote frame</button></p>
<p><button onclick="const f=document.createElement('iframe');f.src=probe('frame-no-referrer');document.body.appendChild(f)">Remote frame without referrer</button></p>
<p><button onclick="window.open('?child');setTimeout(()=>location.href=probe('background'),500)">Background opener</button></p>
<p><button onclick="window.open('assets/shape.svg')">Local image tab</button></p>'''.replace('REMOTE', json.dumps(remote))
    (fixture / 'index.html').write_text(html, encoding='utf-8')
    (fixture / 'assets/canary.js').write_text('window.localCanary="local-only";')
    (fixture / 'assets/shape.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" width="37" height="23"><rect width="37" height="23" fill="green"/></svg>')
    ux.adb('push', str(fixture), '/sdcard/Download/')
    ux.adb('shell', 'am', 'force-stop', args.package)
    ux.launch()
    ux.launch(remote + '/setup')
    ux.expect('Continue anyway (unsafe)', timeout=20)
    ux.tap('Continue anyway (unsafe)')
    page_contains('TLS setup')
    uri = 'content://com.android.externalstorage.documents/document/' + quote('primary:Download/' + folder + '/index.html', safe='')
    shell_user = ['su', '2000'] if ux.adb('shell', 'id', '-u') == '0' else []
    ux.adb('shell', *shell_user, 'am', 'start', '-W', '-n', args.package + '/com.mybrowser.MainActivity',
           '-a', 'android.intent.action.VIEW', '-t', 'text/html', '-d', uri, '--grant-read-uri-permission')
    page_contains('Local isolation fixture')
    ux.tap('Choose resource folder')
    root, _ = ux.nodes()
    if ux.match(root, folder) is None:
        download = ux.match(root, 'Download')
        if download is not None:
            ux.tap_node(download)
        else:
            ux.open_downloads_directory()
    ux.expect(folder)
    ux.tap_node(ux.match(ux.nodes()[0], folder))
    ux.tap('Use this folder')
    ux.tap('Allow')
    page_contains('GRANTED')
    tab_titles('index.html')
    record('Authorized local scripts load; an untitled HTML tab displays its filename')
    ux.tap('Remote frame')
    blocked('frame')
    record('A remote iframe cannot embed local scripts or images even with a known virtual URL')
    ux.tap('Remote frame without referrer')
    blocked('frame-no-referrer')
    record('Suppressing a remote iframe’s Referer cannot bypass local resource isolation')
    ux.tap('Remote same tab')
    blocked('same')
    ux.tap('Back')
    page_contains('GRANTED')
    record('Remote same-tab navigation cannot reuse the binding; Back restores the local page')
    ux.tap('Remote popup')
    blocked('popup')
    record('A remote popup cannot reuse its opener’s local binding or receive the virtual Referer')
    # Return through the tab card: a fresh popup intentionally has no Back history.
    root, _ = ux.nodes()
    ux.tap_node(next(n for n in root.iter('node') if n.get('content-desc', '').startswith('Tabs (')))
    ux.tap('index.html')
    page_contains('GRANTED')
    ux.tap('Background opener')
    page_contains('GRANTED')
    blocked('background')
    record('A background opener is isolated after remote navigation; its local child still loads')
    ux.tap('Local image tab')
    ux.expect('Local HTML file · shape.svg', timeout=15)
    tab_titles('shape.svg')
    record('A local image opened in a child tab uses its filename instead of the virtual origin')
    result['passed'] = True
except Exception:
    shot('failure')
    raise
finally:
    (args.output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    server.shutdown()
    server.server_close()
    ux.adb('reverse', '--remove', f'tcp:{port}')
    ux.adb('shell', 'rm', '-rf', '/sdcard/Download/' + folder)
    cert.unlink(missing_ok=True)
    key.unlink(missing_ok=True)
