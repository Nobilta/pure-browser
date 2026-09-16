#!/usr/bin/env python3
"""Verify desktop redirects, request UAs and switch stability on the installed release.

The local fixture uses Chromium's loopback .localhost hosts and adb reverse, so its
m./www. redirects require neither public DNS nor a changing third-party website.
Pass --online to additionally verify the reported https://m.jrs16.com/ case.
"""
import argparse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import subprocess
import threading
import time
from urllib.parse import parse_qs, urlsplit
import uuid

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('features', ROOT / 'features-regression.py')
features = importlib.util.module_from_spec(spec)
spec.loader.exec_module(features)
ux = features.ux
EVENTS = deque(maxlen=3000)
LOCK = threading.Lock()


def event(value):
    with LOCK:
        EVENTS.append({**value, 'receivedAt': time.time()})


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        size = int(self.headers.get('Content-Length', '0'))
        if self.path != '/__state' or not 0 < size <= 4096:
            self.send_error(400)
            return
        event({'kind': 'document', **json.loads(self.rfile.read(size))})
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path != '/fixture':
            self.send_error(404)
            return
        query = parse_qs(parsed.query)
        host = self.headers.get('Host', '').partition(':')[0]
        agent = self.headers.get('User-Agent', '')
        request_id = uuid.uuid4().hex
        desktop = 'Mobile' not in agent and 'Android' not in agent
        target_host = ('www.pure.localhost' if host == 'm.pure.localhost' and desktop else
                       'm.pure.localhost' if host == 'www.pure.localhost' and not desktop else None)
        target = (f'http://{target_host}:{self.server.server_port}{self.path}' if target_host else '')
        event({'kind': 'request', 'id': request_id, 'host': host, 'port': self.server.server_port,
               'userAgent': agent, 'desktop': desktop, 'target': target, 'path': self.path})
        if target and query.get('redirect') == ['http']:
            self.send_response(302)
            self.send_header('Location', target)
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        config = json.dumps({'target': target, 'case': query.get('case', [''])[0], 'requestId': request_id,
                             'requestDesktop': desktop, 'requestUserAgent': agent})
        body = ('''<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pure desktop redirect fixture</title></head><body><h1>Pure desktop redirect fixture</h1>
<p id="state"></p><a href="/fixture?case=link">Same site link</a><script>
const config = CONFIG;
if (config.target) location.replace(config.target);
else {
  const id = String(performance.timeOrigin) + Math.random();
  function report() {
    const value = {id, case:config.case, requestId:config.requestId, url:location.href, host:location.hostname,
      port:Number(location.port), ready:document.readyState, userAgent:navigator.userAgent,
      desktop:!(/Mobile|Android/.test(navigator.userAgent)), requestDesktop:config.requestDesktop,
      requestUserAgent:config.requestUserAgent, width:document.documentElement.clientWidth,
      viewport:document.querySelector('meta[name=viewport]').content};
    document.getElementById('state').textContent = value.desktop ? 'Desktop layout' : 'Mobile layout';
    fetch('/__state',{method:'POST',body:JSON.stringify(value)}).catch(()=>{});
  }
  report(); setInterval(report, 350);
}
</script></body></html>'''.replace('CONFIG', config)).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


