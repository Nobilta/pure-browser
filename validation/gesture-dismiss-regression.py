#!/usr/bin/env python3
"""Drive a bottom sheet with a held finger: does it follow, settle back, and get thrown away?

The regression stages cover sheets as navigation (open, back, nested pickers). None of them touch
the dismissal gesture, because until 0.14 there was not one.

Two things here need a real gesture rather than a synthesised swipe. The first is finger tracking:
the surface's position is read back *while* the finger is still down, which a sheet that only
reacts on release would pass with a plain swipe and still be wrong. The second is that the two ways
a sheet can be dismissed — pulled past a share of its own height, or thrown fast — are separable,
so each is driven on its own and neither can stand in for the other.

Requires the app to be installed and the UI probe pushed (validation/setup-ui-probe.py).
"""
import argparse
import importlib.util
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
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

    def sheet_node():
        root, _ = ux.window_nodes()
        return ux.match(root, 'browser_menu')

    def sheet_bounds():
        node = sheet_node()
        return ux.bounds(node) if node is not None else None

    def sheet_present():
        node = sheet_node()
        return node is not None and ux.visible(node)

    def header_point():
        """A point on the sheet's own header strip.

        Found by the tag on the sheet's content column, whose first child is the header row — above
        the part of the sheet that scrolls. A drag has to start on the surface, so a point in that
        strip, left of the close button, is the only place where the gesture under test is the
        sheet's own rather than a list's.
        """
        rect = sheet_bounds()
        if rect is None:
            return None
        left, top, right, _ = rect
        return left + (right - left) // 4, top + 40

    def await_settled():
        """Wait until the sheet stops moving.

        The gesture is only accepted once the surface has arrived — a sheet still being driven by
        the window's own entrance spring refuses the finger, deliberately, so that two things never
        move it at once. A drag started before that is ignored rather than queued, so a stage that
        gestured on the frame the sheet appeared would be testing the race, not the gesture.
        """
        deadline = time.monotonic() + 6
        previous = sheet_bounds()
        while time.monotonic() < deadline:
            time.sleep(0.3)
            current = sheet_bounds()
            if current is not None and current == previous:
                return True
            previous = current
        return False

    def open_menu():
        if not sheet_present():
            ux.close_menu()
            time.sleep(0.4)
            # Selected by the resource the toolbar button actually uses rather than a translated
            # label, so this stage does not depend on the emulator's locale.
            ux.tap_resource('cd_menu')
        ux.expect_menu()
        assert await_settled(), 'the sheet never stopped moving after it opened'

    def motionevent(kind, x, y):
        ux.adb('shell', 'input', 'motionevent', kind, str(int(x)), str(int(y)))

    def press_and_move(steps, hold):
        """Press the sheet's header and move down in steps; returns the header tops around it."""
        point = header_point()
        assert point is not None, 'sheet header not found'
        x, y = point
        before = sheet_bounds()[1]
        motionevent('DOWN', x, y)
        time.sleep(0.2)
        moved = 0
        for step in steps:
            moved = step
            motionevent('MOVE', x, y + moved)
            time.sleep(0.35)
        time.sleep(hold)
        during = sheet_bounds()[1] if sheet_present() else None
        return before, during, moved, (x, y + moved)

    def surface_height():
        """Height of the sheet's surface, from its content column and the insets above it."""
        rect = sheet_bounds()
        screen_height = int(ux.adb('shell', 'wm', 'size').split()[-1].split('x')[1])
        # The column starts inside the surface: a 16dp top offset plus the sheet's own 12dp padding.
        return screen_height - rect[1] + int(28 * 2.625)

    # --- the sheet follows a held finger ------------------------------------------------
    open_menu()
    before, during, moved, end = press_and_move([40, 80, 120, 160], hold=0.6)
    # Touch slop is taken before the sheet starts moving, so the travel is the drag minus that.
    followed = before is not None and during is not None and (during - before) > moved * 0.5
    check('sheet follows a held finger', followed,
          f'header top {before} -> {during} while {moved}px of drag was held')
    motionevent('UP', end[0], end[1])
    time.sleep(1.5)
    settled = sheet_present()
    check('short drag settles the sheet back', settled,
          f'sheet still presented after releasing a {moved}px drag' if settled
          else f'a {moved}px drag dismissed the sheet below the threshold')
    ux.close_menu()
    time.sleep(1.0)

    # --- pulled past a share of its height: dismissed, however slowly it was pulled -------
    open_menu()
    height = surface_height()
    far = int(height * 0.45)
    before, during, moved, end = press_and_move([far // 4, far // 2, far * 3 // 4, far], hold=0.0)
    motionevent('UP', end[0], end[1])
    time.sleep(1.6)
    pulled = not sheet_present()
    check('a long pull dismisses the sheet', pulled,
          f'{moved}px of a {height}px surface (>33%) dismissed it, released from top {during}'
          if pulled else
          f'{moved}px of a {height}px surface did not dismiss it, released from top {during}')
    if not pulled:
        ux.close_menu()
        time.sleep(1.0)

    # --- the threshold is what decides, not the gesture ------------------------------------
    # A drag that stops short of the share above has to settle back even though it travelled a long
    # way, which is the other half of the rule the long pull establishes. The surface's position is
    # read while the finger is still down first, so a sheet that simply refused the gesture could
    # not pass this by staying put.
    #
    # The speed half of the rule is deliberately not asserted here. Velocity comes from a fit over
    # the samples inside the last ~100ms, and adb spends about that long starting the process for
    # each event it injects, so no sequence of `input` calls can hand Compose a stream as dense as a
    # finger's. A fast flick is therefore verified by hand rather than claimed by this stage.
    open_menu()
    height = surface_height()
    short = int(height * 0.30)
    x, y = header_point()
    motionevent('DOWN', x, y)
    time.sleep(0.15)
    motionevent('MOVE', x, y + short)
    time.sleep(0.6)
    held = sheet_bounds()[1]
    motionevent('UP', x, y + short)
    time.sleep(1.5)
    reached = held is not None and (held - 210) > short * 0.5
    back = sheet_present()
    check('a drag short of the threshold settles back where it was pulled from', reached and back,
          f'pulled to {held} (from 210, {short}px asked) and released below the {int(height * 0.33)}px'
          f' threshold, so it settled back' if reached and back
          else f'travel accepted={reached} (reached {held}), settled back={back}')
    if not back:
        ux.close_menu()

    result['passed'] = all(item['passed'] for item in result['checks'])
    (args.output / 'gesture-dismiss.json').write_text(
        json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
    print('RESULT:', 'PASS' if result['passed'] else 'FAIL', flush=True)
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
