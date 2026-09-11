#!/usr/bin/env python3
"""Install the previous release, create a bookmark in its UI, then update in place."""
import argparse, importlib.util, json, subprocess, time, hashlib, re
from pathlib import Path
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec); spec.loader.exec_module(ux)
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True); p.add_argument('--previous', type=Path, required=True)
p.add_argument('--apk', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
a = p.parse_args(); assert a.serial.startswith('emulator-'); ux.ADB = ['adb', '-s', a.serial]
a.output.mkdir(parents=True, exist_ok=True)
title = 'Upgrade bookmark ' + str(time.time_ns())
subprocess.run(ux.ADB + ['install', '-r', str(a.previous)], check=True)
ux.adb('reverse', 'tcp:8875', 'tcp:8875')
ux.launch('http://127.0.0.1:8875/browser-ux.html?upgrade=' + str(time.time_ns()))
ux.menu_item('Add bookmark')
field = next(n for n in ux.nodes()[0].iter('node') if n.get('class') == 'android.widget.EditText' and ux.visible(n))
ux.tap_node(field)
ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin', 'com.mybrowser.validation.FastUiDump', 'selectAll')
ux.adb('shell', 'input', 'text', title.replace(' ', '%s')); ux.adb('shell', 'input', 'keyevent', '4'); ux.tap('Save')
ux.menu_item('Bookmarks'); ux.expect(title)
(a.output / 'before.xml').write_text(ux.nodes()[1])
before = ux.adb('shell', 'dumpsys', 'package', 'com.mybrowser'); (a.output / 'before-package.txt').write_text(before)
before_code = int(re.search(r'versionCode=(\d+)', before)[1])
subprocess.run(ux.ADB + ['install', '-r', str(a.apk)], check=True)
ux.launch(); ux.menu_item('Bookmarks'); ux.expect(title)
(a.output / 'after.xml').write_text(ux.nodes()[1])
(a.output / 'after.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))
after = ux.adb('shell', 'dumpsys', 'package', 'com.mybrowser'); (a.output / 'after-package.txt').write_text(after)
after_code = int(re.search(r'versionCode=(\d+)', after)[1])
assert after_code > before_code
installed = ux.adb('shell', 'pm', 'path', 'com.mybrowser').partition(':')[2].strip()
expected = hashlib.sha256(a.apk.read_bytes()).hexdigest()
assert ux.adb('shell', 'sha256sum', installed).split()[0] == expected
(a.output / 'result.json').write_text(json.dumps({'passed': True, 'apkSha256': expected,
    'previousApkSha256': hashlib.sha256(a.previous.read_bytes()).hexdigest(),
    'beforeVersionCode': before_code, 'afterVersionCode': after_code, 'bookmarkTitle': title,
    'checks': [f'Same-package in-place installation accepted: versionCode {before_code} to {after_code}', 'Bookmark created by the old release remains visible after database migration']}, indent=2))
print('PASS upgrade retained bookmark and installed APK identity', flush=True)
