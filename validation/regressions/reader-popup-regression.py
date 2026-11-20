#!/usr/bin/env python3
"""Check that reader style changes reach a background popup, and that toggling off/on works."""
import argparse
import importlib.util
import io
import json
import subprocess
import time
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)

WARM = (244, 236, 216)  # #f4ecd8


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated test emulator'
    ux.ADB = ['adb', '-s', args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    base = 'http://127.0.0.1:8875/'
    checks = []
    result = {'passed': False, 'checks': checks,
              'apkSha256': ux.adb('shell', 'sha256sum',
                                  ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()).split()[0]}
    original_font = ux.adb('shell', 'settings', 'get', 'system', 'font_scale').strip()

    def record(name):
        checks.append(name)
        (args.output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print('PASS', name, flush=True)

    def colour(name):
        png = subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'])
        (args.output / (name + '.png')).write_bytes(png)
        image = Image.open(io.BytesIO(png)).convert('RGB')
        return image.getpixel((6, image.height // 2))

    def tabs():
        root, _ = ux.nodes()
        node = next(n for n in root.iter('node') if ux.visible(n) and
                    n.get('content-desc', '').startswith(('Tabs', '标签页', '分頁')))
        x1, y1, x2, y2 = ux.bounds(node)
        ux.adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(.9)

    def select(title):
        tabs()
        ux.tap(title)
        time.sleep(1.2)

    paper_labels = {values['reader_paper_value'].replace('%1$s', values['reader_paper_' + mode]): mode
                    for values in ux._translations for mode in ('system', 'warm', 'dark')}

    def paper_pill():
        root, _ = ux.nodes()
        return next((node, paper_labels[child.get('content-desc')]) for node in root.iter('node')
                    if node.get('clickable') == 'true' for child in node.iter('node')
                    if child.get('content-desc') in paper_labels)

    def tap_pill():
        pill, _ = paper_pill()
        x1, y1, x2, y2 = ux.bounds(pill)
        ux.adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(1.2)

    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.launch(base + 'reader-popup-opener.html')
        ux.expect('Reader popup opener', timeout=20)
        ux.tap('Open reader popup')
        ux.expect('Reader fixture', timeout=20)
        ux.menu_item('Reader mode')
        time.sleep(2)

        # Enter reader on the opener too, then normalise paper to "Auto" so the next change is
        # observable in the popup regardless of what the previous run left behind.
        select('Reader popup opener')
        ux.menu_item('Reader mode')
        time.sleep(2)
        for _ in range(3):
            if paper_pill()[1] == 'system':
                break
            tap_pill()
        select('Reader fixture')
        assert colour('popup-auto') != WARM, 'Popup did not take the Auto paper'
        record('Paper change from another tab reaches the background popup')

        select('Reader popup opener')
        tap_pill()  # Auto -> Warm
        assert colour('opener-warm') == WARM, 'Opener did not apply the warm paper'
        select('Reader fixture')
        assert colour('popup-warm') == WARM, 'Background popup kept the previous paper'
        record('Warm paper reaches the background popup document')

        # Leaving and re-entering uses the persistent controller; the second call is not a
        # reinjection, which is the path this change exists to prove.
        ux.menu_item('Reader mode')
        time.sleep(1.5)
        assert colour('popup-exited') != WARM, 'Reader did not exit'
        ux.menu_item('Reader mode')
        time.sleep(2)
        assert colour('popup-reentered') == WARM, 'Re-entering did not restore the reader'
        record('Reader exit and re-entry reuse the persistent controller')
        result['passed'] = True
    finally:
        ux.adb('shell', 'settings', 'put', 'system', 'font_scale', original_font or '1.0')
        (args.output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
