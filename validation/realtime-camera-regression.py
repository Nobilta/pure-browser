#!/usr/bin/env python3
"""Verify automatic live preview, camera selection, and capture permission gating."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser.debug')
    parser.add_argument('--output', type=Path, default=ROOT / 'results/realtime-camera')
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a local emulator with front and rear cameras'
    ux.ADB = ['adb', '-s', args.serial]
    ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    apk = ux.adb('shell', 'pm', 'path', args.package).partition(':')[2].strip()
    result = {
        'package': args.package, 'serial': args.serial,
        'sdk': ux.adb('shell', 'getprop', 'ro.build.version.sdk'),
        'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0],
        'checks': [], 'samples': {}, 'passed': False,
    }
    prefix = 'realtime-' + str(time.time_ns())
    base = 'http://127.0.0.1:8875/'

    def events(case):
        with urllib.request.urlopen(base + '__state?case=' + case, timeout=4) as response:
            return json.load(response)

    def launch(query):
        case = prefix + '-' + str(len(result['checks']))
        ux.launch(base + 'realtime-camera-fixture.html?case=' + case + '&' + query)
        return case

    def ordinary(name):
        case = launch('mode=ordinary')
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            rows = events(case)
            if rows:
                assert rows[-1]['result'] == 'playing', rows
                result['samples'][name] = rows[-1]
                result['checks'].append(name)
                print('PASS:', name, flush=True)
                return
            time.sleep(.3)
        raise AssertionError('Ordinary autoplay produced no result')

    def camera(facing, case=None, generation=1):
        if case is None:
            case = launch('facing=' + facing)
        else:
            ux.tap('Front camera' if facing == 'user' else 'Rear camera')
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            rows = events(case)
            assert not any(row['kind'] == 'capture-error' for row in rows), rows
            samples = [row for row in rows if row['kind'] == 'camera' and row['generation'] == generation]
            playing = [row for row in samples if not row['paused']
                       and row['trackState'] == 'live' and not row['muted']
                       and row['readyState'] >= 2 and row['width'] > 0 and row['height'] > 0]
            if len(playing) > 1:
                first, last = playing[0], playing[-1]
                if last['time'] - first['time'] >= 2 and last['frames'] - first['frames'] >= 5:
                    if facing != 'default':
                        assert last['facing'] == facing, last
                    result['samples'][facing + '-' + str(generation)] = [first, last]
                    result['checks'].append(facing + ' camera advances without a separate Play tap')
                    print('PASS:', result['checks'][-1], flush=True)
                    return case
            if not samples:
                root, _ = ux.nodes()
                if ux.match(root, 'Website permission request') is not None:
                    ux.tap('Allow')
                else:
                    button = next((node for node in root.iter('node') if node.get('resource-id', '').endswith(
                        '/permission_allow_foreground_only_button')), None)
                    if button is not None:
                        ux.tap_node(button)
            time.sleep(.3)
        result['samples']['failed-' + facing] = events(case)[-5:]
        raise AssertionError('Camera did not advance without a webpage playback gesture: '
                             + str(result['samples']['failed-' + facing]))

    def deny_capture():
        case = prefix + '-denied'
        ux.launch('http://localhost:8875/realtime-camera-fixture.html?case=' + case)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            rows = events(case)
            if rows:
                assert rows[-1]['kind'] == 'capture-error' and rows[-1]['name'] == 'NotAllowedError', rows
                result['checks'].append('denied capture permission still blocks the camera')
                print('PASS:', result['checks'][-1], flush=True)
                return
            root, _ = ux.nodes()
            if ux.match(root, 'Website permission request') is not None:
                ux.tap('Block')
            time.sleep(.3)
        raise AssertionError('Capture denial produced no result')

    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        ux.adb('shell', 'am', 'force-stop', args.package)
        ordinary('ordinary media can autoplay before capture')
        case = camera('user')
        camera('environment', case, generation=2)
        camera('user', case, generation=3)
        ordinary('ordinary media can autoplay after leaving capture document')
        camera('default')
        deny_capture()
        result['passed'] = True
    finally:
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        (args.output / 'last-screen.xml').write_text(ux.nodes()[1], encoding='utf-8')
        (args.output / 'last-screen.png').write_bytes(subprocess.check_output(
            ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))


if __name__ == '__main__':
    main()
