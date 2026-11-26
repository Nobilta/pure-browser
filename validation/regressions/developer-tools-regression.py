#!/usr/bin/env python3
"""Touch regression for deferred diagnostics subscriptions and console state."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import struct
import sys
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='com.mybrowser')
parser.add_argument('--layout-only', action='store_true', help='Only check 320dp/200% and landscape layouts')
parser.add_argument('--port', type=int, default=8875, help='Local static fixture server port')
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Use a local test emulator'
ux.ADB = ['adb', '-s', args.serial]
ux.PACKAGE = args.package
sdk = ux.adb('shell', 'getprop', 'ro.build.version.sdk')
stem = ROOT / 'results' / ('api' + sdk + '-devtools' + ('-layout' if args.layout_only else ''))
apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
result = {'serial': args.serial, 'sdk': sdk,
          'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': [], 'error': None}
original = {name: ux.adb('shell', 'settings', 'get', 'system', name)
            for name in ('accelerometer_rotation', 'user_rotation')}


def record(message):
    result['checks'].append(message)
    print('PASS:', message, flush=True)


def wait_text(fragment):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        root, _ = ux.nodes()
        if any(fragment in node.get('text', '') for node in root.iter('node') if ux.visible(node)):
            return
        time.sleep(.2)
    raise AssertionError('Diagnostics text missing: ' + fragment)


def snapshot(name):
    path = Path(str(stem) + '-' + name)
    path.with_suffix('.xml').write_text(ux.nodes()[1], encoding="utf-8")
    png = subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20)
    path.with_suffix('.png').write_bytes(png)
    return struct.unpack('>II', png[16:24])


def labelled_field(node, resource):
    # Compose can expose the label on a semantic child of the editable node.
    return (ux.visible(node) and node.get('class') == 'android.widget.EditText' and
            any(child.get('content-desc') in ux.resource_labels(resource) for child in node.iter()))


def enter(command):
    root, _ = ux.nodes()
    field = next(n for n in root.iter('node') if labelled_field(n, 'ui_enter_a_javascript_command'))
    x1, y1, x2, y2 = ux.bounds(field)
    ux.adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    # `input text` goes through the device shell, which ends the argument at the first
    # metacharacter: a JavaScript command arrived as just "setTimeout". Set the field
    # through the accessibility action instead, then read back what actually landed.
    encoded = base64.b64encode(command.encode()).decode()
    for _ in range(3):
        try:
            ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
                   'com.mybrowser.validation.FastUiDump', 'setText', encoded)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass  # An old ART helper can fail while shutting down after the edit already landed.
        if any(n.get('text') == command for n in ux.nodes()[0].iter('node')
               if labelled_field(n, 'ui_enter_a_javascript_command')):
            time.sleep(.3)
            return
        time.sleep(.25)
    raise AssertionError('Console field did not accept ' + command)


def back():
    ux.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.5)


def copy_console_entry(fragment):
    root, _ = ux.nodes()
    node = next(node for node in root.iter('node') if ux.visible(node)
                and node.get('class') == 'android.widget.TextView' and fragment in node.get('text', ''))
    expected = node.get('text')
    # A webpage log selects body and its secondary metadata as one entry.
    if fragment == 'pure-audit-hidden':
        parent = {child: owner for owner in root.iter('node') for child in owner}
        owner = parent[node]
        expected = '\n'.join(n.get('text') for n in owner.iter('node') if n.get('text'))
    x1, y1, x2, y2 = ux.bounds(node)
    x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
    ux.adb('shell', 'input', 'swipe', x, y, x, y, '1200')
    # Android's floating text toolbar is a separate accessibility window.
    for label in ('Select all', 'Copy'):
        deadline = time.monotonic() + 20
        while True:
            root, _ = ux.window_nodes()
            action = ux.match(root, label)
            if action is not None:
                break
            assert time.monotonic() < deadline, 'Text selection action missing: ' + label
            time.sleep(.2)
        ux.tap_node(action)
        time.sleep(.3)
    # Dismiss the system clipboard preview outside the bottom input area.
    ux.tap('Console')
    enter('')
    ux.adb('shell', 'input', 'keyevent', '279')  # Paste into the visible console field.
    root, _ = ux.nodes()
    field = next(node for node in root.iter('node') if labelled_field(node, 'ui_enter_a_javascript_command'))
    assert field.get('text') == expected, 'Clipboard does not match the complete console entry'
    enter('')
    back()


def open_devtools():
    """The row lives inside the menu's More tools page, not the top-level sheet."""
    ux.menu_item('More tools')
    for _ in range(8):
        root, _ = ux.nodes(await_labels=['Developer tools'], timeout_ms=500)
        if ux.match(root, 'Developer tools') is not None:
            ux.tap('Developer tools')
            time.sleep(.5)
            return
        ux.swipe(root, downward=False)
    raise AssertionError('Developer tools row missing from More tools')


