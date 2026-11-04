#!/usr/bin/env python3
"""Measure ad visibility on every animation frame, including before a delayed load event.

Runs only on a rooted local emulator. Restores its original filter/site preferences.
The --expect-flash baseline mode verifies that the old APK actually reproduces the issue.
"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlparse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec); spec.loader.exec_module(ux)
PORT = 8878
# Android emulator's host-loopback alias. EasyList deliberately exempts
# 127.0.0.1/localhost from generic hiding, so those cannot test generic rules.
HOST = '10.0.2.2'
ORIGIN = f'http://{HOST}:{PORT}'
RULES = (f'##.cosmetic-flash-ad\n{HOST}##.cosmetic-scoped-ad\n'
         f'||{HOST}:{PORT}/blocked.js\n'
         f'@@||{HOST}:{PORT}/elemhide$elemhide\n'
         f'@@||{HOST}:{PORT}/generichide$generichide\n'
         f'@@||{HOST}:{PORT}/document$document\n')


def run(args):
    assert args.serial.startswith('emulator-')
    ux.ADB = ['adb', '-s', args.serial]; ux.PACKAGE = args.package
    deadline = time.monotonic() + 60
    while ux.adb('shell', 'getprop', 'sys.boot_completed') != '1':
        assert time.monotonic() < deadline, 'Emulator did not finish booting'
        time.sleep(.5)
    assert ux.adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    ux.adb('root'); ux.adb('wait-for-device'); ux.adb('reverse', f'tcp:{PORT}', f'tcp:{PORT}')
    reports = {}; blocked_requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def reply(self, body, kind):
            self.send_response(200); self.send_header('Content-Type', kind)
            self.send_header('Cache-Control', 'no-store'); self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body)
        def do_GET(self):
            path = urlparse(self.path).path
            if path == '/opener':
                target = '/page?' + urlparse(self.path).query
                self.reply(('<!doctype html><a target="_blank" href="' + target + '">Open cosmetic popup</a>').encode(), 'text/html'); return
            if path == '/redirect':
                self.send_response(302)
                self.send_header('Location', '/page?' + urlparse(self.path).query)
                self.send_header('Content-Length', '0'); self.end_headers(); return
            if path == '/slow.png':
                time.sleep(2.5)
                self.reply(b'', 'image/png'); return
            if path == '/blocked.js':
                blocked_requests.append(self.path)
                self.reply(b'window.cosmeticNetworkMarker=true;', 'application/javascript'); return
            self.reply((ROOT / 'cosmetic-flash.html').read_bytes(), 'text/html; charset=utf-8')
        def do_POST(self):
            size = int(self.headers.get('Content-Length', 0)); assert size < 16384
            report = json.loads(self.rfile.read(size))
            reports[report['run']] = report
            self.send_response(204); self.end_headers()

    server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    output = ROOT / 'results'; output.mkdir(exist_ok=True)
    result = {'serial': args.serial, 'package': args.package, 'baseline': args.expect_flash,
              'cases': [], 'error': None,
              'androidApi': ux.adb('shell', 'getprop', 'ro.build.version.sdk')}
    apk = ux.adb('shell', 'pm', 'path', args.package).partition(':')[2].strip()
    result['apkSha256'] = ux.adb('shell', 'sha256sum', apk).split()[0]
    result['webView'] = ux.adb('shell', 'dumpsys', 'webviewupdate')
    directory = '/data/user/0/' + args.package
    saved = {}
    snapshot_name = 'c05ce71c00000001-' + hashlib.sha256(RULES.encode()).hexdigest() + '.txt'

    def read_remote(path):
        # exec-out can return exit code 0 with "cat: ... not found" on stdout.
        # adb pull reliably distinguishes an absent file from actual XML bytes.
        with tempfile.TemporaryDirectory() as temp:
            local = Path(temp) / 'data'
            attempt = subprocess.run(ux.ADB + ['pull', path, str(local)], capture_output=True)
            return local.read_bytes() if attempt.returncode == 0 else None

    def write_remote(path, data):
        with tempfile.TemporaryDirectory() as temp:
            local = Path(temp) / 'data'; local.write_bytes(data)
            ux.adb('push', str(local), path)
        owner = ux.adb('shell', 'stat', '-c', '%u:%g', directory)
        ux.adb('shell', 'chown', owner, path); ux.adb('shell', 'restorecon', path)

    def preferences(name, updates):
        path = directory + '/shared_prefs/' + name + '.xml'
        if path not in saved:
            saved[path] = read_remote(path)
        original = saved[path]
        root = ET.fromstring(original) if original else ET.Element('map')
        for key, value in updates.items():
            for child in list(root):
                if child.get('name') == key: root.remove(child)
            if isinstance(value, bool): ET.SubElement(root, 'boolean', name=key, value=str(value).lower())
            else: ET.SubElement(root, 'string', name=key).text = value
        write_remote(path, ET.tostring(root, encoding='utf-8', xml_declaration=True))

    def configure(enabled=True, site_enabled=True):
        ux.adb('shell', 'am', 'force-stop', args.package)
        path = directory + '/shared_prefs/filter_settings.xml'
        if path not in saved:
            saved[path] = read_remote(path)
        original = ET.fromstring(saved[path]) if saved[path] else ET.Element('map')
        manifest = next((x.text for x in original if x.get('name') == 'subscriptions_manifest'), '[]')
        records = json.loads(manifest)
        records = [r for r in records if r.get('id') != 'c05ce71c00000001']
        records.append({'id':'c05ce71c00000001', 'url':ORIGIN+'/list', 'name':'Cosmetic QA',
                        'enabled':True, 'file':snapshot_name})
        preferences('filter_settings', {'enabled':enabled, 'auto_update':False,
                                        'subscriptions_manifest':json.dumps(records)})
        preferences('site_settings', {'sites':json.dumps({ORIGIN:{'filtering':site_enabled}})})

    def case(label, path='/page', generic_hidden=True, scoped_hidden=True, network_blocked=True, popup=False):
        stamp = str(time.time_ns())
        before = len(blocked_requests)
        ux.launch(ORIGIN + path + '?run=' + stamp)
        if popup: ux.tap('Open cosmetic popup')
        deadline = time.monotonic() + 25
        while stamp not in reports and time.monotonic() < deadline: time.sleep(.2)
        assert stamp in reports, 'No page telemetry: ' + label
        report = reports[stamp]; report['case'] = label
        report['networkBlocked'] = len(blocked_requests) == before
        result['cases'].append(report)
        assert report['samples'] >= 2 and report['firstSample'] < report['loadedAt'], report
        assert report['normalHidden'] == 0 and report['bridgeRejectsPage'], report
        if args.expect_flash:
            assert report['genericVisible'] > 0, 'Baseline did not reproduce ad flash'
        else:
            assert (report['genericVisible'] == 0) == generic_hidden, report
            assert (report['dynamicVisible'] == 0) == generic_hidden, report
            assert (report['scopedVisible'] == 0) == scoped_hidden, report
            assert report['networkBlocked'] == network_blocked, report
        print('PASS:', label, 'frames:', report['samples'], 'visible ad frames:', report['genericVisible'], flush=True)

    try:
        ux.launch(); ux.adb('shell', 'am', 'force-stop', args.package)
        ux.adb('shell', 'mkdir', '-p', directory+'/files/filter_subscriptions', directory+'/shared_prefs')
        preferences('browser_preferences', {'incognito_enabled':False, 'auto_check_updates':False})
        write_remote(directory+'/files/filter_subscriptions/'+snapshot_name, RULES.encode())
        configure(); case('cold first paint, dynamic insertion and head replacement')
        if not args.expect_flash:
            case('redirect uses destination rules', '/redirect')
            case('element hiding exception', '/elemhide', False, False)
            case('generic hiding exception retains scoped rules', '/generichide', False, True)
            case('document exception also permits network', '/document', False, False, False)
            case('popup first paint', '/opener', popup=True)
            ux.menu_item('Website settings')
            ux.tap('Temporarily disable filtering for this site')
            ux.adb('shell', 'input', 'keyevent', '4'); time.sleep(.5)
            case('temporary site exception', generic_hidden=False, scoped_hidden=False, network_blocked=False)
            ux.menu_item('Website settings'); ux.tap('Resume filtering for this site')
            ux.adb('shell', 'input', 'keyevent', '4'); time.sleep(.5)
            case('resuming site filtering')
            ux.menu_item('Enter incognito mode')
            case('private profile first paint')
            ux.menu_item('Exit incognito mode')
            configure(site_enabled=False); case('site filtering disabled', generic_hidden=False, scoped_hidden=False, network_blocked=False)
            configure(enabled=False); case('global filtering disabled', generic_hidden=False, scoped_hidden=False, network_blocked=False)
    except Exception as error:
        result['error'] = repr(error); raise
    finally:
        ux.adb('shell', 'am', 'force-stop', args.package)
        for path, data in saved.items():
            if data is None: ux.adb('shell', 'rm', '-f', path)
            else: write_remote(path, data)
        ux.adb('shell', 'rm', '-f', directory+'/files/filter_subscriptions/'+snapshot_name)
        server.shutdown(); server.server_close()
        name = 'cosmetic-flash-' + ('baseline-' if args.expect_flash else '') + args.serial + '.json'
        (output/name).write_text(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser')
    parser.add_argument('--expect-flash', action='store_true')
    run(parser.parse_args())
