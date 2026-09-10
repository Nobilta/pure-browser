#!/usr/bin/env python3
"""Real UI Profile cleanup checks; works with a signed release, without CDP or a native test bridge."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    ux.ADB = ['adb', '-s', args.serial]
    ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    url = 'http://127.0.0.1:8875/profile-fixture.html'
    ux.adb('reverse', 'tcp:8875', 'tcp:8875')
    ux.adb('shell', 'logcat', '-b', 'crash', '-c')
    results = []

    def read_storage(expected, label):
        ux.tap('Read storage', timeout=15)
        deadline = time.monotonic() + 15
        while True:
            root, raw = ux.nodes()
            text = ' '.join(n.get('text', '') for n in root.iter('node'))
            fields = [f'{key}: {expected}' for key in ('cookie', 'local', 'indexed', 'cache')]
            if all(field in text for field in fields):
                results.append({'check': label, 'expected': expected, 'passed': True})
                (args.output / f'{len(results):02d}-storage.xml').write_text(raw)
                (args.output / 'result.json').write_text(json.dumps(results, indent=2))
                print('PASS', label, flush=True)
                return
            if time.monotonic() >= deadline:
                raise AssertionError(label + ': ' + text[-3000:])
            time.sleep(.3)

    def enter_private():
        ux.menu_item('进入无痕模式')
        time.sleep(1)
        root, raw = ux.nodes()
        assert any('isolated site storage' in n.get('text', '') or '独立隔离' in n.get('text', '') or '獨立隔離' in n.get('text', '') for n in root.iter('node')), 'MULTI_PROFILE was not obtained'
        ux.launch(url)

    def clear(scope):
        ux.menu_item('清除浏览数据')
        ux.tap(scope)
        ux.tap('清除')
        time.sleep(1)

    def exit_private():
        ux.menu_item('退出无痕模式')
        ux.launch(url)

    ux.launch(url)
    initial, _ = ux.nodes()
    if ux.match(initial, 'Incognito · isolated site storage') is not None:
        exit_private()
    ux.tap('Write normal storage', timeout=15)
    read_storage('normal', 'normal profile contains synthetic website data')
    enter_private()
    read_storage('empty', 'private profile cannot see normal data')
    ux.tap('Write private storage')
    read_storage('private', 'private profile contains its own data')
    clear('当前浏览模式')
    ux.launch(url)
    read_storage('empty', 'current-profile cleanup erases private cookie localStorage IndexedDB and CacheStorage')
    exit_private()
    read_storage('normal', 'normal data survives private cleanup and exit')
    enter_private()
    ux.tap('Write private storage')
    read_storage('private', 'new private data exists')
    clear('普通浏览模式')
    ux.launch(url)
    read_storage('private', 'normal-only cleanup preserves private data')
    exit_private()
    read_storage('empty', 'normal-only cleanup erased normal data')
    ux.tap('Write normal storage')
    enter_private()
    ux.tap('Write private storage')
    clear('两种浏览模式')
    ux.launch(url)
    read_storage('empty', 'both-profile cleanup erases private data')
    exit_private()
    read_storage('empty', 'both-profile cleanup erases normal data')
    crash = ux.adb('shell', 'logcat', '-b', 'crash', '-d')
    (args.output / 'crash.log').write_text(crash)
    assert 'com.mybrowser' not in crash, crash
    print(json.dumps({'checks': len(results), 'passed': True}), flush=True)

if __name__ == '__main__':
    main()
