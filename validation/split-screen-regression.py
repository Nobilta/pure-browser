#!/usr/bin/env python3
"""Exercise both real browser tasks in Android's split-screen organizer."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    assert a.serial.startswith('emulator-')
    ux.ADB = ['adb', '-s', a.serial]
    assert int(ux.adb('shell', 'getprop', 'ro.build.version.sdk')) >= 31
    a.output.mkdir(parents=True, exist_ok=True)
    key = 'split-' + str(time.time_ns())
    base = 'http://127.0.0.1:8875/'
    checks = []
    main_task = None
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'checks': checks, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0]}

    def wm(*args):
        return ux.adb('shell', 'dumpsys', 'activity', 'service',
                      'com.android.systemui/.SystemUIService', 'WMShell', 'splitscreen', *args)

    def wait(name, condition=lambda s: True, since=0):
        end = time.monotonic() + 20
        last = None
        while time.monotonic() < end:
            rows = json.load(urllib.request.urlopen(base + '__state?case=' + key, timeout=4))
            rows = [r for r in rows if r.get('name') == name and r['receivedAt'] > since]
            last = rows[-1] if rows else None
            if last and last['ready'] == 'complete' and condition(last):
                return last
            time.sleep(.3)
        raise AssertionError((name, last))

    def record(name, **details):
        checks.append({'check': name, **details})
        print('PASS', name, details, flush=True)

    def windows():
        raw = ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
                     '/system/bin', 'com.mybrowser.validation.FastUiDump', 'windows')
        return ET.fromstring(raw), raw

    def pane(name):
        root, _ = windows()
        return next(n for n in root if ux.match(n, 'Resident ' + name) is not None)

    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.launch(base + 'resident-fixture.html?case=' + key + '&name=SplitA')
        first = wait('SplitA')
        ux.menu_item('Open other window')
        ux.tap('Edit address')
        ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
               '/system/bin', 'com.mybrowser.validation.FastUiDump', 'selectAll')
        ux.adb('shell', 'input', 'text', base + 'resident-fixture.html?case=' + key + '&name=SplitB')
        ux.adb('shell', 'input', 'keyevent', '66')
        second = wait('SplitB')
        ux.menu_item('Open other window')
        tasks = ux.adb('shell', 'dumpsys', 'activity', 'activities')
        main_task = re.search(r'com\.mybrowser/[^ ]*MainActivity t(\d+)', tasks)[1]
        side_task = re.search(r'com\.mybrowser/[^ ]*SecondaryActivity t(\d+)', tasks)[1]
        assert main_task != side_task
        split_at = time.time()
        wm('moveToSideStage', side_task, '1')
        time.sleep(1)
        activity = ux.adb('shell', 'dumpsys', 'activity', 'activities')
        (a.output / 'split-activities.txt').write_text(activity)
        assert activity.count('mLastReportedMultiWindowMode=true') >= 2, 'Both activities must enter multi-window'
        a_now = wait('SplitA', since=split_at)
        b_now = wait('SplitB', since=split_at)
        assert a_now['token'] == first['token'] and b_now['token'] == second['token']
        record('Both split-screen documents remain alive and continue their page timers')
        ux.tap_node(ux.match(pane('SplitA'), 'Prepare form and SPA'))
        a_now = wait('SplitA', lambda s: s['spa'] == 1)
        b_now = wait('SplitB')
        assert a_now['draft'] == 'Unsubmitted SplitA' and b_now['spa'] == 0
        ux.tap_node(ux.match(pane('SplitB'), 'Prepare form and SPA'))
        b_now = wait('SplitB', lambda s: s['spa'] == 1)
        assert b_now['draft'] == 'Unsubmitted SplitB'
        record('Real taps update only the selected window form and SPA state')
        (a.output / 'split.xml').write_text(windows()[1])
        (a.output / 'split.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
        wm('exitSplitScreen', main_task)
        time.sleep(1)
        ux.expect('Resident SplitA')
        assert wait('SplitA')['token'] == first['token']
        ux.menu_item('Open other window')
        ux.expect('Resident SplitB')
        assert wait('SplitB')['token'] == second['token']
        ux.menu_item('Exit browser')
        ux.launch()
        ux.expect('Resident SplitA')
        record('Leaving split screen and closing the secondary window preserve the main document')
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        raise
    finally:
        (a.output / 'result.json').write_text(json.dumps(result, indent=2))
        if main_task is not None:
            wm('exitSplitScreen', main_task)


if __name__ == '__main__':
    main()
