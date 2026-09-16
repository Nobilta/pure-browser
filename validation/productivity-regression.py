#!/usr/bin/env python3
"""Touch regression of the signed APK; fixture database seeding requires a rooted emulator."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import time
import urllib.request
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--section', choices=['all', 'browser', 'library'], default='all')
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'This regression seeds fixture records on emulators only'
ux.ADB = ['adb', '-s', args.serial]
sdk = ux.adb('shell', 'getprop', 'ro.build.version.sdk')
output = ROOT / 'results'
output.mkdir(exist_ok=True)
checks = []
run = str(time.time_ns())
base = 'http://127.0.0.1:8875/productivity-fixture.html?run=' + run


def record(name):
    checks.append(name)
    print('PASS:', name, flush=True)


def screenshot(name):
    _, raw = ux.nodes()
    prefix = output / ('api' + sdk + '-productivity-' + name)
    prefix.with_suffix('.xml').write_text(raw, encoding="utf-8")
    prefix.with_suffix('.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))


def back():
    ux.adb('shell', 'input', 'keyevent', '4')
    time.sleep(.5)


def long_press(label, page=None):
    target = {'Background destination': 'background', 'Linked test image': 'linked', 'Plain test image': 'plain'}[label]
    url = base if page is None else base.replace('?run=', '?page=' + page + '&run=')
    ux.tap_fixture(label, 'productivity-geometry-' + run, target, url, hold_ms=900)


def edit(text, index=0):
    root, _ = ux.nodes()
    fields = [n for n in root.iter('node') if ux.visible(n) and n.get('class') == 'android.widget.EditText']
    x1, y1, x2, y2 = ux.bounds(fields[index])
    ux.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    ux.adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    select_all()
    ux.adb('shell', 'input', 'keyevent', 'KEYCODE_DEL')
    if text:
        ux.adb('shell', 'input', 'text', text.replace(' ', '%s'))
    time.sleep(.45)


def select_all():
    ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '/system/bin',
           'com.mybrowser.validation.FastUiDump', 'selectAll')


def tabs():
    root, _ = ux.nodes()
    node = next(n for n in root.iter('node') if ux.visible(n) and
                n.get('content-desc', '').startswith(('Tabs', '标签页', '分頁')))
    x1,y1,x2,y2 = ux.bounds(node)
    ux.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.6)


def requests_for(page):
    events = json.load(urllib.request.urlopen('http://127.0.0.1:8875/__state?case=context-requests'))
    return [event for event in events if parse_qs(urlparse(event['path']).query).get('run') == [run]
            and parse_qs(urlparse(event['path']).query).get('page') == [page]]


def close_background():
    root, _ = ux.nodes()
    parents = {child: parent for parent in root.iter() for child in parent}
    assert parents[ux.match(root, 'Back')].get('enabled') == 'false', 'Fresh tab inherited back history'
    tabs()
    screenshot('before-close')
    edit('page=background')
    back()
    ux.tap('Close tab')
    screenshot('after-close')
    back()


def seed_libraries():
    print(ux.adb('root'), flush=True)
    ux.adb('wait-for-device')
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    db = '/data/user/0/' + ux.PACKAGE + '/databases/browser.db'
    owner = ux.adb('shell', 'stat', '-c', '%u:%g', db)
    with tempfile.TemporaryDirectory() as folder:
        local = Path(folder) / 'browser.db'
        ux.adb('pull', db, str(local))
        for suffix in ('-wal', '-shm'):
            subprocess.run(ux.ADB + ['pull', db + suffix, str(local) + suffix], capture_output=True)
        with sqlite3.connect(local) as conn:
            conn.execute('PRAGMA wal_checkpoint(TRUNCATE)')
            conn.execute("DELETE FROM bookmarks WHERE url LIKE 'https://productivity.test/%'")
            conn.execute("DELETE FROM history WHERE url LIKE 'https://productivity.test/%'")
            now = int(time.time()*1000)
            # Match normal bookmark insertion: new rows precede existing folder
            # entries. Creation time alone no longer controls the root list order.
            first_position = conn.execute('SELECT COALESCE(MIN(position), 0) - 1 FROM bookmarks WHERE folder_id = 0').fetchone()[0]
            for i in range(123):
                title = 'Archive Needle' if i == 0 else f'Library {i:03d}'
                url = f'https://productivity.test/{i}'
                conn.execute('INSERT INTO bookmarks(title,url,created_at,position,host) VALUES(?,?,?,?,?)',
                             (title,url,now+i,first_position-i,'productivity.test'))
                conn.execute('INSERT INTO history(title,url,visit_time,visit_count,host) VALUES(?,?,?,1,?)',
                             (title,url,now+i,'productivity.test'))
        conn.close()
        ux.adb('push', str(local), db)
    for suffix in ('-wal', '-shm'):
        ux.adb('shell', 'rm', '-f', db + suffix)
    ux.adb('shell', 'chown', owner, db)
    ux.adb('shell', 'restorecon', db)
    ux.launch(base)


def browser_checks():
    ux.adb('reverse', 'tcp:8875', 'tcp:8875')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    ux.launch(base)
    tabs()
    ux.tap('Tab actions')
    ux.tap('Close all')
    ux.tap('Confirm')
    ux.launch(base)
    long_press('Background destination')
    ux.expect('Open in background')
    screenshot('link-menu')
    ux.tap('Open in background')
    ux.expect('Productivity First')
    time.sleep(1)
    assert not requests_for('background'), 'Background tab navigated before selection'
    record('background opening is lazy and leaves the foreground page unchanged')
    tabs()
    ux.expect('Tabs (2)')
    edit('page=background')
    back()
    screenshot('tab-search')
    ux.tap('127.0.0.1')
    ux.expect('Productivity background')
    assert requests_for('background'), 'Selected background tab did not navigate'
    close_background()
    ux.expect('Productivity First')
    tabs()
    ux.expect('Tabs (1)')
    back()
    record('filtered tab selection uses identity and fresh tabs have independent back history')
    ux.tap_fixture('Second destination', 'productivity-geometry-' + run, 'second', base)
    ux.expect('Productivity second')
    long_press('Background destination', page='second')
    ux.tap('Open in new tab')
    ux.expect('Productivity background')
    close_background()
    ux.expect('Productivity second')
    back()
    ux.expect('Productivity First')
    record('closing a foreground tab restores the previous tab and its back history')
    long_press('Linked test image')
    ux.expect('Open in new tab')
    ux.expect('Save image')
    screenshot('linked-image')
    ux.tap('Copy image link')
    ux.tap('Edit address')
    select_all()
    ux.adb('shell', 'input', 'keyevent', 'KEYCODE_PASTE')
    ux.expect('http://127.0.0.1:8875/context-image.png')
    back()
    back()
    record('linked images expose separate link and image targets; clipboard has the image URL')
    long_press('Plain test image')
    ux.expect('Open in new tab', present=False)
    before = set(ux.adb('shell', 'ls', '/sdcard/Download').splitlines())
    ux.tap('Save image')
    expected = hashlib.sha256(urllib.request.urlopen('http://127.0.0.1:8875/context-image.png').read()).hexdigest()
    deadline = time.monotonic() + 20
    verified = False
    while time.monotonic() < deadline:
        after = set(ux.adb('shell', 'ls', '/sdcard/Download').splitlines())
        for name in after - before:
            if name.startswith('context-image'):
                verified |= ux.adb('shell', 'sha256sum', '/sdcard/Download/' + name).split()[0] == expected
        if verified:
            break
        time.sleep(.5)
    assert verified, 'Image download did not match server bytes'
    record('plain image menu saves the correct image through MediaStore')
    long_press('Background destination')
    ux.tap('Share link')
    _, raw = ux.nodes()
    assert 'ChooserActivity' in ux.adb('shell', 'dumpsys', 'activity', 'activities') or 'resolver' in raw.lower()
    screenshot('share')
    back()
    record('share opens the Android chooser without sending a message')
    long_press('Background destination')
    ux.tap('Open in background')
    tabs()
    ux.tap('Tab actions')
    ux.tap('Close other tabs')
    ux.tap('Cancel')
    ux.expect('Tabs (2)')
    ux.tap('Tab actions')
    ux.tap('Close other tabs')
    ux.tap('Confirm')
    ux.expect('Tabs (1)')
    screenshot('tabs')
    back()
    record('bulk tab closure supports cancel and confirmation')
    ux.tap('Menu')
    screenshot('menu')
    back()


def library_checks():
    seed_libraries()
    ux.menu_item('Bookmarks')
    ux.expect('Library 122')
    edit('Needle')
    back()
    ux.expect('Archive Needle')
    screenshot('bookmarks-search')
    ux.tap('Item actions')
    ux.tap('Edit bookmark')
    edit('Archive Edited')
    ux.tap('Save')
    ux.expect('Edit bookmark', present=False)
    edit('Edited')
    back()
    ux.expect('Archive Edited')
    ux.tap('Item actions')
    ux.tap('Edit bookmark')
    edit('https://productivity.test/1', index=1)
    ux.tap('Save')
    ux.expect('Edit bookmark')
    ux.tap('Cancel')
    ux.expect('Archive Edited')
    record('bookmark search reaches old records; editing preserves identity and duplicate failure keeps the editor')
    ux.tap('Clear all bookmarks')
    ux.expect('Delete all bookmarks? This cannot be undone.')
    ux.tap('Cancel')
    ux.expect('Archive Edited')
    edit('Library')
    back()
    for _ in range(24):
        root, _ = ux.nodes()
        if ux.match(root, 'Load more') is not None:
            break
        ux.swipe(root, downward=False)
    ux.tap('Load more')
    ux.expect('Library 072')
    record('bookmark paging loads the next database page and clear-all cancellation preserves records')
    back()
    ux.menu_item('History')
    ux.expect('Today')
    edit('Needle')
    back()
    ux.expect('Archive Needle')
    ux.tap('Clear history')
    ux.expect(ux._translations[0]['history_clear_confirm'])
    ux.tap('Cancel')
    screenshot('history-search')
    record('history is grouped by date, searches old records, and confirms clear-all')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    time.sleep(1)
    root, _ = ux.nodes()
    if ux.match(root, 'Search history') is None:
        ux.menu_item('History')
    ux.expect('Search history')
    screenshot('large-font')
    record('library controls remain visible at 1.3 font scale')


try:
    ux.adb('reverse', 'tcp:8875', 'tcp:8875')
    if args.section in ('all', 'browser'):
        browser_checks()
    if args.section in ('all', 'library'):
        library_checks()
finally:
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    (output / ('api' + sdk + '-productivity-' + args.section + '.json')).write_text(json.dumps(checks, indent=2), encoding="utf-8")
