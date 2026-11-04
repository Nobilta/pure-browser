#!/usr/bin/env python3
"""Real-touch bookmark organizing on a rooted local emulator with isolated fixture rows."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import struct
import subprocess
import tempfile
import time
import zlib

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec); spec.loader.exec_module(ux)


def run(serial, package):
    assert serial.startswith('emulator-')
    ux.ADB = ['adb', '-s', serial]; ux.PACKAGE = package
    assert ux.adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    ux.adb('root'); ux.adb('wait-for-device'); ux.adb('reverse', 'tcp:8875', 'tcp:8875')
    out = ROOT / 'results'; out.mkdir(exist_ok=True)
    data = '/data/user/0/' + package
    db = data + '/databases/browser.db'
    icon_url = 'http://127.0.0.1:8875/results/bookmark-icon-fixture.html'
    result = {'serial': serial, 'package': package, 'checks': [], 'error': None}
    apk = ux.adb('shell', 'pm', 'path', package).partition(':')[2].strip()
    result['apkSha256'] = ux.adb('shell', 'sha256sum', apk).split()[0]
    result['androidApi'] = ux.adb('shell', 'getprop', 'ro.build.version.sdk')

    def record(message):
        result['checks'].append(message); print('PASS:', message, flush=True)

    def database(edit=None):
        if edit: ux.adb('shell', 'am', 'force-stop', package)
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / 'browser.db'; ux.adb('pull', db, str(p))
            for suffix in ('-wal', '-shm'):
                subprocess.run(ux.ADB + ['pull', db + suffix, str(p) + suffix], capture_output=True)
            with sqlite3.connect(p) as conn:
                if edit:
                    edit(conn); conn.commit(); conn.execute('PRAGMA wal_checkpoint(TRUNCATE)'); conn.execute('PRAGMA journal_mode=DELETE')
                    value = None
                else:
                    value = {
                        'bookmarks': conn.execute("SELECT title FROM bookmarks WHERE folder_id=(SELECT id FROM bookmark_folders WHERE title='Drag QA folder 1') ORDER BY position,id").fetchall(),
                        'folders': conn.execute("SELECT title FROM bookmark_folders WHERE title LIKE 'Drag QA folder %' ORDER BY position,id").fetchall(),
                    }
            if edit:
                owner = ux.adb('shell', 'stat', '-c', '%u:%g', db)
                ux.adb('push', str(p), db)
                for suffix in ('-wal', '-shm'): ux.adb('shell', 'rm', '-f', db + suffix)
                ux.adb('shell', 'chown', owner, db); ux.adb('shell', 'restorecon', db)
            return value

    def seed(conn):
        conn.execute("DELETE FROM bookmarks WHERE url LIKE 'https://bookmark-drag.test/%' OR url=?", (icon_url,))
        conn.execute("DELETE FROM bookmark_folders WHERE title LIKE 'Drag QA folder %'")
        for i in range(1, 4):
            conn.execute('INSERT INTO bookmark_folders(title,parent_id,position) VALUES(?,0,?)', (f'Drag QA folder {i}', -100+i))
        folder = conn.execute("SELECT id FROM bookmark_folders WHERE title='Drag QA folder 1'").fetchone()[0]
        for i in range(1, 76):
            conn.execute('INSERT INTO bookmarks(title,url,created_at,position,folder_id,host) VALUES(?,?,?,?,?,?)',
                         (f'Drag QA {i:03}', f'https://bookmark-drag.test/{i}', int(time.time()*1000), i, folder, 'bookmark-drag.test'))
        conn.execute('INSERT INTO bookmarks(title,url,created_at,position,folder_id,host) VALUES(?,?,?,0,0,?)',
                     ('Drag QA icon', icon_url, int(time.time()*1000), '127.0.0.1'))

    def back(): ux.adb('shell', 'input', 'keyevent', '4'); time.sleep(.4)
    def long_press(label):
        # Search input and its result can have identical text. Touch the row title,
        # not the editable query field (which only opens text selection).
        node = next((n for n in ux.nodes()[0].iter('node') if ux.visible(n)
                     and n.get('class') == 'android.widget.TextView' and n.get('text') == label), None)
        assert node is not None, label
        x1,y1,x2,y2 = ux.bounds(node); x,y = str((x1+x2)//2),str((y1+y2)//2)
        ux.adb('shell', 'input', 'swipe', x,y,x,y,'850'); time.sleep(.5)
    def handles():
        return [n for n in ux.nodes()[0].iter('node') if ux.visible(n) and n.get('content-desc') in ux.resource_labels('bookmarks_drag')]
    def drag(index, target, hold=0, cancel=False):
        h = handles(); x1,y1,x2,y2 = ux.bounds(h[index]); x,y = (x1+x2)//2,(y1+y2)//2
        if target == 'edge':
            scroll = next(n for n in ux.nodes()[0].iter('node') if n.get('scrollable')=='true')
            end = ux.bounds(scroll)[3]-15
        else:
            a,b,c,d=ux.bounds(h[target]); end=(b+d)//2 + (55 if target > index else -55)
        ux.adb('shell','env','CLASSPATH='+ux.UI_PROBE,'app_process','-Xusejit:false','/system/bin',
               'com.mybrowser.validation.FastUiDump','dragCancel' if cancel else 'dragHold',str(x),str(y),str(x),str(end),'1200',str(hold))
        time.sleep(.8)
    def screenshot(name):
        _,xml = ux.nodes(); (out / ('bookmark-'+name+'.xml')).write_text(xml)
        (out / ('bookmark-'+name+'.png')).write_bytes(subprocess.check_output(ux.ADB+['exec-out','screencap','-p']))
    def search(value):
        node = next(n for n in ux.nodes()[0].iter('node') if n.get('class')=='android.widget.EditText')
        ux.tap_node(node)
        time.sleep(.4)
        ux.adb('shell','env','CLASSPATH='+ux.UI_PROBE,'app_process','-Xusejit:false','/system/bin',
               'com.mybrowser.validation.FastUiDump','setText',base64.b64encode(value.encode()).decode())
        time.sleep(.4)
        if 'mInputShown=true' in ux.adb('shell', 'dumpsys', 'input_method'): back()
        time.sleep(.4)
    def selection():
        return {next((c.get('text') for c in n.iter('node') if c.get('text','').startswith('Drag QA ')), '')
                for n in ux.nodes()[0].iter('node') if n.get('checkable')=='true' and n.get('checked')=='true'}

    try:
        ux.launch(); database(seed); ux.launch(); ux.menu_item('Bookmarks')
        ux.expect('Clear all bookmarks', present=False); ux.expect('All folders', present=False)
        ux.tap_resource('bookmarks_transfer')
        for key in ('bookmarks_new_folder','bookmarks_import','bookmarks_export'): ux.expect(ux.resource_strings('values')[key])
        ux.expect('Select bookmarks', present=False); back()
        record('Root has no clear-all or root breadcrumb; menu contains create/import/export')
        long_press('Drag QA folder 1'); assert handles()
        drag(0, 2)
        folders = [r[0] for r in database()['folders']]
        assert folders.index('Drag QA folder 1') > 0, folders
        back(); ux.tap('Drag QA folder 1'); ux.expect('Drag QA 001'); screenshot('normal')
        record('Long-press folder enables sibling folder reordering')
        long_press('Drag QA 001'); assert 'Drag QA 001' in selection()
        ux.tap('Drag QA 002'); assert {'Drag QA 001','Drag QA 002'} <= selection()
        original_bounds = ux.bounds(ux.match(ux.nodes()[0], 'Drag QA 001'))
        before=database()['bookmarks']; drag(0, 3, cancel=True)
        assert database()['bookmarks']==before
        restored_bounds = ux.bounds(ux.match(ux.nodes()[0], 'Drag QA 001'))
        assert all(abs(a-b) <= 2 for a,b in zip(original_bounds, restored_bounds)), (original_bounds, restored_bounds)
        record('Back during a held drag restores the order and original viewport')
        screenshot('selection'); drag(0, 3)
        rows = [r[0] for r in database()['bookmarks']]
        assert 1 <= rows.index('Drag QA 001') <= 4, rows[:8]
        assert {'Drag QA 001','Drag QA 002'} <= selection()
        record('Handle drag saves one item and preserves multi-selection; short drag does not jump pages')
        drag(rows.index('Drag QA 001'), 0)
        assert database()['bookmarks'][0] == ('Drag QA 001',)
        record('Upward drag returns the bookmark to the first position')
        drag(0, 'edge', 13000)
        rows = [r[0] for r in database()['bookmarks']]
        assert rows.index('Drag QA 001') >= 50 and len(rows)==75 and len(set(rows))==75, rows
        visible = [n.get('text') for n in ux.nodes()[0].iter('node') if n.get('text','').startswith('Drag QA ')]
        assert any(f'Drag QA {i:03}' in visible for i in range(50,76)), visible
        screenshot('edge')
        record('Edge drag loads beyond row 50, persists all 75 rows and retains viewport')
        back(); back(); ux.tap('Drag QA folder 1')
        assert database()['bookmarks']==[(x,) for x in rows]
        ux.expect(rows[0])
        search('Drag QA 002'); long_press('Drag QA 002')
        assert 'Drag QA 002' in selection()
        assert not handles(); back()
        search(''); ux.expect('Drag QA 003')
        record('Reopening retains order; global search supports selection without drag handles')
        # One real page supplies an icon through WebView; the library itself makes no requests.
        def chunk(kind, payload):
            return struct.pack('!I',len(payload))+kind+payload+struct.pack('!I',zlib.crc32(kind+payload))
        png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',32,32,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+bytes([24,190,105])*32)*32))+chunk(b'IEND',b'')
        (out/'bookmark-icon-fixture.png').write_bytes(png)
        (out/'bookmark-icon-fixture.html').write_text('<!doctype html><title>Drag QA icon</title><link rel="icon" type="image/png" href="bookmark-icon-fixture.png"><h1>Bookmark icon fixture</h1>')
        back(); back(); ux.launch(icon_url); ux.expect('Bookmark icon fixture'); time.sleep(1)
        name=hashlib.sha256(icon_url.encode()).hexdigest()+'.png'
        ux.adb('shell','test','-f',data+'/cache/bookmark-icons/'+name)
        ux.menu_item('Bookmarks'); search('Drag QA icon'); screenshot('icon')
        record('Visited bookmark caches and displays the WebView website icon')
    except Exception as error:
        result['error']=str(error); raise
    finally:
        (out/('bookmark-drag-'+serial+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--serial',required=True);parser.add_argument('--package',default='com.mybrowser')
    args=parser.parse_args();run(args.serial,args.package)
