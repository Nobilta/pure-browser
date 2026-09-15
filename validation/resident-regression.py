#!/usr/bin/env python3
"""Retained WebView acceptance: DOM, unsent form, SPA memory, scroll and paused media."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec); spec.loader.exec_module(ux)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser.debug')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); assert args.serial.startswith('emulator-')
    ux.ADB = ['adb', '-s', args.serial]; ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    key = 'resident-' + str(time.time_ns()); base = 'http://127.0.0.1:8875/'
    ux.adb('reverse', 'tcp:8875', 'tcp:8875')
    def wait(name, condition, since=0, timeout=15):
        end = time.monotonic() + timeout; last = None
        while time.monotonic() < end:
            events = json.load(urllib.request.urlopen(base+'__state?case='+key, timeout=4))
            events = [r for r in events if r.get('name') == name and r['receivedAt'] > since]
            last = events[-1] if events else None
            if last and condition(last): return last
            time.sleep(.3)
        raise AssertionError((name, last))
    def tabs():
        root, _ = ux.nodes()
        prefixes = [d['tabs_count'].split('%1$d')[0] for d in ux._translations]
        button = next(n for n in root.iter('node') if ux.visible(n) and any(n.get('content-desc','').startswith(p) for p in prefixes))
        ux.tap_node(button)
    def focused_editor(timeout=10):
        # `input text` is delivered to whatever holds focus. tap_node only sleeps 0.5s, which
        # is not enough for a freshly opened dialog to take focus on a software-rendered or
        # memory-pressured emulator, so the text would be dropped and the check would fail
        # for reasons unrelated to the app.
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            root, _ = ux.nodes()
            node = next((n for n in root.iter('node') if n.get('class') == 'android.widget.EditText'
                         and n.get('focused') == 'true' and ux.visible(n)), None)
            if node is not None: return node
            time.sleep(.2)
        raise AssertionError('no editable field took focus')
    checks = []
    try:
        ux.adb('shell','am','force-stop',args.package)
        ux.launch(base+'resident-fixture.html?case='+key+'&name=A')
        wait('A',lambda r:r['ready']=='complete')
        web_tap=ux.tap
        web_tap('Prepare form and SPA'); web_tap('Play video')
        wait('A',lambda r:r['spa']==1 and not r['paused'] and r['time']>0)
        web_tap('Scroll article')
        before = wait('A',lambda r:r['scroll']>=1000)
        ux.adb('shell','input','keycombination','113','48')  # Ctrl+T
        time.sleep(.7)
        ux.launch(base+'resident-fixture.html?case='+key+'&name=B')
        wait('B',lambda r:r['ready']=='complete')
        tabs(); ux.expect('Page kept in memory'); ux.tap('Resident A')
        switched = time.time()
        after = wait('A',lambda r:r['ready']=='complete',since=switched)
        for field in ('token','started','draft','spa','state'):
            assert after[field]==before[field], (field,before,after)
        assert abs(after['scroll']-before['scroll'])<5, (before,after)
        assert after['paused'], 'Leaving a retained tab did not pause its media'
        checks.append('DOM, form, SPA memory and scroll survive a two-tab roundtrip; departed media is paused')
        # Group and order actions are real UI operations; returning to the tab must
        # retain the same document even when its list position changes.
        tabs()
        root,_=ux.nodes()
        row=next(n for n in root.iter('node') if ux.match(n,'Resident A') is not None and
                 ux.match(n,'Item actions') is not None and len(list(n.iter('node')))<30)
        ux.tap_node(ux.match(row,'Item actions')); ux.tap('Tab group'); ux.tap('Group name')
        focused_editor()
        ux.adb('shell','input','text','QAgroup'); ux.adb('shell','input','keyevent','4'); ux.tap('Confirm')
        ux.expect('QAgroup')
        checks.append('Tab group can be assigned and selected in the visible tab list')
        ux.adb('shell', 'input', 'keyevent', '4')

        def unchanged_source():
            returned = wait('A', lambda r: r['ready'] == 'complete', since=time.time())
            for field in ('token', 'started', 'draft', 'spa', 'state'):
                assert returned[field] == before[field], (field, before, returned)
            assert abs(returned['scroll'] - before['scroll']) < 5, (before, returned)
            assert returned['paused'], 'Returning to the source resumed its background media'
            return returned

        opened = time.time()
        ux.tap('Open popup child')
        wait('Popup', lambda r: r['ready'] == 'complete', since=opened)
        ux.adb('shell', 'input', 'keyevent', '4')
        unchanged_source()
        checks.append('window.open child closes with system Back and returns to the original DOM, form, SPA and scroll')

        opened = time.time()
        ux.tap('Open link child')
        wait('Linked', lambda r: r['ready'] == 'complete', since=opened)
        ux.tap('Navigate within tab')
        wait('LinkedNext', lambda r: r['ready'] == 'complete', since=opened)
        back_at = time.time()
        ux.adb('shell', 'input', 'keyevent', '4')
        wait('Linked', lambda r: r['ready'] == 'complete', since=back_at)
        ux.adb('shell', 'input', 'keyevent', '4')
        unchanged_source()
        checks.append('target=_blank child exhausts its own history before Back returns to the retained opener')

        opened = time.time()
        ux.tap('Open popup child')
        wait('Popup', lambda r: r['ready'] == 'complete', since=opened)
        ux.tap('Close popup window')
        unchanged_source()
        checks.append('window.close returns to the retained opener instead of exiting the browser')

        opened = time.time()
        ux.tap('Open popup child')
        wait('Popup', lambda r: r['ready'] == 'complete', since=opened)
        ux.tap('Back')
        unchanged_source()
        checks.append('Toolbar Back closes a child with no page history and preserves the opener')

        opened = time.time()
        ux.tap('Open popup child')
        wait('Popup', lambda r: r['ready'] == 'complete', since=opened)
        tabs()
        root, _ = ux.nodes()
        rows = [n for n in root.iter('node') if ux.match(n, 'Resident Popup') is not None and ux.match(n, 'Close tab') is not None]
        row = min(rows, key=lambda n: len(list(n.iter('node'))))
        ux.tap_node(ux.match(row, 'Close tab'))
        assert ux.match(ux.nodes()[0], 'Resident Popup') is None
        ux.adb('shell', 'input', 'keyevent', '4')
        unchanged_source()
        checks.append('The tab list close button returns to the opener and leaves no exit prompt')

        # Same-tab navigation and Back. Every case above crosses a tab boundary; this one
        # stays inside the tab, so Back is a real document navigation rather than a switch
        # to a retained page. Android WebView has no back/forward cache, so the document is
        # rebuilt and unsent form state is lost. Assert the contract the platform actually
        # provides and record the identity delta, so any change here shows up in result.json
        # instead of passing silently.
        ux.tap('Navigate within tab')
        same_tab_next = wait('ANext', lambda r: r['ready'] == 'complete', since=time.time())
        assert same_tab_next['name'] == 'ANext', same_tab_next
        same_tab_back_at = time.time()
        ux.adb('shell', 'input', 'keyevent', '4')
        same_tab_back = wait('A', lambda r: r['ready'] == 'complete', since=same_tab_back_at)
        assert same_tab_back['name'] == 'A' and same_tab_back['ready'] == 'complete', same_tab_back
        same_tab = {
            'tokenBefore': before['token'], 'tokenAfter': same_tab_back['token'],
            'documentRebuilt': same_tab_back['token'] != before['token'],
            'draftBefore': before['draft'], 'draftAfter': same_tab_back['draft'],
            'spaBefore': before['spa'], 'spaAfter': same_tab_back['spa'],
            'scrollBefore': before['scroll'], 'scrollAfter': same_tab_back['scroll'],
        }
        checks.append('Same-tab Back returns to the previous page and leaves it interactive')
        (args.output/'result.json').write_text(json.dumps({'passed':True,'checks':checks,'before':before,
            'after':after,'sameTabBack':same_tab},indent=2))
        print(json.dumps(checks),flush=True)
    finally:
        (args.output/'last-screen.png').write_bytes(subprocess.check_output(ux.ADB+['exec-out','screencap','-p'],timeout=20))
        (args.output/'last-screen.xml').write_text(ux.nodes()[1])

if __name__=='__main__': main()
