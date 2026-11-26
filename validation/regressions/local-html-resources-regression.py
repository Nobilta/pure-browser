#!/usr/bin/env python3
"""Verify local HTML resources through a real SAF folder grant, cancellation and reuse."""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import subprocess
import threading
import time
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--package', default='com.mybrowser.debug')
p.add_argument('--debug-webview', action='store_true')
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
assert a.serial.startswith('emulator-')
assert not a.debug_webview or a.package.endswith('.debug'), 'WebView inspection requires a debug build'
ux.ADB = ['adb', '-s', a.serial]
ux.PACKAGE = a.package
a.output.mkdir(parents=True, exist_ok=True)
folder = 'PureHtmlResourcesQA-' + str(int(time.time()))
device = '/sdcard/Download/' + folder
fixture = a.output / folder
fixture.mkdir(exist_ok=True)
(fixture / 'assets').mkdir(exist_ok=True)
checks = []
font = ux.adb('shell', 'settings', 'get', 'system', 'font_scale')
shell_user = ['su', '2000'] if ux.adb('shell', 'id', '-u') == '0' else []
requests = []
remote_name = folder + '.png'


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        requests.append(dict(self.headers))
        body = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=')
        self.send_response(200)
        self.send_header('Content-Type', 'image/png')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
port = server.server_port
threading.Thread(target=server.serve_forever, daemon=True).start()


def expect_page(text):
    if not a.debug_webview:
        ux.expect(text, timeout=15)
        return
    deadline = time.monotonic() + 15
    while text not in ux.webview_text():
        assert time.monotonic() < deadline, 'Missing page text: ' + text
        time.sleep(.2)


def cancel_picker():
    # DocumentsUI Back first moves to a parent folder when it remembers a child.
    for _ in range(5):
        if ux.match(ux.nodes()[0], 'Choose resource folder') is not None:
            return
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.3)
    ux.expect('Choose resource folder')


def record(message):
    checks.append(message)
    print('PASS:', message, flush=True)


def shot(name):
    (a.output / (name + '.xml')).write_text(ux.nodes()[1], encoding='utf-8')
    (a.output / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))


def open_file(name):
    uri = 'content://com.android.externalstorage.documents/document/' + quote('primary:Download/' + folder + '/' + name, safe='')
    ux.adb('shell', *shell_user, 'am', 'start', '-W', '-n', ux.PACKAGE + '/com.mybrowser.MainActivity',
           '-a', 'android.intent.action.VIEW', '-t', 'text/html', '-d', uri, '--grant-read-uri-permission')
    ux.expect('Local HTML file · ' + name, timeout=15)