def orient(rotation):
    # Settings writes and the display's rotation observer do not complete together.
    # Confirm the actual locked display before UIAutomation connects or opens the IME.
    ux.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    ux.adb('shell', 'settings', 'put', 'system', 'user_rotation', rotation)
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        display = ux.adb('shell', 'dumpsys', 'window', 'displays')
        png = subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20)
        width, height = struct.unpack('>II', png[16:24])
        if 'mUserRotationMode=USER_ROTATION_LOCKED' in display and (width > height) == (rotation == '1'):
            return
        time.sleep(.2)
    raise AssertionError('System did not apply requested display rotation: ' + rotation)


def choose_tab(label):
    for _ in range(5):
        root, _ = ux.nodes()
        if ux.match(root, label) is not None:
            ux.tap(label)
            return
        anchor = next(n for n in root.iter('node') if ux.visible(n) and n.get('text') in ('Console', 'Network', 'Elements', 'Storage'))
        left, top, right, bottom = ux.bounds(anchor)
        width = int(ux.adb('shell', 'wm', 'size').split()[-1].split('x')[0])
        ux.adb('shell', 'input', 'swipe', str(width - 30), str((top + bottom) // 2), '30', str((top + bottom) // 2), '300')
    raise AssertionError('Tab inaccessible: ' + label)


def compact_layout_checks():
    font = ux.adb('shell', 'settings', 'get', 'system', 'font_scale')
    try:
        orient('0')
        ux.adb('shell', 'wm', 'size', '960x1600')
        ux.adb('shell', 'wm', 'density', '480')
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
        ux.adb('shell', 'cmd', 'uimode', 'night', 'yes')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.launch(f'http://127.0.0.1:{args.port}/browser-ux.html')
        open_devtools()
        snapshot('compact-dark-console')
        choose_tab('Network')
        wait_text('browser-ux.html')
        ux.tap('browser-ux.html')
        ux.expect('Developer tools', present=False)
        ux.expect('Request details')
        snapshot('compact-dark-network-detail')
        ux.tap('Back')
        choose_tab('Elements')
        ux.expect('Enable and reload')
        snapshot('compact-dark-elements-guide')
        ux.tap('Enable and reload')
        # The half-height controls can scroll independently from the DOM content.
        wait_text('<html')
        snapshot('compact-dark-dom')
        record('320dp at 200% text in dark mode keeps root content, capture action and one detail header accessible')
        orient('1')
        root, _ = ux.nodes()
        controls = next(n for n in root.iter('node') if n.get('scrollable') == 'true' and ux.match(n, 'Developer tools') is not None)
        left, top, right, bottom = ux.bounds(controls)
        x = str((left + right) // 2)
        ux.adb('shell', 'input', 'swipe', x, str(bottom - 12), x, str(top + 12), '450')
        ux.expect('Inspect element')
        ux.expect('Refresh DOM')
        snapshot('compact-dark-landscape-dom')
        record('Landscape at 200% text keeps DOM actions reachable by scrolling the controls')
    finally:
        ux.adb('shell', 'wm', 'size', 'reset')
        ux.adb('shell', 'wm', 'density', 'reset')
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', font if font != 'null' else '1.0')
        ux.adb('shell', 'cmd', 'uimode', 'night', 'no')
        orient('0')


try:
    if args.layout_only:
        compact_layout_checks()
        sys.exit(0)
    orient('0')
    ux.adb('reverse', f'tcp:{args.port}', f'tcp:{args.port}')
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    ux.launch(f'http://127.0.0.1:{args.port}/browser-ux.html')
    ux.expect('Pure UX First Page')
    open_devtools()
    for label in ('Help', '使用说明', '使用說明', 'Page information', '页面信息', '頁面資訊', 'Close'):
        ux.expect(label, present=False)
    record('developer tools header contains no help, page information or close actions')
    for label in ('Console', 'Network', 'Elements', 'Storage'):
        ux.expect(label)
    enter('6*7')
    back()
    ux.tap('Network')
    wait_text('browser-ux.html')
    snapshot('network')
    record('opening diagnostics collects requests recorded while the panel was hidden')
    ux.tap('Console')
    ux.expect('6*7')
    ux.tap('Run')
    wait_text('6*7\n42')
    record('console draft survives tab changes and JavaScript execution returns 42')
    copy_console_entry('6*7\n42')
    record('console execution results support long-press selection and clipboard copy')
    ux.tap('Elements')
    ux.expect('Enable capture to view DOM and source.')
    snapshot('elements-guide')
    ux.tap('Storage')
    wait_text('Cookies')
    ux.tap('Local Storage')
    wait_text('Local Storage')
    width, height = snapshot('storage')
    for _ in range(3):
        ux.adb('shell', 'input', 'swipe', str(width // 2), str(height // 2),
               str(width // 2), str(height * 9 // 10), '180')
        ux.expect('Developer tools')
        ux.expect('Local Storage')
    record('scrolling toward the top repeatedly does not dismiss fullscreen developer tools')
    root, _ = ux.nodes()
    field = next(n for n in root.iter('node') if labelled_field(n, 'dev_storage_search'))
    before = ux.bounds(field)
    ux.tap_node(field)
    time.sleep(.4)
    root, _ = ux.nodes()
    focused = next(n for n in root.iter('node') if labelled_field(n, 'dev_storage_search'))
    assert ux.bounds(focused) == before, 'Search field moved or resized after focus'
    back()
    record('search keeps the same bounds before and after focus')
    ux.tap('Console')
    wait_text('6*7\n42')
    record('elements guide and storage load; console results survive tab changes')
    enter("for(var i=0;i<30;i++)console.log('scroll-check-'+i)")
    back()
    ux.tap('Run')
    wait_text('scroll-check-29')
    root, _ = ux.nodes()
    listing = max((n for n in root.iter('node') if n.get('scrollable') == 'true'), key=lambda n: ux.bounds(n)[3] - ux.bounds(n)[1])
    left, top, right, bottom = ux.bounds(listing)
    x = str((left + right) // 2)
    ux.adb('shell', 'input', 'swipe', x, str(top + (bottom - top) // 4), x, str(bottom - (bottom - top) // 4), '450')
    time.sleep(.6)
    def visible_logs():
        return [n.get('text') for n in ux.nodes()[0].iter('node') if ux.visible(n) and n.get('text', '').startswith('scroll-check-')]
    before = visible_logs()
    assert before, 'Log scroll did not reveal page messages'
    ux.tap('Network')
    ux.tap('Console')
    assert visible_logs() == before, 'Returning to Console jumped away from its reading position'
    record('Console retains its reading position across tool tab changes after command execution')

    ux.tap('Clear')
    enter("setTimeout(()=>console.log('pure-audit-hidden'),4000)")
    back()
    ux.tap('Run')
    back()
    ux.expect('More tools')
    ux.expect('Console', present=False)
    record('system Back exits developer tools to More tools')
    back()
    back()
    ux.expect('Menu')
    time.sleep(4.1)
    open_devtools()
    wait_text('pure-audit-hidden')
    snapshot('reopened-console')
    record('console messages recorded with diagnostics closed appear after reopening')
    copy_console_entry('pure-audit-hidden')
    record('webpage console logs support long-press selection and clipboard copy')
    for cycle in range(3):
        orient('1')
        enter('10+10')
        width, height = snapshot('landscape-keyboard-' + str(cycle))
        assert width > height, 'Rotation did not take effect'
        ux.tap('Run')
        wait_text('10+10\n20')
        back()
        ux.expect('Developer tools')
        ux.expect('Network')
        wait_text('10+10\n20')
        if cycle < 2:
            orient('0')
    record('three landscape keyboard cycles keep Run accessible and show the latest result')
    snapshot('landscape-toolbar')
    record('dismissing the landscape keyboard restores the diagnostics toolbar')
    ux.adb('shell', 'input', 'keyevent', '3')
    ux.launch()
    ux.expect('Developer tools')
    wait_text('10+10\n20')
    record('returning to the app without a URL preserves the open diagnostics panel')
    ux.launch(f'http://127.0.0.1:{args.port}/browser-ux.html?page=external')
    ux.expect('Developer tools', present=False)
    ux.expect('Menu')
    ux.expect('Pure UX external Page')
    snapshot('external-view')
    record('an external VIEW URL closes diagnostics and immediately reveals its destination')
    open_devtools()
    ux.expect('Developer tools')
    ux.adb('shell', 'am', 'start', '-W', '-n', ux.PACKAGE + '/com.mybrowser.MainActivity',
           '-a', 'android.intent.action.SEND', '-t', 'text/plain', '--es', 'android.intent.extra.TEXT',
           f'http://127.0.0.1:{args.port}/browser-ux.html?page=shared')
    ux.expect('Developer tools', present=False)
    ux.expect('Menu')
    ux.expect('Pure UX shared Page')
    snapshot('external-shared')
    record('a shared URL closes diagnostics and reveals the shared destination')
    compact_layout_checks()
except Exception as error:
    result['error'] = str(error)
    snapshot('failure-' + str(time.time_ns()))
    raise
finally:
    for name, value in original.items():
        if value == 'null':
            ux.adb('shell', 'settings', 'delete', 'system', name)
        else:
            ux.adb('shell', 'settings', 'put', 'system', name, value)
    stem.with_suffix('.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding="utf-8")
