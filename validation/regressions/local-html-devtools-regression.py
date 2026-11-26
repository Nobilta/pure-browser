#!/usr/bin/env python3
"""Exercise granted local HTML and the Console/DOM/Web Storage editing loop on an emulator."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='com.mybrowser')
parser.add_argument('--debug-webview', action='store_true')
parser.add_argument('--boundaries-only', action='store_true', help='Only check page responses, storage budgets and UI semantics')
parser.add_argument('--output', type=Path, default=ROOT / 'results/local-html-devtools')
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Use a dedicated test emulator'
assert not args.debug_webview or args.package.endswith('.debug'), 'WebView inspection requires a debug build'
ux.ADB = ['adb', '-s', args.serial]
ux.PACKAGE = args.package
args.output.mkdir(parents=True, exist_ok=True)
name = 'pure-local-devtools-regression.html'
device_path = '/sdcard/Download/' + name
uri = 'content://com.android.externalstorage.documents/document/primary%3ADownload%2F' + name
shell_user = ['su', '2000'] if ux.adb('shell', 'id', '-u') == '0' else []
apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
result = {'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': [], 'passed': False}


def record(message):
    result['checks'].append(message)
    print('PASS:', message, flush=True)


def expect_page(text, timeout=15):
    if not args.debug_webview:
        ux.expect(text, timeout=timeout)
        return
    deadline = time.monotonic() + timeout
    while text not in ux.webview_text():
        assert time.monotonic() < deadline, 'Missing page text: ' + text
        time.sleep(.2)


def shot(name):
    (args.output / (name + '.xml')).write_text(ux.nodes()[1], encoding='utf-8')
    (args.output / (name + '.png')).write_bytes(subprocess.check_output(
        ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))


def back():
    ux.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.4)


def editor(root, label):
    wanted = ux.resource_labels(label)
    return next(n for n in root.iter('node') if ux.visible(n) and n.get('class') == 'android.widget.EditText'
                and any(c.get('content-desc') in wanted for c in n.iter()))


def wait_editor(label):
    # Storage loads the complete value asynchronously before opening its editor.
    deadline = time.monotonic() + 10
    while True:
        try:
            return editor(ux.nodes()[0], label)
        except StopIteration:
            assert time.monotonic() < deadline, 'Editor did not open: ' + label
            time.sleep(.2)


def field(label, value):
    ux.tap_node(wait_editor(label))
    # Wait for focus to settle before the shell accessibility connection is recreated.
    deadline = time.monotonic() + 5
    while wait_editor(label).get('focused') != 'true':
        assert time.monotonic() < deadline, 'Input did not receive focus: ' + label
    ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
           'com.mybrowser.validation.FastUiDump', 'setText', base64.b64encode(value.encode()).decode())
    assert wait_editor(label).get('text') == value, 'Wrong input contents: ' + label


def contains(fragment):
    deadline = time.monotonic() + 10
    while True:
        root, _ = ux.nodes()
        if any(fragment in n.get('text', '') for n in root.iter('node') if ux.visible(n)):
            return
        assert time.monotonic() < deadline, 'Missing result: ' + fragment
        time.sleep(.2)


def tool_tab(label):
    # Details replace the root toolbar; use the visible header Back to return to its list.
    root, _ = ux.nodes()
    if ux.match(root, 'Developer tools') is None:
        ux.tap('Back')
    ux.tap(label)


def enable_capture():
    root, _ = ux.nodes()
    if ux.match(root, 'Capturing') is None:
        tool_tab('Elements')
        if ux.match(ux.nodes()[0], 'Enable and reload') is None:
            ux.tap('Capture stopped')
        ux.tap('Enable and reload')
    ux.expect('Capturing')


def command(code, expected=None):
    tool_tab('Console')
    field('ui_enter_a_javascript_command', code)
    back()
    ux.tap('Run')
    if expected is not None:
        contains('> ' + code + '\n' + expected)


def devtools():
    ux.menu_item('More tools')
    for _ in range(8):
        root, _ = ux.nodes()
        if ux.match(root, 'Developer tools') is not None:
            ux.tap('Developer tools')
            return
        ux.swipe(root, downward=False)
    raise AssertionError('Developer tools entry missing')


def open_file(action):
    command = ['am', 'start', '-W', '-n', ux.PACKAGE + '/com.mybrowser.MainActivity',
               '-a', 'android.intent.action.' + action, '-t', 'text/html', '--grant-read-uri-permission']
    # am does not migrate EXTRA_STREAM to ClipData; data carries the read grant.
    command += ['-d', uri]
    if action == 'SEND':
        command += ['--eu', 'android.intent.extra.STREAM', uri]
    ux.adb('shell', *shell_user, *command)
    expect_page('SCRIPT AND CSS PASSED')
    expect_page('本地 HTML 渲染')
    expect_page('SIBLING 404')


def clear_storage():
    ux.tap('More actions')
    ux.tap('Clear')
    ux.tap('Clear')
    ux.expect('No data')


def boundary_checks():
    ux.tap('Menu')
    root = ux.expect_menu()
    parent = {child: node for node in root.iter('node') for child in node}
    tile = ux.match(root, 'Block element')
    assert tile is not None
    while tile.get('enabled') != 'false' and tile in parent:
        tile = parent[tile]
    assert tile.get('enabled') == 'false'
    ux.close_menu()
    record('Local HTML disables saved element rules before entering the picker')
    ux.menu_item('Find')
    root, _ = ux.nodes()
    inputs = [n for n in root.iter('node') if n.get('class') == 'android.widget.EditText']
    assert len(inputs) == 1
    label = ux.resource_labels('ui_find_in_page')
    labelled = [n for n in root.iter('node') if n.get('content-desc') in label or n.get('text') in label]
    assert len(labelled) == 1, 'Find field exposes duplicate labels'
    ux.tap('Close')
    record('Find input exposes one accessible label')
    devtools()
    enable_capture()
    command("window.qaDom=window[Object.getOwnPropertyNames(window).find(k=>k.startsWith('__pureDevToolsDom_'))];window.qaDetails=qaDom.domDetails;qaDom.domDetails=()=>({});'malformed-ready'", 'malformed-ready')
    tool_tab('Elements')
    ux.tap('<html>')
    ux.expect('This DOM node is no longer valid. Refresh DOM.')
    shot('malformed-dom-details')
    command("qaDom.domDetails=qaDetails;'restored'", 'restored')
    tool_tab('Elements')
    ux.tap('<html>')
    ux.expect('Add attribute')
    record('Malformed page-owned DOM details show an error; restoring the accessor allows the next read')
    command("localStorage.clear();localStorage.setItem('x'.repeat(65537),'large-key');localStorage.setItem('qa-safe','safe-value');localStorage.length", '2')
    tool_tab('Storage')
    ux.tap('Refresh storage')
    ux.tap('Local Storage')
    ux.expect('qa-safe')
    contains('Loaded: 1')
    assert 'x' * 65537 not in ux.nodes()[1]
    ux.tap('qa-safe')
    ux.tap('Edit')
    field('dev_storage_value', 'updated-safe')
    back()
    ux.tap('Apply')
    command("localStorage.getItem('qa-safe')", 'updated-safe')
    tool_tab('Storage')
    ux.tap('Refresh storage')
    ux.tap('qa-safe')
    ux.tap('Edit')
    ux.tap('Remove')
    contains('Loaded: 0')
    ux.expect('No data', present=False)
    shot('oversized-storage-key')
    record('Oversized keys are omitted with a loaded count; valid keys still support editing and removal')
    command("for(var i=0;i<20;i++)localStorage.setItem('page-'+i,'v'.repeat(65536));localStorage.length", '21')
    tool_tab('Storage')
    ux.tap('Refresh storage')
    ux.tap('Local Storage')
    for _ in range(10):
        root, _ = ux.nodes()
        if ux.match(root, 'Load more') is not None:
            break
        ux.swipe(root, downward=False)
    ux.tap('Load more')
    for _ in range(10):
        root, _ = ux.nodes()
        if any('Loaded: 20' in n.get('text', '') for n in root.iter('node')):
            break
        listing = max((n for n in root.iter('node') if n.get('scrollable') == 'true'),
                      key=lambda n: ux.bounds(n)[3] - ux.bounds(n)[1])
        left, top, right, bottom = ux.bounds(listing)
        x = str((left + right) // 2)
        ux.adb('shell', 'input', 'swipe', x, str(top + (bottom - top) // 4),
               x, str(bottom - (bottom - top) // 4), '450')
    contains('Loaded: 20')
    record('Loading the last storage page preserves the count of entries omitted for oversized keys')
    command("localStorage.clear();delete window.qaDom;delete window.qaDetails;'cleaned'", 'cleaned')
    root, _ = ux.nodes()
    backs = [n for n in root.iter('node') if n.get('content-desc') in ux.resource_labels('cd_back')]
    ux.tap_node(backs[0])
    back()  # More tools returns to the main menu.
    ux.close_menu()


try:
    ux.launch()  # Complete first-run onboarding before opening the external HTML fixture.
    fixture = args.output / name
    fixture.write_bytes('''<!doctype html><meta charset="gb18030"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Local HTML test</title>
<style>body{font:20px sans-serif;background:#ecfdf5;margin:24px}#heading{color:rgb(0,128,0)}button{padding:12px}#box{border:2px solid;padding:8px}</style>
<h1 id="heading">本地 HTML 渲染</h1><div id="box">Editable text <b id="child">Child preserved</b></div><p id="result">SCRIPT NOT RUN</p>
<button onclick="document.querySelector('#result').textContent='CLICK PASSED'">Run local action</button>
<p><a href="?popup" target="_blank">Open file in new tab</a> <button onclick="window.close()">Close child</button></p><p id="route"></p>
<p><button onclick="window.open('?linked','localChild')">Open linked child</button> <a href="other.html">Unavailable local link</a></p>
<script>if(opener){var check=document.createElement('p');document.body.append(check);opener.fetch(opener.location.href).then(r=>r.text()).then(t=>check.textContent=t.includes('SCRIPT NOT RUN')?'OPENER FETCH OK':'OPENER FETCH WRONG').catch(()=>check.textContent='OPENER FETCH FAILED');var reload=document.createElement('button');reload.textContent='Reload opener';reload.onclick=()=>opener.location.reload();document.body.append(reload);}</script>
<script>document.querySelector('#route').textContent='Route '+location.search;document.querySelector('#result').textContent='SCRIPT '+(getComputedStyle(document.querySelector('#heading')).color==='rgb(0, 128, 0)'?'AND CSS PASSED':'CSS FAILED');fetch('sibling.txt').then(r=>document.body.insertAdjacentHTML('beforeend','<p>SIBLING '+r.status+'</p>'));console.log('local-fixture-log');console.warn('local-fixture-warning');</script>'''.encode('gb18030'))
    subprocess.run(ux.ADB + ['push', str(fixture), device_path], check=True)
    open_file('VIEW')
    boundary_checks()
    if args.boundaries_only:
        result['passed'] = True
        raise SystemExit(0)
    ux.tap('Run local action')
    expect_page('CLICK PASSED')
    ux.tap('Refresh')
    expect_page('SCRIPT AND CSS PASSED')
    shot('html-view')
    record('VIEW renders original GB18030 bytes, CSS and JavaScript; reload works and siblings return 404')
    # Terminate only renderers owned by this test app, leaving its Activity alive.
    def renderer_pids():
        processes = ux.adb('shell', 'dumpsys', 'activity', 'processes')
        return [re.search(r'ProcessRecord\{\S+ (\d+):', block)[1]
                for block in processes.split('*APP*')[1:]
                if 'sandboxed_process' in block.splitlines()[0]
                and 'packageList={' + ux.PACKAGE + '}' in block]
    old_renderers = renderer_pids()
    assert old_renderers, 'No renderer owned by the test application'
    for pid in old_renderers:
        ux.adb('shell', 'kill', '-9', pid)
    deadline = time.monotonic() + 20
    while not set(renderer_pids()).difference(old_renderers):
        assert time.monotonic() < deadline, 'Local HTML renderer was not recreated'
        time.sleep(.2)
    expect_page('SCRIPT AND CSS PASSED', timeout=15)
    expect_page('SIBLING 404')
    ux.tap('Run local action')
    expect_page('CLICK PASSED')
    record('Renderer termination recreates the WebView with its local HTML binding and working scripts')
    open_file('SEND')
    record('SEND opens the granted HTML stream in a browser tab')
    ux.tap('Open file in new tab')
    expect_page('Route ?popup', timeout=10)
    expect_page('SCRIPT AND CSS PASSED')
    expect_page('SIBLING 404')
    ux.tap('Show original')
    ux.expect('Preview')
    contains('<!doctype html>')
    ux.tap('Preview')
    expect_page('Route ?popup')
    ux.tap('Refresh')
    expect_page('Route ?popup')
    shot('html-popup')
    ux.tap('Close child')
    expect_page('Route')
    record('Local HTML popup renders the same file, reloads, shows original source and returns to its opener; siblings stay inaccessible')
    ux.tap('Open linked child')
    expect_page('Route ?linked', timeout=10)
    expect_page('OPENER FETCH OK', timeout=10)
    ux.tap('Reload opener')
    time.sleep(1)
    ux.tap('Close child')
    expect_page('SCRIPT AND CSS PASSED', timeout=10)
    expect_page('SIBLING 404')
    shot('html-background-opener')
    record('Background local opener fetch and reload use the granted file')
    ux.tap('Refresh')
    ux.expect('Unavailable local link', timeout=10)
    root, _ = ux.nodes()
    link = ux.match(root, 'Unavailable local link')
    x1, y1, x2, y2 = ux.bounds(link)
    x, y = str((x1+x2)//2), str((y1+y2)//2)
    ux.adb('shell', 'input', 'swipe', x, y, x, y, '1200')
    ux.tap('Share link')
    ux.expect('No app available to share this link')
    record('Sharing a sibling link cannot send the current HTML file')
    devtools()
    enable_capture()
    tool_tab('Elements')
    ux.tap('<html>')
    ux.tap('Add attribute')
    field('dev_storage_name', 'data-root-qa')
    field('dev_storage_value', 'edited')
    back()
    ux.tap('Apply')
    contains('data-root-qa')
    back()
    contains('<html data-root-qa="edited">')
    record('Editing the HTML root updates its tree row without collapsing the loaded children')
    root, _ = ux.nodes()
    collapse = min((n for n in root.iter('node') if ux.visible(n) and n.get('clickable') == 'true'
                    and any(c.get('content-desc', '').startswith('Collapse ') for c in n.iter('node'))),
                   key=lambda n: len(list(n.iter('node'))))
    density = int(re.findall(r'(?:Physical|Override) density: (\d+)', ux.adb('shell', 'wm', 'density'))[-1]) / 160
    x1, y1, x2, y2 = ux.bounds(collapse)
    assert x2-x1 >= 48*density-1 and y2-y1 >= 48*density-1, 'DOM expand touch target is smaller than 48dp'
    ux.tap_node(collapse)
    root, _ = ux.nodes()
    expand = next(n for n in root.iter('node') if ux.visible(n) and n.get('content-desc', '').startswith('Expand '))
    ux.tap_node(expand)
    record('DOM expansion has a 48dp touch target and distinct expand/collapse accessibility labels')
    ux.tap('Inspect element')
    ux.tap('Child preserved')
    expect_page('Child preserved')
    ux.tap('Select the surrounding element')
    ux.tap('Select back inside the element')
    shot('picked-element')
    root, _ = ux.nodes()
    actions = [n for n in root.iter('node') if ux.visible(n) and n.get('text') in ux.resource_labels('dev_dom_inspect')]
    ux.tap_node(actions[-1])
    ux.expect('Add attribute')
    ux.tap('Back')
    root, _ = ux.nodes()
    refresh = next(n for n in root.iter('node') if ux.visible(n)
                   and n.get('content-desc') in ux.resource_labels('dev_dom_refresh'))
    ux.tap_node(refresh)
    ux.expect('Add attribute', present=False)
    ux.tap('Inspect element')
    ux.tap('Child preserved')
    root, _ = ux.nodes()
    actions = [n for n in root.iter('node') if ux.visible(n) and n.get('text') in ux.resource_labels('dev_dom_inspect')]
    ux.tap_node(actions[-1])
    ux.tap('Add attribute')
    field('dev_storage_name', 'data-qa')
    field('dev_storage_value', 'changed')
    back()
    ux.tap('Apply')
    contains('data-qa')
    ux.tap('data-qa')
    field('dev_storage_value', 'edited-existing')
    back()
    ux.tap('Apply')
    contains('edited-existing')
    ux.tap('Add inline style')
    field('dev_storage_name', 'color')
    field('dev_storage_value', 'rgb(255, 0, 0) !important')
    back()
    ux.tap('Apply')
    command("document.querySelector('#child').getAttribute('data-qa')+'|'+getComputedStyle(document.querySelector('#child')).color+'|'+document.querySelector('#child').style.getPropertyPriority('color')",
            'edited-existing|rgb(255, 0, 0)|important')
    shot('dom-write-result')
    record('Picker restores DOM selection; refreshing the tree exits old details and reselecting permits attribute/important-style edits')
    field('dev_console_search', 'local-fixture-warning')
    back()
    root, _ = ux.nodes()
    assert not any('local-fixture-log' in n.get('text', '') for n in root.iter('node') if ux.visible(n))
    contains('local-fixture-warning')
    field('dev_console_search', '')
    back()
    ux.tap('Warnings')
    root, _ = ux.nodes()
    assert not any('local-fixture-log' in n.get('text', '') for n in root.iter('node') if ux.visible(n))
    command('6*7', '42')
    record('Manual command result remains visible with the Warnings filter selected')
    ux.tap('All')
    ux.tap('Command history')
    ux.tap('6*7')
    assert wait_editor('ui_enter_a_javascript_command').get('text') == '6*7'
    ux.tap('Run')
    contains('6*7\n42')
    record('Console search, level filtering and command history reuse work')
    command("localStorage.setItem('long-qa','x'.repeat(5000));localStorage.getItem('long-qa').length", '5000')
    tool_tab('Storage')
    ux.tap('Refresh storage')
    ux.tap('Local Storage')
    ux.tap('long-qa')
    ux.tap('Edit')
    assert wait_editor('dev_storage_value').get('text') == 'x' * 5000
    field('dev_storage_value', 'x' * 5000 + 'z')
    back()
    ux.tap('Apply')
    ux.expect('Edit')
    ux.expect('Developer tools', present=False)
    record('Applying a storage edit keeps its detail page open')
    command("localStorage.getItem('long-qa').length+'|'+localStorage.getItem('long-qa').slice(-1)", '5001|z')
    tool_tab('Storage')
    ux.tap('Refresh storage')
    ux.tap('long-qa')
    ux.tap('Edit')
    ux.tap('Remove')
    ux.expect('No data')
    ux.tap('Add')
    field('dev_storage_name', 'qa-key')
    field('dev_storage_value', 'first-value')
    back()
    ux.tap('Apply')
    ux.expect('qa-key')
    clear_storage()
    ux.tap('Session Storage')
    ux.tap('Add')
    field('dev_storage_name', 'session-qa')
    field('dev_storage_value', 'session-value')
    back()
    ux.tap('Apply')
    command("sessionStorage.getItem('session-qa')+'|'+localStorage.getItem('session-qa')", 'session-value|null')
    shot('storage-write-result')
    tool_tab('Storage')
    ux.tap('Refresh storage')
    clear_storage()
    record('Local Storage edits use full 5000-character values; add/delete/confirmed clear work and Session Storage stays separate')
    back()
    back()
    back()
    ux.tap('Refresh')
    expect_page('SCRIPT AND CSS PASSED')
    devtools()
    command("document.querySelector('#child').getAttribute('data-qa')+'|'+document.querySelector('#child').style.color", 'null|')
    record('Reload discards temporary DOM edits')
    command("var xmlNode=document.createElementNS('urn:pure:review','review-node');xmlNode.id='xml-review';xmlNode.textContent='XML element';document.body.prepend(xmlNode);typeof xmlNode.style", 'undefined')
    tool_tab('Elements')
    ux.tap('Inspect element')
    ux.tap('XML element')
    root, _ = ux.nodes()
    actions = [n for n in root.iter('node') if ux.visible(n) and n.get('text') in ux.resource_labels('dev_dom_inspect')]
    ux.tap_node(actions[-1])
    ux.expect('Add attribute')
    ux.expect('Add inline style', present=False)
    ux.expect('Box model')
    shot('xml-node-details')
    ux.tap('Add attribute')
    field('dev_storage_name', 'data-qa')
    field('dev_storage_value', 'xml-edited')
    back()
    ux.tap('Apply')
    command("document.getElementById('xml-review').getAttribute('data-qa')", 'xml-edited')
    record('XML Element without style supports native layout/details and attribute editing without offering inline styles')
    # Refresh must replace stale search ids without changing the query.
    tool_tab('Elements')
    field('dev_dom_search', '#child')
    back()
    ux.tap('<b>')
    contains('id="child"')
    ux.tap('Refresh DOM')
    ux.tap('<b>')
    contains('id="child"')
    tool_tab('Network')
    tool_tab('Elements')
    assert wait_editor('dev_dom_search').get('text') == '#child'
    contains('id="child"')
    record('DOM refresh redoes an unchanged search; switching tool tabs retains query and expanded path')
    field('dev_dom_search', '')
    back()
    command("document.body.appendChild(document.createComment('source-page\\n'.repeat(6000)));'large-source'", 'large-source')
    tool_tab('Elements')
    ux.tap('Refresh DOM')
    root, _ = ux.nodes()
    html = next(n for n in root.iter('node') if ux.visible(n) and n.get('text', '').startswith('<html'))
    ux.tap_node(html)
    ux.tap('Copy node HTML')
    contains('Copied the first 65,536 characters')
    record('Copying a large DOM node reports its truncation')
    ux.tap('Back')
    ux.tap('DOM source')
    def source_range():
        deadline = time.monotonic() + 10
        while True:
            root, _ = ux.nodes()
            found = [n.get('text') for n in root.iter('node') if ux.visible(n) and n.get('text', '').startswith('Lines ')]
            if found:
                return found[0]
            assert time.monotonic() < deadline, 'Source range missing'
    first = source_range()
    ux.tap('Next')
    second = source_range()
    assert second != first, 'Source did not advance'
    tool_tab('Network')
    tool_tab('Elements')
    assert source_range() == second, 'Switching tabs reset the source chunk'
    ux.tap('Previous')
    assert source_range() == first, 'Source history did not return to the first chunk'
    shot('source-restored')
    record('Source chunk and history survive switching tool tabs')
    result['passed'] = True
except Exception as error:
    result['error'] = str(error)
    shot('failure')
    raise
finally:
    ux.adb('shell', 'rm', '-f', device_path)
    (args.output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
