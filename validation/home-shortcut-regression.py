#!/usr/bin/env python3
"""Exercise shortcut editing on a rooted emulator and restore its homepage preferences."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import sqlite3
import struct
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Fixture data is restricted to emulators'
ux.ADB = ['adb', '-s', args.serial]
assert ux.adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
print(ux.adb('root'), flush=True)
ux.adb('wait-for-device')
sdk = ux.adb('shell', 'getprop', 'ro.build.version.sdk')
output = ROOT / 'results'
output.mkdir(exist_ok=True)
data = '/data/user/0/' + ux.PACKAGE
prefs = data + '/shared_prefs/browser_settings.xml'
base = 'http://127.0.0.1:8875/productivity-fixture.html'
checks = []
failure = None


def record(name):
    checks.append(name)
    print('PASS:', name, flush=True)


def screenshot(name):
    _, raw = ux.nodes()
    prefix = output / ('api' + sdk + '-home-shortcut-' + name)
    prefix.with_suffix('.xml').write_text(raw)
    prefix.with_suffix('.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))


def back():
    ux.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.5)


def restart():
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    ux.adb('shell', 'am', 'start', '-W', '-n', ux.PACKAGE + '/.MainActivity')
    time.sleep(.8)


def long_press(label):
    root, _ = ux.nodes()
    node = ux.match(root, label)
    assert node is not None, label
    x1, y1, x2, y2 = ux.bounds(node)
    x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
    ux.adb('shell', 'input', 'swipe', x, y, x, y, '850')
    ux.expect('Edit shortcut')


def edit(value, index, hide_keyboard=True):
    root, _ = ux.nodes()
    fields = [n for n in root.iter('node') if ux.visible(n) and n.get('class') == 'android.widget.EditText']
    x1, y1, x2, y2 = ux.bounds(fields[index])
    ux.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    ux.adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '/system/bin',
           'com.mybrowser.validation.FastUiDump', 'selectAll')
    ux.adb('shell', 'input', 'keyevent', 'KEYCODE_DEL')
    ux.adb('shell', 'input', 'text', value.replace(' ', '%s'))
    time.sleep(.3)
    if index == 1:
        screenshot('keyboard')
    if hide_keyboard:
        back()


def records():
    root = ET.fromstring(ux.adb('shell', 'cat', prefs))
    return json.loads(root.find("string[@name='homepage_shortcuts']").text)


def icon_files():
    return set(ux.adb('shell', 'ls', data + '/files/homepage_icons').splitlines())


def push_preferences(raw):
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / 'preferences.xml'
        path.write_bytes(raw)
        ux.adb('push', str(path), prefs)
    ux.adb('shell', 'chown', owner, prefs)
    ux.adb('shell', 'restorecon', prefs)


def bookmarks():
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / 'browser.db'
        ux.adb('pull', data + '/databases/browser.db', str(path))
        for suffix in ('-wal', '-shm'):
            subprocess.run(ux.ADB + ['pull', data + '/databases/browser.db' + suffix, str(path) + suffix], capture_output=True)
        with sqlite3.connect(path) as conn:
            return conn.execute('SELECT id,title,url,created_at FROM bookmarks ORDER BY id').fetchall()


def choose_image(name):
    ux.tap('Choose image')
    ux.choose_download_document(name)
    ux.expect('Edit shortcut')
    time.sleep(.7)


def png_chunk(kind, payload):
    return struct.pack('!I', len(payload)) + kind + payload + struct.pack('!I', zlib.crc32(kind + payload))


ux.adb('reverse', 'tcp:8875', 'tcp:8875')
ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
original = ux.adb('shell', 'cat', prefs).encode()
owner = ux.adb('shell', 'stat', '-c', '%u:%g', prefs)
original_bookmarks = bookmarks()
original_icons = icon_files()
apk_path = ux.adb('shell', 'pm', 'path', ux.PACKAGE).splitlines()[0].removeprefix('package:')
apk_sha256 = ux.adb('shell', 'sha256sum', apk_path).split()[0]
old_font = ux.adb('shell', 'settings', 'get', 'system', 'font_scale')
old_rotation = ux.adb('shell', 'settings', 'get', 'system', 'user_rotation')
old_auto = ux.adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation')
fixture_records = [
    {'id': 'shortcut-fixture-one', 'title': 'Shortcut One', 'url': base, 'createdAt': 1000},
    {'id': 'shortcut-fixture-two', 'title': 'Shortcut Two', 'url': base + '?page=second', 'createdAt': 2000},
]
try:
    root = ET.fromstring(original)
    for name in ('homepage_shortcuts', 'homepage_mode', 'restore_last_session'):
        existing = root.find("*[@name='" + name + "']")
        if existing is not None:
            root.remove(existing)
    ET.SubElement(root, 'string', name='homepage_shortcuts').text = json.dumps(fixture_records)
    ET.SubElement(root, 'string', name='homepage_mode').text = 'NAVIGATION'
    ET.SubElement(root, 'boolean', name='restore_last_session', value='false')
    push_preferences(ET.tostring(root, encoding='utf-8', xml_declaration=True))
    with tempfile.TemporaryDirectory() as folder:
        image = Path(folder) / 'shortcut-editor-fixture.png'
        image.write_bytes(b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('!IIBBBBB', 2400, 1600, 8, 2, 0, 0, 0)) +
            png_chunk(b'IDAT', zlib.compress(b''.join(b'\0' + bytes([24, 152, 112] if y < 800 else [245, 187, 48]) * 2400
                                                    for y in range(1600)))) + png_chunk(b'IEND', b''))
        ux.adb('push', str(image), '/sdcard/Download/' + image.name)
        bad = Path(folder) / 'shortcut-editor-invalid.png'
        bad.write_bytes(b'This is not an image')
        ux.adb('push', str(bad), '/sdcard/Download/' + bad.name)

    ux.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    ux.adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    restart()
    ux.expect('Shortcut One')
    long_press('Shortcut One')
    for label in ('Choose image', 'Use letter icon', 'Title', 'URL', 'Remove from homepage'):
        ux.expect(label)
    screenshot('editor')
    record('long press opens one editor containing title, address, icon and removal')
    edit('Uncommitted Draft', 0)
    edit('javascript:alert(1)', 1, hide_keyboard=False)
    ux.tap('Save')
    ux.expect('Enter a valid HTTP or HTTPS address')
    ux.tap('Cancel')
    assert records() == fixture_records
    record('invalid address is rejected and cancelling leaves stored values unchanged')

    long_press('Shortcut One')
    choose_image('shortcut-editor-fixture.png')
    edit('Image Draft', 0)
    ux.tap('Cancel')
    assert records() == fixture_records and icon_files() == original_icons
    long_press('Shortcut Two')
    ux.expect('Shortcut Two')
    ux.expect(base + '?page=second')
    ux.tap('Cancel')
    record('cancelling an image draft writes no files and editing another tile starts with its own values')

    long_press('Shortcut One')
    edit('Shortcut Edited', 0)
    edit(base + '?page=second', 1)
    ux.tap('Save')
    ux.expect('This address is already on the homepage')
    assert records() == fixture_records
    edit(base + '?page=edited', 1)
    ux.tap('Choose image')
    back()
    ux.expect('Shortcut Edited')
    record('duplicate address is rejected and cancelling the image picker preserves the draft')

    choose_image('shortcut-editor-invalid.png')
    ux.expect('Unable to read or save this image. Choose another image.')
    choose_image('shortcut-editor-fixture.png')
    ux.expect('Unable to read or save this image. Choose another image.', present=False)
    screenshot('image-preview')
    ux.adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
    time.sleep(1.3)
    ux.expect('Edit shortcut')
    screenshot('landscape')
    ux.adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    time.sleep(1)
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    time.sleep(1.3)
    ux.expect('Shortcut Edited')
    screenshot('large-font')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    time.sleep(1)
    ux.tap('Save')
    ux.expect('Edit shortcut', present=False)
    saved = records()
    assert saved[0]['id'] == fixture_records[0]['id'] and saved[0]['createdAt'] == 1000
    assert saved[0]['title'] == 'Shortcut Edited' and saved[0]['url'] == base + '?page=edited'
    assert saved[0]['customIcon'] is True and saved[1]['id'] == fixture_records[1]['id']
    icon = subprocess.check_output(ux.ADB + ['exec-out', 'cat', data + '/files/homepage_icons/' + saved[0]['icon']])
    assert struct.unpack('!II', icon[16:24]) == (96, 64)
    restart()
    ux.expect('Shortcut Edited')
    assert records() == saved
    screenshot('saved-home')
    record('custom image is downsampled to 96x64; draft survives rotation and font changes; save survives restart')

    ux.tap('Shortcut Edited')
    ux.expect('Productivity edited')
    ux.tap('Homepage')
    long_press('Shortcut Edited')
    ux.tap('Remove from homepage')
    ux.tap('Cancel')
    ux.expect('Shortcut Edited')
    ux.tap('Use letter icon')
    ux.tap('Save')
    assert records()[0]['icon'] is None
    assert saved[0]['icon'] not in icon_files()
    record('edited address opens correctly; deletion can be cancelled; letter icon removes the saved image')

    long_press('Shortcut Edited')
    ux.tap('Remove from homepage')
    ux.tap('Remove')
    ux.expect('Shortcut Edited', present=False)
    ux.expect('Shortcut Two')
    assert [item['id'] for item in records()] == ['shortcut-fixture-two']
    assert icon_files() == original_icons
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    assert bookmarks() == original_bookmarks
    record('confirmed removal deletes only the selected tile and preserves all bookmarks')
except Exception as error:
    failure = repr(error)
    screenshot('failure')
    raise
finally:
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    for name in icon_files() - original_icons:
        ux.adb('shell', 'rm', '-f', data + '/files/homepage_icons/' + name)
    push_preferences(original)
    for value, name in ((old_font, 'font_scale'), (old_rotation, 'user_rotation'), (old_auto, 'accelerometer_rotation')):
        if value == 'null':
            ux.adb('shell', 'settings', 'delete', 'system', name)
        else:
            ux.adb('shell', 'settings', 'put', 'system', name, value)
    for name in ('shortcut-editor-fixture.png', 'shortcut-editor-invalid.png'):
        ux.adb('shell', 'rm', '-f', '/sdcard/Download/' + name)
    (output / ('api' + sdk + '-home-shortcut.json')).write_text(json.dumps({
        'timestamp': datetime.now(timezone.utc).isoformat(), 'serial': args.serial, 'sdk': int(sdk),
        'apk_sha256': apk_sha256, 'checks': checks, 'error': failure,
    }, indent=2))
