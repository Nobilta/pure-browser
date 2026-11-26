#!/usr/bin/env python3
"""Measure retained tab switches and app/renderer PSS with the existing local resident fixture.

Requires QA server on 8875 and a dedicated emulator. The saved startup preference must open
the home page. Measurements include ADB delivery, two page animation frames and telemetry;
they are not physical-device frame times. No arbitrary tab retention cap is imposed.
"""
import argparse
import json
from pathlib import Path
import re
import statistics
import shlex
import subprocess
import time
import urllib.request

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--package', default='com.mybrowser.debug')
p.add_argument('--tabs', type=int, default=10)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
assert a.serial.startswith('emulator-') and a.tabs >= 2
adb = ['adb', '-s', a.serial]


def run(*args):
    command = ['shell', shlex.join(args[1:])] if args[0] == 'shell' else list(args)
    return subprocess.check_output(adb + command, text=True, timeout=30).strip()


def pss(process):
    raw = run('shell', 'dumpsys', 'meminfo', str(process))
    match = re.search(r'TOTAL PSS:\s*(\d+)', raw) or re.search(r'^\s*TOTAL\s+(\d+)', raw, re.M)
    assert match, raw
    return int(match[1])


key = 'tab-benchmark-' + str(time.time_ns())
base = 'http://127.0.0.1:8875/'


def painted(name, since):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        events = json.load(urllib.request.urlopen(base + '__state?case=' + key, timeout=4))
        match = next((e for e in events if e.get('name') == name and e.get('painted')
                      and e.get('visible') and e['receivedAt'] >= since), None)
        if match:
            return match
        time.sleep(.05)
    raise AssertionError('No visible paint for ' + name)


run('reverse', 'tcp:8875', 'tcp:8875')
run('shell', 'am', 'force-stop', a.package)
uid = re.search(r'package:' + re.escape(a.package) + r' uid:(\d+)',
                run('shell', 'pm', 'list', 'packages', '-U', a.package))[1]
memory, tokens, switches = [], {}, []
for i in range(a.tabs):
    name = str(i)
    start = time.time()
    run('shell', 'am', 'start', '-W', '-n', a.package + '/com.mybrowser.MainActivity',
        '-a', 'android.intent.action.VIEW', '-d', base + 'resident-fixture.html?case=' + key + '&name=' + name)
    event = painted(name, start)
    tokens[name] = event['token']
    if i + 1 in {1, a.tabs // 2, a.tabs}:
        time.sleep(.5)
        isolated = json.loads(run('shell', 'am', 'get-isolated-pids', uid))
        memory.append({'tabs': i + 1, 'appPssKb': pss(a.package),
                       'rendererPssKb': sum(pss(pid) for pid in isolated)})
        print(memory[-1], flush=True)

for i in range(a.tabs):
    start = time.time()
    run('shell', 'input', 'keycombination', '113', '61')  # Ctrl+Tab
    event = painted(str(i), start)
    assert event['token'] == tokens[str(i)], 'Retained tab was reloaded: ' + str(i)
    switches.append(round((event['receivedAt'] - start) * 1000, 2))

apk = run('shell', 'pm', 'path', a.package).partition(':')[2].strip()
result = {'apkSha256': run('shell', 'sha256sum', apk).split()[0], 'memory': memory,
          'switchMs': switches, 'medianSwitchMs': statistics.median(switches),
          'allDocumentsRetained': True, 'scope': 'Emulator; ADB + two animation frames + loopback telemetry. PSS snapshots, not peak memory.'}
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
