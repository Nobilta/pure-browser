#!/usr/bin/env python3
"""Touch regression for deferred diagnostics subscriptions and console state."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Use a local test emulator'
ux.ADB = ['adb', '-s', args.serial]
sdk = ux.adb('shell', 'getprop', 'ro.build.version.sdk')
stem = ROOT / 'results' / ('api' + sdk + '-devtools')
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


def enter(command):
    root, _ = ux.nodes()
    field = next(n for n in root.iter('node') if ux.visible(n) and n.get('class') == 'android.widget.EditText')
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
               if n.get('class') == 'android.widget.EditText'):
            time.sleep(.3)
            return
        time.sleep(.25)
    raise AssertionError('Console field did not accept ' + command)


def back():
    ux.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.5)


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


try:
    orient('0')
    ux.adb('reverse', 'tcp:8875', 'tcp:8875')
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    ux.launch('http://127.0.0.1:8875/browser-ux.html')
    ux.expect('Pure UX First Page')
    ux.menu_item('Developer tools')
    for label in ('Console', 'Network', 'Source', 'Info'):
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
    ux.tap('Source')
    wait_text('Pure UX First Page')
    snapshot('source')
    ux.tap('Info')
    wait_text('Pure UX First Page')
    ux.tap('Console')
    wait_text('6*7\n42')
    record('source and page information load; console results survive tab changes')
    enter("setTimeout(()=>console.log('pure-audit-hidden'),4000)")
    back()
    ux.tap('Run')
    ux.tap('Close')
    ux.expect_menu()
    time.sleep(4.1)
    ux.menu_item('Developer tools')
    wait_text('pure-audit-hidden')
    snapshot('reopened-console')
    record('console messages recorded with diagnostics closed appear after reopening')
    orient('1')
    enter('10+10')
    width, height = snapshot('landscape-keyboard')
    assert width > height, 'Rotation did not take effect'
    ux.tap('Run')
    wait_text('10+10\n20')
    record('landscape keyboard leaves Run accessible and shows the latest result')
    back()
    ux.expect('Developer tools')
    ux.expect('Network')
    snapshot('landscape-toolbar')
    record('dismissing the landscape keyboard restores the diagnostics toolbar')
    ux.adb('shell', 'input', 'keyevent', '3')
    ux.launch()
    ux.expect('Developer tools')
    wait_text('10+10\n20')
    record('returning to the app without a URL preserves the open diagnostics panel')
    ux.launch('http://127.0.0.1:8875/browser-ux.html?page=external')
    ux.expect('Developer tools', present=False)
    ux.expect('Menu')
    ux.expect('Pure UX external Page')
    snapshot('external-view')
    record('an external VIEW URL closes diagnostics and immediately reveals its destination')
    ux.menu_item('Developer tools')
    ux.expect('Developer tools')
    ux.adb('shell', 'am', 'start', '-W', '-n', ux.PACKAGE + '/com.mybrowser.MainActivity',
           '-a', 'android.intent.action.SEND', '-t', 'text/plain', '--es', 'android.intent.extra.TEXT',
           'http://127.0.0.1:8875/browser-ux.html?page=shared')
    ux.expect('Developer tools', present=False)
    ux.expect('Menu')
    ux.expect('Pure UX shared Page')
    snapshot('external-shared')
    record('a shared URL closes diagnostics and reveals the shared destination')
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
