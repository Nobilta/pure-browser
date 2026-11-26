#!/usr/bin/env python3
"""Interrupt a sheet's entrance and its exit, and check nothing is left stranded.

Springs are the point of the 0.14 motion rework: an interrupted animation continues from where it
is instead of restarting, which is what lets a user change their mind about a sheet. That path has
no coverage anywhere else — the regression stages open and close sheets, but always let each
transition finish — and its failure mode is the worst kind: a modal window the user cannot dismiss,
or a scrim left dimming a page with nothing on top of it.

So the assertions are deliberately about what is left behind: after reversing mid-entrance and
mid-exit, the sheet must be fully gone or fully present and still usable, and the app must still be
responsive.

Requires the app to be installed and the UI probe pushed (validation/tools/setup-ui-probe.py).
"""
import argparse
import importlib.util
import json
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mybrowser')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
    ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    # Start from the browser itself: the stage is run by the suite registry, which does not
    # promise that the app is foregrounded when it begins.
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    ux.launch()
    installed = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'apkSha256': ux.adb('shell', 'sha256sum', installed).split()[0],
              'checks': []}

    def check(name, ok, detail):
        result['checks'].append({'name': name, 'passed': bool(ok), 'detail': detail})
        print(('PASS' if ok else 'FAIL'), name, '|', detail, flush=True)
        return ok

    def sheet_present():
        root, _ = ux.window_nodes()
        node = ux.match(root, 'browser_menu')
        return node is not None and ux.visible(node)

    def open_menu():
        ux.tap_resource('cd_menu')

    def settle(expected, timeout=6):
        """Wait until the sheet's presence stops changing, then report it."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if sheet_present() is expected:
                return True
            time.sleep(0.3)
        return sheet_present() is expected

    def close_menu_if_open():
        if sheet_present():
            ux.close_menu()
            settle(False)

    # --- reversing an entrance -----------------------------------------------------------
    # Dismiss while the sheet is still arriving. Nothing here waits for the entrance to finish,
    # which is the whole point: the exit has to start from part-way in.
    stranded = []
    for attempt in range(3):
        close_menu_if_open()
        open_menu()
        time.sleep(0.08)
        ux.close_menu()
        if not settle(False):
            stranded.append(f'attempt {attempt + 1}: still presented after reversing mid-entrance')
            close_menu_if_open()
    check('reversing an entrance leaves no stranded sheet', not stranded,
          '; '.join(stranded) if stranded else '3 entrances reversed 80ms in, all ended closed')

    # --- reversing an exit ----------------------------------------------------------------
    # Dismiss, then reopen before it has finished leaving. The host keeps the leaving page
    # composed for exactly this, so the reopened sheet must come back and work.
    reopened = []
    for attempt in range(3):
        close_menu_if_open()
        open_menu()
        if not settle(True):
            reopened.append(f'attempt {attempt + 1}: sheet never appeared')
            continue
        ux.close_menu()
        time.sleep(0.12)
        open_menu()
        if not settle(True):
            reopened.append(f'attempt {attempt + 1}: reopen mid-exit did not present the sheet')
            close_menu_if_open()
    check('reopening mid-exit presents the sheet again', not reopened,
          '; '.join(reopened) if reopened else '3 dismissals reversed part-way out, all reopened')

    # --- still usable afterwards ----------------------------------------------------------
    close_menu_if_open()
    open_menu()
    root = ux.expect_menu()
    # A row inside the sheet must still be reachable, so the surface is not a dead layer.
    usable = ux.match(root, 'browser_menu') is not None and any(
        ux.visible(n) and n.get('text') for n in root.iter('node'))
    check('the sheet is still usable after the interruptions', usable,
          'sheet content readable after the interruption cycles' if usable
          else 'sheet content missing after the interruption cycles')
    # And it still leaves when asked, which is the property that matters most.
    ux.close_menu()
    left = settle(False)
    check('and it still closes', left,
          'closed normally after the interruption cycles' if left
          else 'sheet refused to close after the interruption cycles')

    # Independently opened sheets must stay mounted until their own exit completes too.
    # Slow only the exit so a UI hierarchy sample can distinguish a transition from unmounting.
    original_scale = ux.adb('shell', 'settings', 'get', 'global', 'animator_duration_scale')
    try:
        for method in ('header', 'scrim', 'system-back'):
            ux.adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1')
            ux.menu_item('More tools')
            ux.tap('User scripts')
            ux.expect('User scripts')
            time.sleep(.6)
            root, _ = ux.nodes()
            ux.adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '10')
            time.sleep(.2)
            if method == 'header':
                button = next(n for n in root.iter('node') if ux.visible(n)
                              and n.get('content-desc') in ux.resource_labels('cd_back'))
                x1, y1, x2, y2 = ux.bounds(button)
                ux.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            elif method == 'scrim':
                _, top, right, _ = ux.stable_display_bounds(root)
                ux.adb('shell', 'input', 'tap', str(right//2), str(top+2))
            else:
                ux.adb('shell', 'input', 'keyevent', '4')
            retained = ux.match(ux.nodes()[0], 'User scripts') is not None
            check('independent sheet animates ' + method, retained,
                  'content remains during slowed exit' if retained else 'content unmounted immediately')
            ux.expect('User scripts', present=False, timeout=10)
        ux.adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
        ux.menu_item('More tools')
        ux.tap('User scripts')
        ux.tap_resource('cd_back')
        ux.expect('User scripts', present=False)
        check('independent sheet closes with animations disabled', True, 'no stranded window')
    finally:
        if original_scale == 'null':
            ux.adb('shell', 'settings', 'delete', 'global', 'animator_duration_scale')
        else:
            ux.adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', original_scale)

    result['passed'] = all(item['passed'] for item in result['checks'])
    (args.output / 'motion-interruption.json').write_text(
        json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
    print('RESULT:', 'PASS' if result['passed'] else 'FAIL', flush=True)
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
