#!/usr/bin/env python3
"""Exercise the media error UI without accepting an invalid TLS certificate."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
BASE = 'http://127.0.0.1:8875/'

def run(serial, debug=False):
    assert serial.startswith('emulator-')
    ux.ADB = ['adb', '-s', serial]
    if debug: ux.PACKAGE = 'com.mybrowser.debug'
    for port in (8875, 8876, 8877, 8879): ux.adb('reverse', f'tcp:{port}', f'tcp:{port}')
    result = {'serial': serial, 'androidApi': ux.adb('shell', 'getprop', 'ro.build.version.sdk'),
              'webView': ux.adb('shell', 'dumpsys', 'webviewupdate'), 'checks': [], 'error': None}
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result['apkSha256'] = ux.adb('shell', 'sha256sum', apk).split()[0]
    try:
        for name, detail in [('failhttp', 'media_request_http'), ('failoffline', 'media_request_network'),
                             ('failtls', 'ui_ssl_certificate_verification_failed'), ('failread', 'media_request_network')]:
            case = name + '-' + str(time.time_ns())
            ux.launch(BASE + 'cast-referrer-fixture.html?case=' + case)
            ux.expect('Cast referrer fixture'); ux.tap('Refresh sources'); ux.tap('Play primary')
            prefixes = [s.split('%')[0] for s in ux.resource_labels('cast_detected_sources')]
            node = next(n for n in ux.nodes()[0].iter('node') if ux.visible(n)
                        and any(n.get('content-desc', '').startswith(p) for p in prefixes))
            ux.tap_node(node); ux.expect('Choose media to cast')
            rows = [n for n in ux.nodes()[0].iter('node') if n.get('checkable') == 'true'
                    and any(c.get('text', '').endswith(' · direct.mp4') for c in n.iter('node'))]
            row = min(rows, key=lambda n: len(list(n.iter('node'))))
            action = next(n for n in row.iter('node') if n.get('text') in ux.resource_labels('cast_play'))
            urllib.request.urlopen(urllib.request.Request(BASE + '__state?case=media-failure-' + case,
                                                         data=b'{"armed":true}', method='POST')).close()
            labels = base64.b64encode(json.dumps(sorted(ux.resource_labels('cast_play'))).encode()).decode()
            receipt = ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
                             '/system/bin', 'com.mybrowser.validation.FastUiDump', 'tapInRow', ' · direct.mp4', labels)
            assert 'Tapped row action' in receipt, receipt
            ux.expect('Unable to open this page')
            ux.tap('Technical details')
            labels = [x.replace('%1$d', '403') for x in ux.resource_labels(detail)]
            text = '\n'.join(n.get('text', '') for n in ux.nodes()[0].iter('node'))
            assert any(label in text for label in labels), text
            result['checks'].append(name + ': visible browser error and specific reason')
            print('PASS:', result['checks'][-1], flush=True)
            ux.adb('shell', 'input', 'keyevent', '4')
            ux.expect('Cast referrer fixture')
    except Exception as error:
        result['error'] = str(error); raise
    finally:
        (ROOT / ('results/cast-media-failure-' + serial + '.json')).write_text(json.dumps(result, indent=2))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True); parser.add_argument('--debug', action='store_true')
    args = parser.parse_args(); run(args.serial, args.debug)
