#!/usr/bin/env python3
"""Reviewed resource download, UTF-8/binary bytes, restart, exclusions and private mode."""
import argparse, importlib.util, json, subprocess, time
from pathlib import Path
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('features', ROOT / 'features-regression.py')
features = importlib.util.module_from_spec(spec); spec.loader.exec_module(features)
ux = features.ux

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True); p.add_argument('--package', default='com.mybrowser')
    p.add_argument('--output', type=Path, required=True); a = p.parse_args()
    assert a.serial.startswith('emulator-'); ux.PACKAGE = a.package
    r = features.Regression(a.serial, 'resources'); r.output = a.output; a.output.mkdir(parents=True, exist_ok=True)
    expected = (ROOT / 'resource-style.css').read_text(encoding="utf-8")
    def good(s): return s['resourceText'] == expected and s['resourceHeader'] == '137,80,78,71,13,10,26,10' and s['resourceBytes'] > 50
    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875'); ux.adb('shell', 'am', 'force-stop', a.package)
        r.page(); ux.tap('Install resource fixture'); ux.expect('Pure resource fixture')
        root, _ = ux.nodes(); ux.tap('替换脚本' if ux.match(root, '替换脚本') is not None else '安装脚本')
        r.page(); first = r.wait(good)
        r.record('Installed resources return exact UTF-8 text and actual PNG bytes', bytes=first['resourceBytes'], browserVersion=first['browserVersion'])
        ux.adb('shell', 'am', 'force-stop', a.package); r.page(); r.wait(good)
        r.record('Resource bytes survive browser process restart')
        r.page('&excluded=1'); r.wait(lambda s: s['ready'] == 'complete' and not s['resourceText'])
        r.record('Excluded pages do not receive script resources')
        ux.menu_item('Enter incognito mode'); r.page()
        r.wait(lambda s: s['ready'] == 'complete' and not s['resourceText'])
        r.record('Private pages do not execute installed resource scripts')
        ux.menu_item('Exit incognito mode'); r.page(); r.wait(good)
        r.record('Returning to normal browsing restores authorized script execution')
        (a.output / 'result.json').write_text(json.dumps({'passed': True, 'apkSha256': r.apk_hash, 'checks': r.checks}, ensure_ascii=False, indent=2), encoding="utf-8")
    finally:
        (a.output / 'last-screen.xml').write_text(ux.nodes()[1], encoding="utf-8")
        (a.output / 'last-screen.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))

if __name__ == '__main__': main()
