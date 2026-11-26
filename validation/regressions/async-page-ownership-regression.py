#!/usr/bin/env python3
"""Check background opener GM writes and delayed Blob offers on a dedicated emulator.

Requires an installed app and validation/tools/setup-ui-probe.py. The script starts its own
HTTP fixture, installs a uniquely named user script through the review UI, and removes it
afterwards. No QA server or application test hooks are used. Example:
python3 validation/regressions/async-page-ownership-regression.py --serial emulator-5554 \
    --package com.mybrowser.debug --output validation/results/async-page-ownership
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
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser')
    parser.add_argument('--output', type=Path, default=ROOT / 'results/async-page-ownership')
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated test emulator'
    ux.ADB = ['adb', '-s', args.serial]
    ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    case = 'async-owner-' + str(time.time_ns())
    prefix = '/' + case
    script_name = 'Pure async ownership ' + case
    filename = case + '.txt'
    body = ('Page-owned bytes: ' + case + '\n').encode()
    events = []
    gate = threading.Event()
    installed = False
    private = False
    result = {'passed': False, 'checks': [], 'events': events}
    apk = ux.adb('shell', 'pm', 'path', args.package).partition(':')[2].strip()
    result['apkSha256'] = ux.adb('shell', 'sha256sum', apk).split()[0]

    def document(title, content, javascript=''):
        return ('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">'
                '<style>body{font:18px sans-serif;margin:24px}button,a{display:block;margin:14px 0;padding:14px}</style>'
                f'<title>{title}</title><h1>{title}</h1>{content}<script>'
                f'const fixture={json.dumps(case)}, prefix={json.dumps(prefix)};'
                "const mode=new URLSearchParams(location.search).get('mode')||'normal';"
                "function report(event, details={}){return fetch(prefix+'/event',{method:'POST',"
                "headers:{'Content-Type':'application/json'},body:JSON.stringify({event,mode,...details})});}"
                + javascript + '</script>').encode()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, content, mime='text/html; charset=utf-8'):
            self.send_response(200)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(content)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            try:
                self.wfile.write(content)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_POST(self):
            assert urlsplit(self.path).path == prefix + '/event'
            event = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            events.append({**event, 'receivedAt': time.monotonic()})
            self.reply(b'{}', 'application/json')

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == prefix + '/gate':
                gate.wait(10)
                self.reply(b'{}', 'application/json')
            elif path == prefix + '/storage.user.js':
                self.reply(user_script.encode(), 'application/javascript; charset=utf-8')
            elif path == prefix + '/opener':
                self.reply(document('Ownership opener', '<button id="open">Open ownership popup</button>',
                    "document.getElementById('open').onclick=()=>window.open(prefix+'/child?mode='+mode,'_blank');"))
            elif path == prefix + '/child':
                self.reply(document('Ownership child',
                    '<button id="write">Write in background opener</button><p id="status">Waiting for write</p>'
                    '<button id="delayed">Offer delayed blob</button>'
                    '<button id="immediate">Save immediate blob</button>'
                    '<button onclick="window.close()">Close ownership popup</button>',
                    "document.getElementById('write').onclick=()=>window.opener.postMessage({fixture,"
                    "value:mode==='private'?2:1},location.origin);"
                    "window.addEventListener('message',e=>{if(e.origin===location.origin&&e.data.fixture===fixture)"
                    "document.getElementById('status').textContent='Background write '+e.data.result;});"
                    "function save(delay){const api=window.__purePageFileDownload;"
                    "if(!api){report('missing-blob-channel');return;}const inspect=api.inspect;"
                    "if(delay)api.inspect=function(url,probe){api.inspect=inspect;report('inspect-held');"
                    "fetch(prefix+'/gate').then(()=>{const ok=inspect.call(api,url,probe);"
                    "report('offer-released',{ok});});};"
                    f"const a=document.createElement('a');a.download={json.dumps(filename)};"
                    f"a.href=URL.createObjectURL(new Blob([{json.dumps(body.decode())}],{{type:'text/plain'}}));"
                    "document.body.appendChild(a);a.click();a.remove();}"
                    "document.getElementById('delayed').onclick=()=>save(true);"
                    "document.getElementById('immediate').onclick=()=>save(false);"
                    "report('child-loaded');"))
            elif path == prefix + '/':
                self.reply(document('Ownership installer', f'<a href="{prefix}/storage.user.js">Install ownership script</a>'))
            else:
                self.send_error(404)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    port = server.server_address[1]
    origin = f'http://127.0.0.1:{port}'
    user_script = f'''// ==UserScript==
// @name {script_name}
// @namespace pure.validation.{case}
// @match http://127.0.0.1{prefix}/opener*
// @run-at document-end
// @noframes
// @grant GM.getValue
// @grant GM.setValue
// ==/UserScript==
(async function(){{
const fixture={json.dumps(case)}, prefix={json.dumps(prefix)};
const mode=new URLSearchParams(location.search).get('mode')||'normal';
const report=(event,details={{}})=>fetch(prefix+'/event',{{method:'POST',headers:{{'Content-Type':'application/json'}},
body:JSON.stringify({{event,mode,...details}})}});
window.addEventListener('message',async e=>{{
if(e.origin!==location.origin||e.data.fixture!==fixture)return;
try{{await GM.setValue('value',e.data.value);await report('write-ack',{{value:e.data.value}});
e.source.postMessage({{fixture,result:'saved'}},e.origin);}}
catch(error){{report('write-error',{{error:String(error)}});}}
}});
await report('script-loaded',{{value:await GM.getValue('value',0)}});
}})();
'''
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def wait_event(event, mode='normal', since=0, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            matches = [e for e in list(events) if e['event'] == event and e['mode'] == mode and e['receivedAt'] > since]
            if matches:
                return matches[-1]
            errors = [e for e in list(events) if e['event'].endswith('error') and e['receivedAt'] > since]
            assert not errors, errors
            time.sleep(.1)
        raise AssertionError(f'Missing {event} ({mode}); events={events!r}')

    def tap_resource(name):
        ux.tap(next(iter(ux.resource_labels(name))), timeout=10)

    def back():
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.4)

    def record(message):
        result['checks'].append(message)
        print('PASS:', message, flush=True)

    def screenshot(name):
        (args.output / (name + '.xml')).write_text(ux.nodes()[1], encoding='utf-8')
        (args.output / (name + '.png')).write_bytes(subprocess.check_output(
            ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))

    def load_opener(mode, expected):
        since = time.monotonic()
        ux.launch(origin + prefix + '/opener?mode=' + mode)
        event = wait_event('script-loaded', mode, since)
        assert event['value'] == expected, event
        ux.expect('Open ownership popup', timeout=15)

    def gm_roundtrip(mode, initial, written):
        load_opener(mode, initial)
        since = time.monotonic()
        ux.tap('Open ownership popup')
        wait_event('child-loaded', mode, since)
        ux.tap('Write in background opener')
        event = wait_event('write-ack', mode, since)
        assert event['value'] == written, event
        ux.expect('Background write saved', timeout=10)
        screenshot(mode + '-background-write')
        ux.tap('Close ownership popup')
        ux.expect('Open ownership popup', timeout=15)
        since = time.monotonic()
        ux.adb('shell', 'input', 'keycombination', '113', '46')  # Ctrl+R
        event = wait_event('script-loaded', mode, since)
        assert event['value'] == written, event
        record(f'{mode}: background opener acknowledges GM write and reload reads the saved value')

    def remove_script():
        ux.launch(origin + prefix + '/')
        ux.open_settings('内容与工具')
        ux.tap('油猴脚本')
        delete = next(iter(ux.resource_labels('cd_delete')))
        for _ in range(12):
            root, _ = ux.nodes()
            cards = [n for n in root.iter('node') if ux.match(n, script_name) is not None and ux.match(n, delete) is not None]
            if cards:
                card = min(cards, key=lambda n: len(list(n.iter('node'))))
                ux.tap_node(ux.match(card, delete))
                root, _ = ux.nodes()
                actions = [n for n in root.iter('node') if ux.visible(n) and n.get('text') in ux.resource_labels('cd_delete')]
                assert actions, 'Script deletion confirmation missing'
                ux.tap_node(actions[-1])
                ux.expect(script_name, present=False, timeout=10)
                return
            ux.swipe(root, downward=False)
        raise AssertionError('Installed fixture script not found for cleanup')

    try:
        ux.adb('reverse', f'tcp:{port}', f'tcp:{port}')
        if int(ux.adb('shell', 'getprop', 'ro.build.version.sdk')) >= 33:
            ux.adb('shell', 'pm', 'grant', args.package, 'android.permission.POST_NOTIFICATIONS')
        ux.launch(origin + prefix + '/')
        # This suite needs a normal starting session, as do the existing script regressions.
        ux.tap('菜单')
        root = ux.expect_menu()
        assert not any(ux.match(root, label) is not None for label in ux.resource_labels('menu_incognito_exit_short')), \
            'Start this regression in normal mode'
        ux.close_menu()
        ux.tap('Install ownership script')
        ux.expect(script_name, timeout=15)
        tap_resource('script_install')
        installed = True
        ux.expect('油猴脚本', timeout=15)
        gm_roundtrip('normal', 0, 1)
        ux.menu_item(next(iter(ux.resource_labels('menu_incognito_short'))))
        private = True
        gm_roundtrip('private', 0, 2)
        ux.menu_item(next(iter(ux.resource_labels('menu_incognito_exit_short'))))
        private = False
        load_opener('normal', 1)
        record('Private GM writes stay isolated from the normal store')

        since = time.monotonic()
        ux.tap('Open ownership popup')
        wait_event('child-loaded', since=since)
        ux.tap('Offer delayed blob')
        held = wait_event('inspect-held', since=since)
        # Keep this transition within the real 8-second inspect timeout. A native shortcut
        # selects the new tab before its command returns; the old popup remains alive.
        ux.adb('shell', 'input', 'keycombination', '113', '48')  # Ctrl+T
        root, raw = ux.nodes()
        assert ux.match(root, 'Save immediate blob') is None, 'The source tab is still visible: ' + raw
        gate.set()
        released = wait_event('offer-released', since=held['receivedAt'], timeout=5)
        assert released['ok'], released
        assert released['receivedAt'] - held['receivedAt'] < 6, 'Fixture missed the native inspect deadline'
        time.sleep(.5)
        root, raw = ux.nodes()
        assert not any(ux.match(root, label) is not None for label in ux.resource_labels('download_confirm_title')), raw
        screenshot('late-blob-new-tab')
        ux.menu_item('下载')
        root, raw = ux.nodes()
        assert filename not in raw, 'Old page offer entered the new tab downloads: ' + raw
        assert not any(ux.match(root, label) is not None for label in ux.resource_labels('download_blocked_title')), raw
        back()
        ux.close_menu()
        ux.adb('shell', 'input', 'keycombination', '113', '51')  # Ctrl+W, return to source popup
        ux.expect('Save immediate blob', timeout=15)
        record('A late Blob offer cannot create a confirmation or intercepted entry in the new tab')

        ux.tap('Save immediate blob')
        ux.expect(filename, timeout=15)
        screenshot('source-blob-confirmation')
        tap_resource('download_confirm_action')
        path = '/sdcard/Download/' + filename
        deadline = time.monotonic() + 20
        while True:
            found = subprocess.run(ux.ADB + ['shell', 'sha256sum', path], capture_output=True, text=True, timeout=10)
            if found.returncode == 0:
                assert found.stdout.split()[0] == hashlib.sha256(body).hexdigest(), found.stdout
                break
            assert time.monotonic() < deadline, 'Blob was not published to Downloads'
            time.sleep(.25)
        record('Returning to the owning popup still saves a Blob with the exact expected bytes')
        ux.menu_item('下载')
        ux.expect(filename, timeout=10)
        more = next(iter(ux.resource_labels('download_more')))
        rows = [n for n in ux.nodes()[0].iter('node')
                if ux.match(n, filename) is not None and ux.match(n, more) is not None]
        assert rows, 'Saved download row has no actions'
        ux.tap_node(ux.match(min(rows, key=lambda n: len(list(n.iter('node')))), more))
        tap_resource('download_delete')
        tap_resource('ui_delete_records_and_files')
        tap_resource('cd_delete')
        ux.expect(filename, present=False, timeout=10)
        back()
        ux.close_menu()
        ux.tap('Close ownership popup')
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        raise
    finally:
        gate.set()
        cleanup_errors = []
        try:
            if private:
                ux.menu_item(next(iter(ux.resource_labels('menu_incognito_exit_short'))))
            if installed:
                remove_script()
        except Exception as error:
            cleanup_errors.append(repr(error))
        subprocess.run(ux.ADB + ['shell', 'rm', '-f', '/sdcard/Download/' + filename], capture_output=True, timeout=10)
        subprocess.run(ux.ADB + ['reverse', '--remove', f'tcp:{port}'], capture_output=True, timeout=10)
        server.shutdown()
        server.server_close()
        if cleanup_errors:
            result['cleanupErrors'] = cleanup_errors
            result['passed'] = False
        (args.output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        if cleanup_errors and 'error' not in result:
            raise AssertionError('Fixture cleanup failed: ' + repr(cleanup_errors))


if __name__ == '__main__':
    main()
