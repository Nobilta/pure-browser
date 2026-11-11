#!/usr/bin/env python3
"""Check real release sources and automatic fallback on a rooted local emulator."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'results/update-sources')
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated local emulator'
    ux.ADB = ['adb', '-s', args.serial]
    ux.PACKAGE = 'com.mybrowser'
    args.output.mkdir(parents=True, exist_ok=True)
    ux.adb('root')
    ux.adb('wait-for-device')
    assert ux.adb('shell', 'id', '-u') == '0', 'This suite needs a rootable emulator'
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    directory = '/data/user/0/' + ux.PACKAGE + '/shared_prefs'
    path = directory + '/app_updates.xml'
    owner = ux.adb('shell', 'stat', '-c', '%u:%g', '/data/user/0/' + ux.PACKAGE)
    exists = subprocess.run(ux.ADB + ['shell', 'test', '-f', path], check=False).returncode == 0
    original = subprocess.check_output(ux.ADB + ['exec-out', 'cat', path]) if exists else None
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {
        'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0],
        'sdk': ux.adb('shell', 'getprop', 'ro.build.version.sdk'),
        'checks': [], 'passed': False,
        'notTested': ['Real upgrade installation', 'Physical devices'],
    }

    def write_preferences(data):
        ux.adb('shell', 'mkdir', '-p', directory)
        ux.adb('shell', 'chown', owner, directory)
        subprocess.run(ux.ADB + ['shell', 'cat > ' + path], input=data, check=True)
        ux.adb('shell', 'chown', owner, path)
        ux.adb('shell', 'chmod', '600', path)

    def check(name, blocked, expected_source):
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        prefs = ET.Element('map')
        for source in blocked:
            ET.SubElement(prefs, 'long', name='retry_after_' + source,
                          value=str(int(time.time() * 1000) + 3_600_000))
        write_preferences(ET.tostring(prefs, encoding='utf-8', xml_declaration=True))
        ux.launch('about:blank')
        ux.open_settings('关于与更新')
        ux.tap('Check for updates')
        ux.tap('Check for updates')
        keys = ['update_latest', 'update_confirm_download'] if expected_source else ['update_rate_limit']
        labels = set().union(*(ux.resource_labels(key) for key in keys))
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            root, raw = ux.nodes()
            if any(ux.visible(node) and node.get('text') in labels for node in root.iter('node')):
                break
            time.sleep(.3)
        else:
            raise AssertionError('Update dialog did not reach the expected result: ' + name)
        prefs = ET.fromstring(ux.adb('shell', 'cat', path))
        source = prefs.find("string[@name='source']")
        assert (source.text if source is not None else None) == expected_source
        result['checks'].append({'name': name, 'source': expected_source})
        (args.output / (name + '.xml')).write_text(raw, encoding='utf-8')
        (args.output / (name + '.png')).write_bytes(subprocess.check_output(
            ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))
        print('PASS:', name, flush=True)

    try:
        check('gitee-primary', [], 'GITEE')
        check('github-fallback', ['GITEE'], 'GITHUB')
        check('both-rate-limited', ['GITEE', 'GITHUB'], None)
        result['passed'] = True
    finally:
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        if original is None:
            ux.adb('shell', 'rm', '-f', path)
        else:
            write_preferences(original)


if __name__ == '__main__':
    main()
