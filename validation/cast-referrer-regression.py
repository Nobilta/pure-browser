#!/usr/bin/env python3
"""Check Referer-protected media through the real cast Play action, reload and redirect."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
BASE = 'http://127.0.0.1:8875/'
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def run(serial, scenarios=None):
    assert serial.startswith('emulator-'), 'Use a local test emulator'
    ux.ADB = ['adb', '-s', serial]
    for port in ('8875', '8876'):
        ux.adb('reverse', 'tcp:' + port, 'tcp:' + port)
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': [], 'cases': [], 'error': None}

    def record(message):
        result['checks'].append(message)
        print('PASS:', message, flush=True)

    def rows(key):
        return json.load(urllib.request.urlopen(BASE + '__state?case=' + key, timeout=5))

    def back():
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.4)

    def close_tools():
        for _ in range(6):
            if ux.match(ux.nodes()[0], 'Edit address') is not None:
                return
            back()
        raise AssertionError('Tools did not close')

    def set_text(value):
        field = next(n for n in ux.nodes()[0].iter('node')
                     if ux.visible(n) and n.get('class') == 'android.widget.EditText')
        ux.tap_node(field)
        ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
               '/system/bin', 'com.mybrowser.validation.FastUiDump', 'setText', base64.b64encode(value.encode()).decode())

    def video_state(case, seek=False):
        ux.menu_item('More tools')
        ux.tap('Developer tools')
        ux.tap('Console')
        sample = str(time.time_ns())
        seek_code = 'if(v&&Number.isFinite(v.duration)){v.currentTime=v.duration*.65;v.play().catch(()=>{});}' if seek else ''
        script = "(()=>{const v=document.querySelector('video');" + seek_code + "const start=v?.currentTime||0;let tries=0;const timer=setInterval(()=>{fetch(" + json.dumps(
            BASE + '__state?case=playback-' + case) + ",{method:'POST',body:JSON.stringify({sample:" + json.dumps(sample) + ",url:location.href,video:!!v,ready:v?.readyState,playing:v?.paused===false,position:v?.currentTime,start,error:v?.error?.code})});if(++tries>=20||(v?.readyState>=3&&!v.paused&&v.currentTime>start+.1))clearInterval(timer)},500)})()"
        set_text(script)
        ux.tap('Run')
        deadline = time.monotonic() + 15
        state = None
        while True:
            state = next((r for r in reversed(rows('playback-' + case)) if r.get('sample') == sample), None)
            if state and state['video'] and state['ready'] >= 3 and state['playing'] and state['position'] > state['start'] + .1:
                if seek:
                    assert state['position'] > 1, state
                close_tools()
                return state
            assert time.monotonic() < deadline, state or 'No video telemetry'
            time.sleep(.25)

    private = False
    try:
        for name, private_mode in scenarios or (('direct', False), ('redirect', False), ('direct', True)):
            if private_mode:
                ux.menu_item('Enter incognito mode')
                private = True
            case = str(time.time_ns())
            source = BASE + 'cast-referrer-fixture.html?case=' + case
            target = BASE + 'cast-referer/' + name + '.mp4?case=' + case
            try:
                urllib.request.urlopen(target, timeout=5)
                raise AssertionError('The guarded fixture allowed a missing Referer')
            except urllib.error.HTTPError as error:
                assert error.code == 403
            ux.launch(source + '#source-fragment')
            ux.expect('Cast referrer fixture')
            ux.tap('Refresh sources')
            ux.tap('Play primary')
            prefixes = [s.split('%')[0] for s in ux.resource_labels('cast_detected_sources')]
            button = next(n for n in ux.nodes()[0].iter('node') if ux.visible(n)
                          and any(n.get('content-desc', '').startswith(p) for p in prefixes))
            ux.tap_node(button)
            ux.expect('Choose media to cast')
            candidates = [n for n in ux.nodes()[0].iter('node') if n.get('checkable') == 'true'
                          and any(c.get('text', '').endswith(' · ' + name + '.mp4') for c in n.iter('node'))]
            item = min(candidates, key=lambda n: len(list(n.iter('node'))))
            action = next(n for n in item.iter('node') if n.get('text') in ux.resource_labels('cast_play'))
            since = time.time()
            ux.tap_node(action)
            first = video_state(case, seek=True)
            expected_url = target
            assert first['url'] == expected_url, first
            ux.tap('Refresh')
            second = video_state(case)
            assert second['url'] == expected_url, second
            requests = [r for r in rows('cast-referrer-' + case) if r['receivedAt'] > since]
            result['cases'].append({'name': name, 'private': private_mode, 'requests': requests, 'before': first, 'reload': second})
            assert requests and all(r['allowed'] and r['referer'] == source for r in requests), requests
            assert any(r['range'] and not r['range'].startswith('bytes=0-') for r in requests), requests
            if name == 'redirect':
                assert any(r['port'] == 8876 for r in requests), requests
            record(('private' if private_mode else 'normal') + ' ' + name + ': protected playback, seek/Range and reload retain Referer')
            if name == 'redirect':
                ux.tap('Edit address')
                set_text(BASE + 'cast-referer/leak-check?case=' + case)
                ux.adb('shell', 'input', 'keyevent', '66')
                ux.expect('No stale media Referer')
                ux.tap('Refresh')
                ux.expect('No stale media Referer')
                leak = [r for r in rows('cast-referrer-' + case) if r['path'].endswith('/leak-check')]
                assert leak and all(not r['referer'] for r in leak), leak
                record('same-tab navigation and its reload do not inherit the media Referer')
            else:
                back()
                ux.expect('Cast referrer fixture')
                record('Back after media reload returns to the original page')
            if private:
                ux.menu_item('Exit incognito mode')
                private = False
    except Exception as error:
        result['error'] = str(error)
        raise
    finally:
        result['lastRequests'] = rows('cast-referrer-' + case) if 'case' in locals() else []
        path = ROOT / 'results/cast-referrer.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    run(parser.parse_args().serial)