try:
    ux.adb('reverse', f'tcp:{port}', f'tcp:{port}')
    html = '''<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset=utf-8>
<link rel=stylesheet href="assets/theme.css">
<h1>Local resource test</h1><p id=result>NO RESOURCES</p><p id=boundary></p><img id=picture alt="Local green square" src="assets/shape.svg">
<p><a href="assets/a%20b.json" download="saved.json">Download local resource</a></p>
<button onclick="this.textContent='COUNT 1'">COUNT 0</button><script src="assets/app.js"></script>'''
    html += f'<p><img alt="Remote image" src="http://127.0.0.1:{port}/{remote_name}" width="48" height="48"></p>'
    for name in ('index.html', 'second.html'):
        (fixture / name).write_text(html, encoding='utf-8')
    (fixture / 'assets/theme.css').write_text('body{font:20px sans-serif;margin:20px}h1{color:rgb(0,128,0)}img{width:48px;height:48px}button{padding:12px}')
    (fixture / 'assets/shape.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48"><rect width="48" height="48" fill="green"/></svg>')
    (fixture / 'assets/a b.json').write_text('{"message":"中文"}', encoding='utf-8')
    (fixture / 'assets/app.js').write_text('''Promise.all([picture.decode(),fetch('assets/a%20b.json?v=1').then(r=>r.json())]).then(([,data])=>{
result.textContent=getComputedStyle(document.querySelector('h1')).color==='rgb(0, 128, 0)'&&data.message==='中文'?'RESOURCES OK':'RESOURCE CONTENT WRONG';
});fetch('../pure-html-outside.txt').then(r=>boundary.textContent='OUTSIDE '+r.status);''', encoding='utf-8')
    outside = a.output / 'pure-html-outside.txt'
    outside.write_text('outside the selected directory')
    ux.adb('push', str(fixture), '/sdcard/Download/')
    ux.adb('push', str(outside), '/sdcard/Download/pure-html-outside.txt')
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    ux.launch()
    open_file('index.html')
    expect_page('NO RESOURCES')
    ux.tap('COUNT 0')
    ux.tap('Choose resource folder')
    cancel_picker()
    expect_page('COUNT 1')
    expect_page('NO RESOURCES')
    record('Cancelling folder selection preserves the original page and script state')
    ux.tap('Choose resource folder')
    root, raw = ux.nodes()
    if ux.match(root, folder) is None:
        download = ux.match(root, 'Download')
        if download is not None:
            ux.tap_node(download)
        else:
            ux.open_downloads_directory()
    # Recreate the browser while DocumentsUI owns the result.
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.1')
    time.sleep(1)  # Let DocumentsUI replace the pre-configuration grid before using its bounds.
    ux.expect(folder)
    ux.tap_node(ux.match(ux.nodes()[0], folder))
    root, _ = ux.nodes()
    assert ux.match(root, 'Use this folder').get('enabled') == 'true', 'Selected resource directory is not grantable'
    ux.tap('Use this folder')
    ux.tap('Allow')
    expect_page('RESOURCES OK')
    expect_page('OUTSIDE 404')
    shot('authorized-resources')
    record('Folder result survives recreation; real CSS, JS, image and encoded JSON path load; parent file stays inaccessible')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', font)
    expect_page('RESOURCES OK')
    ux.tap('Download local resource')
    ux.expect('Unable to start the download')
    ux.expect('Download file?', present=False)
    for action, feedback in [('Save image', 'Unable to start the download'),
                             ('Share image', 'Unable to fetch this image.')]:
        image = ux.match(ux.nodes()[0], 'Local green square')
        assert image is not None, 'Local image missing from accessibility tree'
        x1, y1, x2, y2 = ux.bounds(image)
        x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
        ux.adb('shell', 'input', 'swipe', x, y, x, y, '1200')
        root, raw = ux.nodes()
        assert '.local.pure-browser.invalid' not in raw, 'Context menu exposes virtual local URL'
        assert ux.match(root, 'shape.svg') is not None
        for unavailable in ('Copy image link', 'Search by image', 'Block this address', 'Block everything from this site'):
            assert ux.match(root, unavailable) is None, 'Local resource exposes ' + unavailable
        ux.tap(action)
        ux.expect(feedback)
        ux.expect('Download file?', present=False)
    ux.menu_item('Downloads')
    ux.expect('a b.json', present=False)
    ux.expect('shape.svg', present=False)
    ux.adb('shell', 'input', 'keyevent', '4')
    ux.close_menu()
    record('Local resource download and image save/share report unavailable without creating HTTP tasks')
    link = ux.match(ux.nodes()[0], 'Download local resource')
    x1, y1, x2, y2 = ux.bounds(link)
    x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
    ux.adb('shell', 'input', 'swipe', x, y, x, y, '1200')
    root, raw = ux.nodes()
    assert '.local.pure-browser.invalid' not in raw
    assert ux.match(root, 'Copy link') is None
    assert ux.match(root, 'Block this address') is None
    assert ux.match(root, 'Block everything from this site') is None
    assert ux.match(root, 'Open in new tab') is not None
    ux.adb('shell', 'input', 'keyevent', '4')
    ux.tap('Menu')
    root = ux.expect_menu()
    assert ux.match(root, 'index.html') is not None, 'Untitled local HTML menu must use its filename'
    assert all('.local.pure-browser.invalid' not in (n.get('text', '') + n.get('content-desc', '')) for n in root.iter('node'))
    parent = {child: node for node in root.iter('node') for child in node}
    tile = ux.match(root, 'Block element')
    assert tile is not None
    while tile.get('enabled') != 'false' and tile in parent:
        tile = parent[tile]
    assert tile.get('enabled') == 'false', 'Local HTML must not offer saved element rules'
    shot('local-resource-menu')
    ux.close_menu()
    record('Untitled page uses its filename; local link/image menus omit virtual addresses and invalid copy/search/block actions')
    for action in ('Share image', 'Save image'):
        image = ux.match(ux.nodes()[0], 'Remote image')
        assert image is not None
        x1, y1, x2, y2 = ux.bounds(image)
        x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
        ux.adb('shell', 'input', 'swipe', x, y, x, y, '1200')
        ux.expect('Copy image link')
        ux.expect('Search by image')
        before = len(requests)
        ux.tap(action)
        if action == 'Save image':
            ux.expect('Download file?')
            assert '.local.pure-browser.invalid' not in ux.nodes()[1], 'Download source exposes virtual local URL'
            ux.tap('Download')
        deadline = time.monotonic() + 15
        while len(requests) == before:
            assert time.monotonic() < deadline, 'Native image request did not arrive'
            time.sleep(.2)
        native = [h for h in requests[before:] if 'Sec-Fetch-Dest' not in h]
        assert native and all(not h.get('Referer') for h in native), requests[before:]
        if action == 'Share image':
            ux.expect('Choose resource folder', present=False)
            ux.adb('shell', 'input', 'keyevent', '4')
            ux.expect('Choose resource folder')
    record('Remote image actions stay available; native share/download requests and confirmation omit the virtual local source')
    ux.menu_item('More tools')
    ux.tap('User scripts')
    ux.expect('User scripts')
    open_file('second.html')
    ux.expect('User scripts', present=False)
    expect_page('RESOURCES OK')
    expect_page('OUTSIDE 404')
    record('Incoming HTML closes the previous independent scripts panel and shows the new page')
    record('Another HTML file in the same authorized directory loads without asking again')
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    open_file('index.html')
    expect_page('RESOURCES OK')
    expect_page('OUTSIDE 404')
    record('Folder binding and read grant survive restart and download cleanup')
    ux.tap('Show original')
    ux.expect('Preview')
    ux.tap('Preview')
    expect_page('RESOURCES OK')
    record('Source/preview round trip still returns to the rendered local page')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
    for size, label in [('840x1920', '320dp'), ('1920x840', 'landscape')]:
        ux.adb('shell', 'wm', 'size', size)
        ux.expect('Choose resource folder', timeout=15)
        root, _ = ux.nodes()
        parent = {child: node for node in root.iter('node') for child in node}
        buttons = []
        width, height = map(int, size.split('x'))
        for text in ('Show original', 'Choose resource folder'):
            button = ux.match(root, text)
            while button.get('clickable') != 'true':
                button = parent[button]
            x1, y1, x2, y2 = ux.bounds(button)
            assert 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height, (text, ux.bounds(button))
            buttons.append((x2 - x1, y2 - y1))
        assert abs(buttons[0][0] - buttons[1][0]) <= 2 and buttons[0][1] == buttons[1][1], buttons
        shot('actions-' + label + '-200percent')
        ux.tap('Choose resource folder')
        cancel_picker()
        expect_page('RESOURCES OK')
        ux.tap('Show original')
        ux.expect('Preview')
        ux.tap('Preview')
        expect_page('RESOURCES OK')
        record('Equal resource/source actions, picker Back and source return at ' + label + '/200%')
    (a.output / 'result.json').write_text(json.dumps({'passed': True, 'checks': checks}, ensure_ascii=False, indent=2))
except Exception:
    shot('failure')
    raise
finally:
    server.shutdown()
    server.server_close()
    ux.adb('reverse', '--remove', f'tcp:{port}')
    ux.adb('shell', 'wm', 'size', 'reset')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', font)
    ux.adb('shell', 'rm', '-rf', device)
    ux.adb('shell', 'rm', '-f', '/sdcard/Download/pure-html-outside.txt')
    ux.adb('shell', 'rm', '-f', '/sdcard/Download/' + remote_name)
