#!/usr/bin/env python3
"""Verify media ownership disappears on close while the browser process stays alive."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
BASE = 'http://127.0.0.1:8875/'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pip-only', action='store_true', help='Run only the PiP lifecycle section')
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = int(ux.adb('shell', 'getprop', 'ro.build.version.sdk'))
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0],
              'checks': [], 'limitations': [], 'cases': {}}
    original_settings = {}
    key = ''
    process = ''

    def wait(condition, timeout=15, message='Condition did not become true'):
        deadline = time.monotonic() + timeout
        while True:
            value = condition()
            if value is not None and value is not False:
                return value
            assert time.monotonic() < deadline, message
            time.sleep(.25)

    def state(condition=lambda row: True, since=0, case=None):
        last = None

        def matches():
            nonlocal last
            rows = json.load(urllib.request.urlopen(BASE + '__state?case=' + (case or key), timeout=4))
            last = rows[-1] if rows else None
            return last if last and last['receivedAt'] > since and condition(last) else False

        try:
            return wait(matches, message='No matching media telemetry')
        except AssertionError:
            raise AssertionError('Last telemetry: ' + repr(last))

    def record(name):
        result['checks'].append(name)
        print('PASS', name, flush=True)
        (args.output / 'result.json').write_text(json.dumps(result, indent=2))

    def session_text():
        return ux.adb('shell', 'dumpsys', 'media_session')

    def has_session(text):
        return re.search(r'(?m)^\s+PureBrowser\s+com\.mybrowser[/\s]', text) is not None

    def session_present():
        return wait(lambda: has_session(session_text()), message='Playing media has no Android session')

    def service_text():
        return ux.adb('shell', 'dumpsys', 'activity', 'services', ux.PACKAGE)

    def active_notifications():
        if sdk >= 30:
            return ux.adb('shell', 'cmd', 'notification', 'list')
        # Android 10 has no notification-list shell command. Restrict its dump
        # to active records; archived notifications are expected after Stop.
        raw = ux.adb('shell', 'dumpsys', 'notification', '--noredact')
        active = re.search(r'(?ms)^  Notification List:\r?\n(.*?)(?=^  \S|\Z)', raw)
        assert active is not None, 'Cannot locate active Android notifications'
        return active[1]

    def assert_released(name, stable=False):
        wait(lambda: not has_session(session_text()), timeout=9, message='Closed media retained a session')
        if stable:
            # Exercise more than one four-second frame deadline. Paused packets
            # must not recreate a stopped/closed session.
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                assert not has_session(session_text()), 'A delayed signal revived the old session'
                time.sleep(.3)
        wait(lambda: 'MediaPlaybackService' not in service_text(), message='Media foreground service survived close')
        notifications = active_notifications()
        assert not re.search(r'\|com\.mybrowser\|2002\|', notifications), 'Active media notification survived close'
        assert ux.adb('shell', 'pidof', ux.PACKAGE) == process, 'The browser process restarted during the test'
        (args.output / (name + '-session.txt')).write_text(session_text())
        (args.output / (name + '-services.txt')).write_text(service_text())
        (args.output / (name + '-notifications.txt')).write_text(notifications)

    def setting(title, value):
        ux.open_settings('Video playback')
        root, _ = ux.nodes()
        row = next(n for n in root.iter('node') if n.get('checkable') == 'true'
                   and ux.match(n, title) is not None)
        previous = row.get('checked') == 'true'
        original_settings.setdefault(title, previous)
        if previous != value:
            ux.tap_node(row)
        # A new external URL closes the settings navigation stack.
        ux.launch(BASE + 'browser-ux.html?case=media-settings')

    def page(name, frame=False, fixture='media-lifecycle-fixture.html'):
        nonlocal key
        key = 'lifecycle-' + name + '-' + str(time.time_ns())
        result['lastCase'] = key
        ux.launch(BASE + fixture + '?case=' + key + ('&frame=1' if frame else ''))
        state(case=key + '-child' if frame else key)

    def play(frame=False, label='Play media'):
        ux.tap(label)
        row = state(lambda r: not r['paused'] and r.get('position', r.get('currentTime', 0)) > .1,
                    case=key + '-child' if frame else key)
        session_present()
        return row

    def pip_active():
        activity = ux.adb('shell', 'dumpsys', 'activity', 'activities')
        return 'mode=pinned' in activity or 'windowingMode=2' in activity or 'mWindowingMode=2' in activity

    def all_windows():
        return ux.window_nodes()

    def pip_close():
        # The actual system PiP menu exposes the close/dismiss button only after
        # the floating window is tapped. Resolve bounds from its live UI tree.
        root, _ = all_windows()
        app = next((n for n in root.iter('node') if n.get('package') == ux.PACKAGE and ux.visible(n)), None)
        assert app is not None, 'The PiP window is missing from accessibility'
        density = ux.adb('shell', 'wm', 'density')
        ux.tap_node(app)
        menu_shown = time.monotonic()
        root, raw = all_windows()
        button = next((n for n in root.iter('node') if ux.visible(n) and
                       (n.get('resource-id', '').endswith(('/dismiss', '/close_button', '/pip_close_button')) or
                        (n.get('package') in ('com.android.systemui', 'com.google.android.apps.nexuslauncher')
                         and n.get('content-desc') in ('Close', 'Dismiss', '关闭', '關閉')))), None)
        # Android 17's surface-hosted PiP menu can be visible in the screenshot
        # but absent from UiAutomation windows. Keep the actual system touch path
        # using its observed top-right 48dp close target within the current bounds.
        (args.output / 'pip-menu.xml').write_text(raw)
        # Screenshot encoding on a software-rendered emulator can take four
        # seconds, long enough for this transient system menu to hide. Complete
        # the observed Close touch before collecting the post-close screenshot.
        result['pipMenuLookupSeconds'] = round(time.monotonic() - menu_shown, 3)
        if button is not None:
            ux.tap_node(button)
        else:
            values = re.findall(r'(?:Physical|Override) density: (\d+)', density)
            assert values, 'Unknown display density for PiP menu'
            inset = round(24 * int(values[-1]) / 160)
            # Showing the menu can resize/reposition PiP. Never use its bounds
            # from before the menu opened, or the touch can miss the close icon.
            app = next(n for n in root.iter('node') if n.get('package') == ux.PACKAGE and ux.visible(n))
            x1, y1, x2, y2 = ux.bounds(app)
            ux.adb('shell', 'input', 'tap', str(x2 - inset), str(y1 + inset))
            result['pipCloseInput'] = {'kind': 'system-menu-touch', 'x': x2 - inset, 'y': y1 + inset}
        wait(lambda: not pip_active(), message='PiP stayed pinned after system close')

    try:
        for port in ('8875', '8876'):
            ux.adb('reverse', 'tcp:' + port, 'tcp:' + port)
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.launch(BASE + 'browser-ux.html?case=media-start')
        setting('Allow background media', True)
        setting('Fullscreen video in picture in picture', True)
        process = ux.adb('shell', 'pidof', ux.PACKAGE)
        result['pid'] = process

        if not args.pip_only:
            page('pause-stop')
            play()
            ux.media_dispatch('pause')
            state(lambda r: r['paused'])
            session_present()
            ux.media_dispatch('play')
            state(lambda r: not r['paused'])
            session_present()
            ux.media_dispatch('stop')
            state(lambda r: r['paused'])
            assert_released('system-stop', stable=True)
            record('System Pause stays resumable; Play resumes; Stop releases token, notification and service without later revival')

            conditions = {
                'Finish media': lambda r: r['ended'],
                'Hide media': lambda r: r['hidden'] and r['paused'],
                'Unload media': lambda r: r['ready'] == 0,
                'Remove media': lambda r: not r['connected'],
                'Replace media': lambda r: r['connected'] and r['paused'],
            }
            for label, condition in conditions.items():
                name = label.split()[0].lower()
                page(name)
                play()
                (args.output / (name + '-playing-session.txt')).write_text(session_text())
                changed = time.time()
                ux.tap(label)
                result['cases'][name] = state(lambda r: r['lastAction'] == label and condition(r), since=changed)
                assert_released(name)
                record(label + ': no Android media owner or notification remains; browser PID is unchanged')

            page('remove-frame', frame=True)
            ux.tap('Play media')
            state(lambda r: not r['paused'], case=key + '-child')
            # Document-start support is a provider capability, not an Android API promise.
            # Older WebView cannot observe this cross-origin fixture; verify its fallback.
            time.sleep(1.5)
            tracked = has_session(session_text())
            ux.tap('Remove frame')
            state(lambda r: not r['frameConnected'])
            assert_released('removed-frame', stable=True)
            if tracked:
                record('Removing the last cross-origin player frame retires its session even when the frame sends no final message')
            else:
                result['limitations'].append('This WebView cannot observe the cross-origin fixture; iframe session expiry was not exercised here')
                record('Unsupported cross-origin playback keeps website controls and leaves no browser session after removal')

            for attempt in range(3):
                page('navigation-' + str(attempt))
                play()
                ux.launch(BASE + 'browser-ux.html?case=no-media')
                assert_released('navigation-' + str(attempt))
            record('Three consecutive playing-page departures release media and pending foreground starts without restarting the browser')

            page('close-tab')
            play()
            root, _ = ux.nodes()
            tabs = next((n for n in root.iter('node') if ux.visible(n) and any(
                n.get('content-desc') in ux.labels('Tabs (' + str(count) + ')') for count in range(1, 101))), None)
            assert tabs is not None, 'Tab switcher control is missing'
            ux.tap_node(tabs)
            root, _ = ux.nodes()
            candidates = []
            for row in root.iter('node'):
                children = list(row.iter('node'))
                close = [n for n in children if ux.match(n, 'Close tab') is n]
                if len(close) == 1 and ux.match(row, 'Media lifecycle lab') is not None:
                    candidates.append((len(children), close[0]))
            assert candidates, 'Cannot identify the current video tab close control'
            ux.tap_node(min(candidates, key=lambda item: item[0])[1])
            assert_released('close-tab')
            record('Closing the playing tab releases its media owner without closing the browser process')

            page('background', fixture='player-fixture.html')
            first = play(label='Play inline')
            left = time.time()
            ux.adb('shell', 'input', 'keyevent', '3')
            deadline = time.monotonic() + 9
            while time.monotonic() < deadline:
                assert has_session(session_text()), 'Valid background session expired while media was playing'
                time.sleep(.5)
            later = state(lambda r: not r['paused'] and r['currentTime'] > first['currentTime'] + 8, since=left)
            assert 'isForeground=true' in service_text(), 'Background service is not foreground'
            result['cases']['background'] = later
            ux.media_dispatch('stop')
            assert_released('background-stop', stable=True)
            record('Explicit background playback stays active past two frame deadlines; system Stop releases it')

        ux.launch()
        page('pip', fixture='player-fixture.html')
        play(label='Play fullscreen')
        fullscreen_state = state(lambda r: r['fullscreen'] and r['enhanced'] and not r['paused']
                                 and r['viewport']['width'] > r['viewport']['height'])
        ux.adb('shell', 'input', 'keyevent', '3')
        wait(pip_active, message='Home from fullscreen did not enter PiP')
        def fitted_video(row):
            # Both the accessibility window and Chromium resize asynchronously;
            # refresh the bounds instead of retaining an early transition value.
            root, _ = all_windows()
            app = next((n for n in root.iter('node') if n.get('package') == ux.PACKAGE and ux.visible(n)), None)
            if app is None:
                return False
            left, top, right, bottom = ux.bounds(app)
            result['pipWindowBounds'] = [left, top, right, bottom]
            viewport = row['viewport']
            width, height = viewport['width'] * viewport['dpr'], viewport['height'] * viewport['dpr']
            return (not row['paused'] and row['fullscreen'] and row['enhanced'] and
                    viewport['width'] < fullscreen_state['viewport']['width'] * .9 and
                    abs(width - (right - left)) <= 8 and abs(height - (bottom - top)) <= 8)

        # Pinned state precedes Chromium's final resize. A fixed sleep can capture
        # a wide fullscreen surface cropped into the small window during transition.
        # Require fresh DOM dimensions to fit the actual system PiP window first.
        result['cases']['pip-visible'] = state(fitted_video, since=time.time())
        (args.output / 'pip-visible.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))
        session_present()
        pip_close()
        assert_released('pip-closed', stable=True)
        (args.output / 'pip-closed.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))
        # A hidden WebView can stop timers. Resume the retained Activity and check
        # its actual video before sending any play action.
        ux.launch()
        result['cases']['pip-closed'] = state(lambda r: r['paused'] and not r['fullscreen'], since=time.time())
        assert_released('pip-reopened')
        record('System PiP close pauses playback and releases media despite enabled background playback; reopening stays paused')
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        if key:
            rows = json.load(urllib.request.urlopen(BASE + '__state?case=' + key, timeout=4))
            (args.output / 'failure-telemetry.json').write_text(json.dumps(rows[-60:], indent=2))
        raise
    finally:
        (args.output / 'last-session.txt').write_text(session_text())
        (args.output / 'last-activity.txt').write_text(ux.adb('shell', 'dumpsys', 'activity', 'activities'))
        (args.output / 'last-screen.xml').write_text(all_windows()[1])
        (args.output / 'last-screen.png').write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))
        (args.output / 'result.json').write_text(json.dumps(result, indent=2))
        if result['passed']:
            ux.launch(BASE + 'browser-ux.html?case=media-complete')
            for title, value in original_settings.items():
                setting(title, value)


if __name__ == '__main__':
    main()
