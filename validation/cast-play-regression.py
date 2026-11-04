#!/usr/bin/env python3
"""Check cast-list actions and foreground media tabs on the local QA fixture server."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def run(serial):
    assert serial.startswith('emulator-'), 'Use a local test emulator'
    ux.ADB = ['adb', '-s', serial]
    ux.adb('reverse', 'tcp:8875', 'tcp:8875')
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'serial': serial, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0],
              'checks': [], 'error': None}

    def record(message):
        result['checks'].append(message)
        print('PASS:', message, flush=True)

    def back():
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.5)

    def row(fragment):
        values = [n for n in ux.nodes()[0].iter('node') if ux.visible(n)
                  and n.get('checkable') == 'true'
                  and any(fragment in c.get('text', '') for c in n.iter('node'))]
        assert values, 'Missing media row: ' + fragment
        return min(values, key=lambda n: len(list(n.iter('node'))))

    def action(item, name):
        values = [n for n in item.iter('node') if ux.visible(n)
                  and n.get('text') in ux.resource_labels(name)]
        assert values, 'Missing action: ' + name
        return values[0]

    def selected(item):
        return item.get('checked') == 'true' or item.get('selected') == 'true'

    def cast():
        ux.menu_item('Cast')
        ux.expect('Choose media to cast')

    try:
        ux.launch('http://127.0.0.1:8875/cast-play-fixture.html')
        ux.expect('Cast playback fixture')
        ux.tap('Refresh sources')
        ux.tap('Play primary')
        time.sleep(1)
        cast()
        assert selected(row('sample-default.mp4')), 'Playing source is not selected'
        stream = row('stream-secondary.m3u8')
        assert not any(c.get('text') in ux.resource_labels('cast_play') |
                       ux.resource_labels('download_confirm_action') for c in stream.iter('node'))
        assert not any(n.get('text') in {'Copy', '复制', '複製'} for n in ux.nodes()[0].iter('node'))
        record('direct media has Play/Download; streams remain selectable for casting without these actions')
        ux.tap_node(action(row('player-sample.mp4'), 'download_confirm_action'))
        ux.expect('Download file?')
        ux.tap('Cancel')
        assert selected(row('sample-default.mp4')), 'Download changed the cast selection'
        record('download confirmation and cancellation preserve the selected cast source')
        ux.tap_node(action(row('player-sample.mp4'), 'cast_play'))
        ux.expect('Choose media to cast', present=False)
        ux.menu_item('More tools')
        ux.tap('Developer tools')
        ux.tap('Console')
        field = next(n for n in ux.nodes()[0].iter('node')
                     if ux.visible(n) and n.get('class') == 'android.widget.EditText')
        ux.tap_node(field)
        expression = "JSON.stringify({url:location.href,video:!!document.querySelector('video'),ready:document.querySelector('video')?.readyState,playing:document.querySelector('video')?.paused===false})"
        encoded = base64.b64encode(expression.encode()).decode()
        ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
               '/system/bin', 'com.mybrowser.validation.FastUiDump', 'setText', encoded)
        ux.tap('Run')
        deadline = time.monotonic() + 10
        while True:
            text = '\n'.join(n.get('text', '') for n in ux.nodes()[0].iter('node'))
            if '"playing":true' in text and ('"ready":4' in text or '"ready":3' in text):
                assert 'player-sample.mp4?token=second%2Bsource&quality=720' in text
                break
            assert time.monotonic() < deadline, text
            time.sleep(.3)
        record('Play opens the chosen URL with its complete query in a foreground tab and video playback starts')
        # Close the keyboard and nested tool/menu pages before testing the tab's Back action.
        for _ in range(6):
            if ux.match(ux.nodes()[0], 'Edit address') is not None:
                break
            back()
        ux.expect('Edit address')
        back()
        ux.expect('Cast playback fixture')
        # The existing cast store follows the active tab; returning must rediscover its requests.
        ux.tap('Refresh sources')
        ux.tap('Play primary')
        time.sleep(1)
        cast()
        assert selected(row('sample-default.mp4')), 'Playing source is not selected after returning'
        ux.tap_node(row('stream-secondary.m3u8'))
        assert selected(row('stream-secondary.m3u8')), 'Stream selection stopped working'
        record('Back returns to the original page; explicit cast-row selection still works')
        back()
    except Exception as error:
        result['error'] = str(error)
        raise
    finally:
        output = ROOT / 'results/cast-play.json'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    run(parser.parse_args().serial)
