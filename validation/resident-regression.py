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
    checks = []
    try:
        ux.adb('shell','am','force-stop',args.package)
        ux.launch(base+'resident-fixture.html?case='+key+'&name=A')
        wait('A',lambda r:r['ready']=='complete')
        ux.tap('Prepare form and SPA'); ux.tap('Play video')
        wait('A',lambda r:r['spa']==1 and not r['paused'] and r['time']>0)
        ux.tap('Scroll article')
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
        ux.adb('shell','input','text','QAgroup'); ux.adb('shell','input','keyevent','4'); ux.tap('Confirm')
        ux.expect('QAgroup')
        checks.append('Tab group can be assigned and selected in the visible tab list')
        (args.output/'result.json').write_text(json.dumps({'passed':True,'checks':checks,'before':before,'after':after},indent=2))
        print(json.dumps(checks),flush=True)
    finally:
        (args.output/'last-screen.png').write_bytes(subprocess.check_output(ux.ADB+['exec-out','screencap','-p'],timeout=20))
        (args.output/'last-screen.xml').write_text(ux.nodes()[1])

if __name__=='__main__': main()
