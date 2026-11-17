#!/usr/bin/env python3
"""Check download selection and player controls at narrow widths and large font sizes.

Run qa-server.py and ftp-hls-regression.py first to seed pure-ftp.bin on the emulator.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('video', ROOT / 'video-regression.py')
video = importlib.util.module_from_spec(spec)
spec.loader.exec_module(video)
ux = video.ux


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--download-name', default='pure-ftp.bin')
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': []}
    font = ux.adb('shell', 'settings', 'get', 'system', 'font_scale').strip()
    size = ux.adb('shell', 'wm', 'size')
    original_size = size.split('Override size: ')[1].strip() if 'Override size:' in size else 'reset'
    locale = ux.adb('shell', 'cmd', 'locale', 'get-app-locales', ux.PACKAGE).split('[')[1].split(']')[0]
    rotation = ux.adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation').strip()
    density = int(ux.adb('shell', 'wm', 'density').strip().splitlines()[-1].split(':')[1]) / 160

    def record(check):
        result['checks'].append(check)
        print('PASS:', check, flush=True)

    def save(name, raw):
        (args.output / (name + '.xml')).write_text(raw, encoding='utf-8')
        (args.output / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))

    def configure(width, scale):
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.adb('shell', 'wm', 'size', f'{round(width*density)}x{round((400 if width >= 600 else 720)*density)}')
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', str(scale))

    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        ux.adb('shell', 'cmd', 'locale', 'set-app-locales', ux.PACKAGE, '--locales', 'en')
        configure(320, 2)
        ux.launch()
        ux.menu_item('Downloads')
        ux.expect(args.download_name)
        root, raw = ux.nodes()
        radios = [n for n in root.iter('node') if n.get('class') == 'android.widget.RadioButton']
        assert radios and any(n.get('checked') == 'true' and any(c in radios for c in n.iter('node'))
                              for n in root.iter('node')), 'Missing radio selection semantics'
        assert not any(n.get('class') == 'android.widget.Switch' for n in root.iter('node'))
        record('Download filters expose selected RadioButton semantics')
        node = ux.match(root, args.download_name)
        left, top, right, bottom = ux.bounds(node)
        x, y = str((left+right)//2), str((top+bottom)//2)
        ux.adb('shell', 'input', 'swipe', x, y, x, y, '1200')
        ux.expect('Selected: 1')
        root, raw = ux.nodes()
        save('download-selection-320-200', raw)
        count = ux.match(root, 'Selected: 1')
        count_bounds = ux.bounds(count)
        assert count_bounds[3] - count_bounds[1] <= 52*density, 'Selection count wraps into multiple lines'
        for resource in ('download_pause_selected', 'download_resume_selected', 'cd_delete', 'ui_close'):
            labels = ux.resource_labels(resource)
            node = next((n for n in root.iter('node') if n.get('content-desc') in labels
                         and ux.bounds(n)[1] >= count_bounds[3]), None)
            assert node is not None, resource
            left, top, right, bottom = ux.bounds(node)
            assert 0 <= left < right <= round(320*density), resource
            assert top >= count_bounds[3], f'{resource}: action {top} above count bottom {count_bounds[3]}'
        save('download-selection-320-200', raw)
        record('Selection count stays on one line and all four actions fit below it at 320dp and 200%')

        ux.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '1')
        ux.adb('emu', 'sensor', 'set', 'acceleration', '0:9.8:0')
        for width, scale in [(320, 1), (320, 2), (360, 1), (360, 2), (400, 1), (400, 2), (720, 1)]:
            configure(400 if width >= 600 else width, scale)
            test = video.Regression(args.serial, 'square', True)
            test.output = args.output
            test.case += f'-{width}-{scale}'
            ux.launch('http://127.0.0.1:8875/player-fixture.html?square=1&case=' + test.case)
            test.wait(lambda state: state['duration'] > 0, timeout=40)
            test.enter_fullscreen()
            if width >= 600:
                ux.adb('shell', 'wm', 'size', f'{round(width*density)}x{round(400*density)}')
                time.sleep(1)
            root, _ = ux.nodes()
            if ux.match(root, 'Got it') is not None:
                ux.tap('Got it')
            test.snapshot('entered')
            test.button('暂停视频')
            test.wait(lambda state: state['paused'])
            root, raw = test.player_nodes(reveal=True)
            save(f'player-{width}-{scale}', raw)
            title = ux.match(root, 'Pure Video Lab')
            assert title is not None and ux.bounds(title)[2] > ux.bounds(title)[0], 'Player title is missing'
            for resource in ('ui_exit_fullscreen', 'picture_in_picture', 'ui_rotate_screen',
                             'player_background_audio', 'player_video_menu'):
                label = next(iter(ux.resource_labels(resource)))
                targets = [n for n in root.iter('node') if n.get('clickable') == 'true'
                           and ux.match(n, label) is not None]
                assert targets, f'{width}/{scale}: {resource} missing'
                left, top, right, bottom = ux.bounds(targets[-1])
                assert 0 <= left < right <= test.width and 0 <= top < bottom <= test.height, resource
                assert right-left >= 47*density and bottom-top >= 47*density, 'Player touch target is clipped'
            record(f'Player title and all five 48dp top actions fit at {width}dp / {scale*100}%')
            if width == 400 and scale == 2:
                background = next(iter(ux.resource_labels('player_background_audio')))
                def checked(tree):
                    return next(n.get('checked') for n in tree.iter('node')
                                if n.get('checkable') == 'true' and ux.match(n, background) is not None)
                initial = checked(root)
                test.button(background)
                assert checked(test.player_nodes(reveal=True)[0]) != initial
                test.button(background)
                assert checked(test.player_nodes(reveal=True)[0]) == initial
                record('Background playback switch toggles both ways at 400dp and 200%')
        result['passed'] = True
    finally:
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        for key, value in (('font_scale', font), ('accelerometer_rotation', rotation)):
            if value == 'null':
                ux.adb('shell', 'settings', 'delete', 'system', key)
            else:
                ux.adb('shell', 'settings', 'put', 'system', key, value)
        ux.adb('shell', 'wm', 'size', original_size)
        command = ['shell', 'cmd', 'locale', 'set-app-locales', ux.PACKAGE]
        if locale:
            command += ['--locales', locale]
        ux.adb(*command)
        (args.output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