class Regression(features.Regression):
    def __init__(self, serial):
        assert serial.startswith('emulator-'), 'Use a dedicated local emulator'
        super().__init__(serial, 'desktop-mode')
        self.output = ROOT / 'results/release-0.5.2'
        self.output.mkdir(parents=True, exist_ok=True)
        self.prefix = 'api' + self.sdk + '-desktop-mode'
        self.result = {'serial': serial, 'sdk': self.sdk, 'apkSha256': self.apk_hash,
                       'webView': ux.adb('shell', 'dumpsys', 'webviewupdate'),
                       'startedAt': time.strftime('%Y-%m-%d %H:%M:%S'), 'checks': self.checks}
        self.case = uuid.uuid4().hex

    def snapshot(self, name):
        stem = self.output / (self.prefix + '-' + name)
        stem.with_suffix('.xml').write_text(ux.nodes()[1], encoding="utf-8")
        stem.with_suffix('.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))

    def switch(self, root=None):
        root = root if root is not None else ux.nodes()[0]
        containers = [n for n in root.iter('node') if ux.match(n, '桌面版网站') is not None
                      and any(c.get('checkable') == 'true' for c in n.iter('node'))]
        assert containers, 'Desktop switch is missing'
        container = min(containers, key=lambda n: len(list(n.iter('node'))))
        return next(c for c in container.iter('node') if c.get('checkable') == 'true')

    def set_desktop_in_settings(self, checked):
        for attempt in range(4):
            try:
                ux.menu_item('网站设置')
                break
            except AssertionError:
                # A website can schedule its notice after load completion, including
                # between locating the menu and tapping its row. No save happened yet.
                if attempt == 3 or not self.dismiss_notice():
                    raise
        switch = self.switch()
        if switch.get('checked') != str(checked).lower():
            ux.tap_node(switch)
        self.since = time.time()
        ux.tap('保存并刷新')

    def stable_menu(self, checked, label):
        for attempt in range(4):
            root, _ = ux.nodes()
            if ux.menu_open(root):
                break
            if self.dismiss_notice(root):
                continue
            try:
                ux.tap('菜单')
                break
            except AssertionError:
                if attempt == 3 or not self.dismiss_notice():
                    raise
        samples = []
        for _ in range(8):
            self.dismiss_notice()
            actual = self.switch().get('checked') == 'true'
            samples.append(actual)
            assert actual == checked, 'Desktop mode changed without user input: ' + str(samples)
            time.sleep(.3)
        self.snapshot(label)
        ux.close_menu()
        self.record('desktop menu remains stable: ' + label, samples=samples)

    def open_fixture(self, host='m.pure.localhost', redirect='js', port=8878):
        self.since = time.time()
        ux.launch(f'http://{host}:{port}/fixture?case={self.case}&redirect={redirect}')

    def settled(self, desktop, host=None, port=8878):
        deadline = time.monotonic() + 25
        last = None
        while time.monotonic() < deadline:
            with LOCK:
                last = next((e for e in reversed(EVENTS) if e.get('kind') == 'document'
                             and e.get('case') == self.case and e['receivedAt'] > self.since), None)
                request = next((e for e in reversed(EVENTS) if e['kind'] == 'request'), None)
            if (last and time.time() - last['receivedAt'] < 2 and last['ready'] == 'complete'
                    and request and request['receivedAt'] >= self.since and last['requestId'] == request['id']
                    and last['desktop'] == desktop and last['requestDesktop'] == desktop
                    and last['port'] == port and (host is None or last['host'] == host)
                    and (not desktop or last['width'] == 1024)):
                return last
            time.sleep(.2)
        raise AssertionError('Page did not settle with the requested UA: ' + json.dumps(last))

    def stable_document(self, desktop, host, label, port=8878):
        before = self.settled(desktop, host, port)
        started = time.time()
        time.sleep(3)
        after = self.settled(desktop, host, port)
        with LOCK:
            requests = [e for e in EVENTS if e['kind'] == 'request' and e['receivedAt'] >= started]
        assert before['id'] == after['id'], 'Document reloaded without user input'
        assert not requests, 'Unexpected repeated main-frame requests: ' + json.dumps(requests)
        self.record(label, document=after, unexpectedRequests=len(requests))

    def local(self):
        self.open_fixture()
        self.set_desktop_in_settings(False)
        self.stable_document(False, 'm.pure.localhost', 'initial mobile page settles')
        self.since = time.time()
        ux.menu_item('桌面版网站')
        self.stable_document(True, 'www.pure.localhost', 'JavaScript m. to www. redirect keeps desktop UA')
        self.stable_menu(True, 'desktop-enabled')
        ux.menu_item('网站设置')
        assert self.switch().get('checked') == 'true', 'Website settings disagree with the menu'
        self.snapshot('inherited-site-setting')
        ux.tap_node(self.switch())
        self.since = time.time()
        ux.tap('保存并刷新')
        self.stable_document(False, 'm.pure.localhost', 'disabling from www. returns to mobile without a loop')
        self.stable_menu(False, 'desktop-disabled')
        self.open_fixture(redirect='http')
        self.since = time.time()
        ux.menu_item('桌面版网站')
        self.stable_document(True, 'www.pure.localhost', 'HTTP 302 redirect keeps desktop request and viewport')
        for number in range(2):
            self.since = time.time()
            ux.tap('刷新')
            self.stable_document(True, 'www.pure.localhost', 'explicit refresh stays desktop ' + str(number + 1))
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        self.open_fixture(host='www.pure.localhost')
        self.stable_document(True, 'www.pure.localhost', 'desktop preference survives process restart')
        self.open_fixture(host='other.pure.localhost')
        self.stable_document(False, 'other.pure.localhost', 'unrelated subdomain retains its own preference')
        self.open_fixture(port=8879)
        self.stable_document(False, 'm.pure.localhost', 'different port retains its own preference', port=8879)
        self.open_fixture()
        self.stable_document(True, 'www.pure.localhost', 'returning to the original site restores desktop')

    def dismiss_notice(self, root=None):
        root = root if root is not None else ux.nodes()[0]
        if ux.match(root, '取消') is not None and ux.match(root, '确定') is not None:
            ux.tap('取消')
            print('Dismissed a delayed website notice', flush=True)
            return True
        return False

    def dismiss_site_dialogs(self):
        deadline = time.monotonic() + 35
        ready_since = None
        while time.monotonic() < deadline:
            root, _ = ux.nodes()
            if self.dismiss_notice(root):
                ready_since = None
            elif ux.match(root, '菜单') is not None and ux.match(root, '刷新') is not None:
                ready_since = ready_since or time.monotonic()
                if time.monotonic() - ready_since >= 3:
                    return
            else:
                ready_since = None
            time.sleep(.3)
        raise AssertionError('Website did not finish loading after its delayed dialogs')

    def console_value(self):
        source = ('JSON.stringify({probe:"' + uuid.uuid4().hex + '",url:location.href,title:document.title,ready:document.readyState,'
                  'userAgent:navigator.userAgent,timeOrigin:performance.timeOrigin,'
                  'width:document.documentElement.clientWidth,'
                  'viewport:document.querySelector("meta[name=viewport]")?.content})')
        self.enter_source(source)
        self.back()
        ux.tap('执行')
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            root, _ = ux.nodes()
            lines = [n.get('text', '') for n in root.iter('node')
                     if n.get('text', '').startswith('> ' + source + '\n')]
            if lines:
                return json.loads(lines[-1].partition('\n')[2])
            time.sleep(.2)
        raise AssertionError('Page diagnostics did not return through the release console')

    def online(self):
        ux.launch('https://m.jrs16.com/')
        self.dismiss_site_dialogs()
        self.set_desktop_in_settings(True)
        self.dismiss_site_dialogs()
        self.stable_menu(True, 'jrs-desktop-menu')
        ux.menu_item('开发者工具')
        first = self.console_value()
        time.sleep(5)
        second = self.console_value()
        assert urlsplit(second['url']).hostname == 'www.jrs16.com', second
        assert second['ready'] == 'complete' and second['width'] == 1024, second
        assert 'Mobile' not in second['userAgent'] and 'X11; Linux x86_64' in second['userAgent'], second
        assert first['url'] == second['url'] and first['timeOrigin'] == second['timeOrigin'], (first, second)
        self.snapshot('jrs-desktop-console')
        ux.tap('关闭')
        ux.close_menu()
        self.snapshot('jrs-desktop-page')
        self.record('real jrs16 desktop page keeps URL, document, desktop UA and viewport stable', first=first, second=second)
        self.set_desktop_in_settings(False)
        self.dismiss_site_dialogs()
        self.stable_menu(False, 'jrs-mobile-menu')
        ux.menu_item('开发者工具')
        mobile = self.console_value()
        assert urlsplit(mobile['url']).hostname == 'm.jrs16.com' and 'Mobile' in mobile['userAgent'], mobile
        assert mobile['ready'] == 'complete', mobile
        ux.tap('关闭')
        ux.close_menu()
        self.record('real jrs16 can return to mobile mode', document=mobile)

    def save(self):
        with LOCK:
            self.result['events'] = list(EVENTS)
        (self.output / (self.prefix + '.json')).write_text(json.dumps(self.result, ensure_ascii=False, indent=2) + '\n', encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--online', action='store_true')
    parser.add_argument('--online-only', action='store_true', help='Check only the public site after a local run')
    args = parser.parse_args()
    test = Regression(args.serial)
    if args.online_only:
        test.prefix += '-online'
    servers = []
    try:
        for port in (8878, 8879):
            server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
            servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            ux.adb('reverse', f'tcp:{port}', f'tcp:{port}')
        if not args.online_only:
            test.local()
        if args.online or args.online_only:
            test.online()
        test.result['status'] = 'passed'
    except Exception as error:
        test.result.update(status='failed', error=str(error))
        test.snapshot('failure')
        raise
    finally:
        test.save()
        for server in servers:
            server.shutdown()
            server.server_close()
        for port in (8878, 8879):
            ux.adb('reverse', '--remove', f'tcp:{port}')


if __name__ == '__main__':
    main()
