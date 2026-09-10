#!/usr/bin/env python3
"""Exercise real menu ancestry, modal window cleanup, and rapid Back on a signed APK."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--cycles', type=int, default=24)
parser.add_argument('--label', default='menu-navigation')
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
assert re.fullmatch(r'[a-z0-9-]+', args.label)
ux.ADB = ['adb', '-s', args.serial]
sdk = ux.adb('shell', 'getprop', 'ro.build.version.sdk')
prefix = 'api' + sdk + '-' + args.label + '-' + str(time.time_ns())
out = ROOT / 'results' / prefix
out.mkdir(parents=True)
path = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
result = {'sdk': sdk, 'serial': args.serial, 'apkSha256': ux.adb('shell', 'sha256sum', path).split()[0],
          'checks': [], 'rapidCycles': 0, 'error': None}
original = {name: ux.adb('shell', 'settings', 'get', 'system', name)
            for name in ('font_scale', 'accelerometer_rotation', 'user_rotation')}


def record(name):
    result['checks'].append(name)
    print('PASS:', name, flush=True)


def foreground():
    activity = ux.adb('shell', 'dumpsys', 'activity', 'activities')
    assert re.search(r'(?:topResumedActivity|mResumedActivity)[^\n]*com\.mybrowser/[^\n]*MainActivity', activity), \
        'Browser left the foreground during menu navigation'


def window_count():
    windows = ux.adb('shell', 'dumpsys', 'window', 'windows')
    return len(re.findall(r'Window #\d+ Window\{[^}\n]*com\.mybrowser/com\.mybrowser\.MainActivity', windows))


def browser():
    deadline = time.monotonic() + 5
    while True:
        foreground()
        root, _ = ux.nodes()
        if not ux.menu_open(root) and ux.match(root, '编辑网址') is not None and window_count() == 1:
            return root
        assert time.monotonic() < deadline, 'Browser is still covered by a page, dialog or invisible sheet window'
        time.sleep(.15)


def menu():
    foreground()
    root = ux.expect_menu()
    deadline = time.monotonic() + 4
    while window_count() != 2:
        assert time.monotonic() < deadline, 'Menu has missing or duplicate dialog windows'
        time.sleep(.15)
    return root


def settings():
    ux.expect('浏览与启动')
    ux.expect('视频播放')
    foreground()
    assert window_count() == 1, 'A menu window remains above the settings page'


def back(delay=.55):
    ux.adb('shell', 'input', 'keyevent', '4')
    time.sleep(delay)


def toolbar_back():
    root, _ = ux.nodes()
    candidates = [node for node in root.iter('node') if ux.visible(node)
                  and node.get('content-desc') in ux.labels('返回')]
    assert candidates, 'Page toolbar Back is missing'
    ux.tap_node(min(candidates, key=lambda node: ux.bounds(node)[1]))


def snapshot(name):
    try:
        _, xml = ux.nodes()
        (out / (name + '.xml')).write_text(xml)
    except Exception as error:
        (out / (name + '-dump-error.txt')).write_text(str(error))
    (out / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'], timeout=20))
    for service in ('window', 'activity'):
        (out / (name + '-' + service + '.txt')).write_text(ux.adb('shell', 'dumpsys', service))


def tap_point(node):
    x1, y1, x2, y2 = ux.bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2


def sequence(events):
    encoded = base64.b64encode(json.dumps(events).encode()).decode()
    run = subprocess.run(ux.ADB + ['shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
                                  '/system/bin', 'com.mybrowser.validation.FastUiDump', 'sequence', encoded],
                         text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    receipt = re.search(r'Sequence completed in (\d+) ms', run.stdout)
    if run.returncode != 0 or receipt is None:
        (out / ('input-helper-' + str(time.time_ns()) + '.txt')).write_text(run.stdout + run.stderr)
    assert receipt, 'Input sequence did not finish; never retry possibly delivered Back events'
    return int(receipt[1])


try:
    ux.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    ux.adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    ux.adb('reverse', 'tcp:8875', 'tcp:8875')
    ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
    ux.launch('http://127.0.0.1:8875/browser-ux.html')
    ux.adb('logcat', '-c')
    browser()
    ux.tap('菜单')
    menu()
    back()
    browser()
    record('opening only the menu then Back leaves the browser interactive and foreground')

    ux.open_settings()
    settings()
    back()
    menu()
    ux.expect('设置')  # The menu retains the scroll position of the chosen row.
    snapshot('settings-back-to-menu')
    back()
    browser()
    record('settings → menu → browser takes exactly two Back presses and preserves menu scroll')

    ux.open_settings()
    settings()
    toolbar_back()
    menu()
    back()
    browser()
    record('settings toolbar Back follows the same menu ancestry')

    # No WebView navigation has happened since the fresh launch. Exercise the exit
    # confirmation within its actual two-second window, without slow hierarchy probes.
    root = browser()
    x, y = tap_point(ux.match(root, '菜单'))
    duration = sequence([['key', 4], ['tap', x, y], ['wait', 350], ['key', 4], ['wait', 350], ['key', 4]])
    result['exitResetInputMillis'] = duration
    assert duration < 2000, 'Exit reset test did not run within the confirmation window'
    browser()
    record('a prior exit attempt is disarmed by opening and closing the menu')

    for label in ('书签', '历史记录', '下载', '离线文章', '网站设置', '开发者工具'):
        ux.menu_item(label)
        time.sleep(.35)
        foreground()
        root, _ = ux.nodes()
        assert not ux.menu_open(root), 'Menu still covers child: ' + label
        assert window_count() == 2, 'Child retained a second modal window: ' + label
        back()
        menu()
        back()
        browser()
        record(label + ' returns to menu, then browser, without retaining another window')

    ux.open_settings('关于')
    ux.tap('开发者工具')
    back()
    ux.expect('Pure 浏览器')
    back()
    settings()
    back()
    menu()
    back()
    browser()
    record('developer tools opened from About retain settings category and menu ancestry')

    ux.open_settings('浏览与启动')
    ux.tap('搜索引擎')
    back()
    ux.expect('启动时恢复上次网页')
    back()
    settings()
    back()
    menu()
    back()
    browser()
    record('picker → category → settings → menu → browser returns one level at a time')

    ux.open_settings('视频播放')
    ux.adb('shell', 'input', 'keyevent', '3')
    ux.launch()
    ux.expect('增强全屏控件')
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.15')
    time.sleep(1.2)
    ux.expect('增强全屏控件')
    back()
    settings()
    back()
    menu()
    snapshot('recreated-menu')
    back()
    browser()
    ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    time.sleep(1)
    record('background/resume and Activity recreation retain the complete return path')

    # Dismiss the menu through its drag handle, not by scrolling its inner list.
    ux.tap('菜单')
    root = menu()
    handle = ux.match(root, 'Drag handle')
    if handle is None:
        handle = next((node for node in root.iter('node') if ux.visible(node)
                       and node.get('content-desc') in ('拖动句柄', '拖曳控點')), None)
    assert handle is not None, 'Menu drag handle missing'
    x, y = tap_point(handle)
    safe = ux.stable_display_bounds(root)
    assert safe[0] < x < safe[2] and safe[1] < y < safe[3], 'Menu handle overlaps a system gesture area'
    height = int(re.search(r'(\d+)x(\d+)', ux.adb('shell', 'wm', 'size'))[2])
    ux.adb('shell', 'input', 'swipe', str(x), str(y), str(x), str(height - 70), '250')
    time.sleep(.6)
    browser()
    record('drag dismissal removes the menu window and releases input')

    # Each cycle starts from an observed settings root. Rapid Back can land before
    # the remounted menu has registered its callback; in that case close that one
    # remaining menu explicitly. It must never reach exit or leave an invisible window.
    for index in range(args.cycles):
        ux.open_settings()
        ux.expect('浏览与启动')
        delay = (30, 80, 160, 300)[index % 4]
        sequence([['key', 4], ['wait', delay], ['key', 4]])
        time.sleep(.5)
        foreground()
        root, _ = ux.nodes()
        if ux.menu_open(root):
            back()
        browser()
        result['rapidCycles'] += 1
        if (index + 1) % 6 == 0:
            print('PASS: rapid menu/setting round trips', index + 1, flush=True)
    record('rapid repeated Back during sheet opening/closing never exits or leaves a blocking window')

    for delay in (0, 15, 50, 100):
        ux.open_settings()
        back()
        root = menu()
        row = ux.match(root, '设置')
        assert row is not None, 'Settings row was lost on returning to menu'
        x, y = tap_point(row)
        sequence([['tap', x, y], ['wait', delay], ['key', 4]])
        time.sleep(.6)
        foreground()
        root, _ = ux.nodes()
        if not ux.menu_open(root):
            settings()
            back()
        menu()
        back()
        browser()
    record('tapping Settings and immediately returning at 0/15/50/100 ms leaves a usable menu')

    ux.open_settings('浏览与启动')
    ux.tap('搜索引擎')
    ux.launch('http://127.0.0.1:8875/browser-ux.html?from=menu')
    browser()
    # Older WebViews can publish a stale accessibility tree after a dialog closes.
    # Reload through the actual toolbar after attaching the probe, as in browser regressions.
    ux.tap('刷新')
    ux.expect('Pure UX First Page')
    ux.tap('SPA route')
    ux.expect('Pure UX SPA Updated')
    browser()
    snapshot('final-browser-interactive')
    record('external navigation clears all menu layers; a real webpage tap still works afterwards')
except Exception as error:
    result['error'] = str(error)
    snapshot('failure')
    raise
finally:
    (out / 'logcat.txt').write_text(ux.adb('logcat', '-d', '-v', 'threadtime'))
    (out / 'events.txt').write_text(ux.adb('logcat', '-b', 'events', '-d', '-v', 'threadtime'))
    (out / 'crash.txt').write_text(ux.adb('logcat', '-b', 'crash', '-d', '-v', 'threadtime'))
    for name, value in original.items():
        if value == 'null':
            ux.adb('shell', 'settings', 'delete', 'system', name)
        else:
            ux.adb('shell', 'settings', 'put', 'system', name, value)
    (out / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('RESULT:', out / 'result.json', flush=True)
