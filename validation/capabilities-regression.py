#!/usr/bin/env python3
"""Exercise website, bookmark and download capabilities through the installed signed APK and Android UI."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)

class Regression:
    def __init__(self, serial, section):
        ux.ADB = ['adb', '-s', serial]
        self.section = section
        self.sdk = ux.adb('shell', 'getprop', 'ro.build.version.sdk')
        self.prefix = 'api' + self.sdk + '-capabilities-' + section
        self.base = 'http://127.0.0.1:8875/'
        self.checks = []
        path = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2]
        self.apk_hash = ux.adb('shell', 'sha256sum', path).split()[0]
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        ux.adb('reverse', 'tcp:8876', 'tcp:8876')

    def record(self, text, **details):
        self.checks.append({'check': text, **details})
        print('PASS:', text, json.dumps(details), flush=True)

    def snapshot(self, name):
        path = ROOT / 'results' / (self.prefix + '-' + name)
        path.with_suffix('.xml').write_text(ux.nodes()[1], encoding="utf-8")
        png = subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p'])
        path.with_suffix('.png').write_bytes(png)
        return struct.unpack('>II', png[16:24])

    def click(self, node):
        assert node is not None
        x1, y1, x2, y2 = ux.bounds(node)
        ux.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.5)

    def back(self):
        ux.adb('shell', 'input', 'keyevent', '4')
        time.sleep(.5)

    def page(self, port=8875):
        self.url = 'http://127.0.0.1:' + str(port) + '/capabilities-fixture.html?visit=' + str(time.time_ns())
        # Enable Chromium accessibility before navigation; telemetry observes the
        # requested URL without issuing a second load through the refresh button.
        ux.nodes()
        ux.launch(self.url)

    def telemetry(self, condition=lambda event: True, timeout=8):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            events = json.load(urllib.request.urlopen(self.base + '__state?case=capability-page', timeout=5))
            latest = next((e for e in reversed(events) if e['url'] == self.url), None)
            if latest and time.time() - latest['receivedAt'] < 2 and condition(latest):
                return latest
            time.sleep(.3)
        raise AssertionError('Missing fixture telemetry: ' + self.url)

    def tap_rectangle(self, event, rectangle):
        view = next(n for n in ux.nodes()[0].iter('node') if n.get('class') == 'android.webkit.WebView')
        x1, y1, x2, y2 = ux.bounds(view)
        scale = (x2-x1)/event['width']
        x = x1 + (rectangle['x']+rectangle['width']/2)*scale
        y = y1 + (rectangle['y']+rectangle['height']/2)*scale
        assert y1 <= y <= y2
        ux.adb('shell', 'input', 'tap', str(int(x)), str(int(y)))

    def web_tap(self, key):
        # The cosmetic filter hides the fixture's ad element shortly after load,
        # shifting everything below it. One telemetry snapshot can therefore
        # describe a pre-filter layout that no longer exists at tap time, so the
        # tap lands below the real element. Require two consecutive reports with
        # identical geometry, then re-check after the tap and tap again if the
        # element still moved (the first tap missed and had no effect).
        rect = self.telemetry()['buttons'][key]
        deadline = time.monotonic() + 8
        while True:
            time.sleep(.45)  # the fixture reports every 400ms
            event = self.telemetry()
            current = event['buttons'][key]
            if all(abs(current[k] - rect[k]) < 1 for k in ('x', 'y', 'width', 'height')):
                break
            rect = current
            assert time.monotonic() < deadline, 'Fixture layout never settled for ' + key
        self.tap_rectangle(event, rect)
        time.sleep(.6)  # let a post-tap report arrive
        settled = self.telemetry()
        moved = settled['buttons'][key]
        if any(abs(moved[k] - rect[k]) >= 1 for k in ('x', 'y')):
            self.tap_rectangle(settled, moved)
        time.sleep(.5)

    def javascript_disabled(self):
        self.page()
        time.sleep(1)
        events = json.load(urllib.request.urlopen(self.base + '__state?case=capability-page'))
        assert not any(e['url'] == self.url for e in events), 'JavaScript executed while disabled'
        ux.expect('Pure capability article')

    def scroll_to(self, label):
        # Landscape at large font sizes exposes only a few rows per swipe.
        for _ in range(24):
            root, _ = ux.nodes()
            node = ux.match(root, label)
            if node is not None:
                return node
            regions = [n for n in root.iter('node') if n.get('scrollable') == 'true' and ux.visible(n)]
            assert regions, 'No scroll container for ' + label
            region = max(regions, key=lambda n: ux.bounds(n)[3] - ux.bounds(n)[1])
            x1, y1, x2, y2 = ux.bounds(region)
            ux.adb('shell', 'input', 'swipe', str((x1+x2)//2), str(y1+(y2-y1)*4//5),
                   str((x1+x2)//2), str(y1+(y2-y1)//4), '350')
            time.sleep(.4)
        raise AssertionError('Missing: ' + label)

    def last_button(self, label):
        variants = ux.labels(label)
        nodes = [n for n in ux.nodes()[0].iter('node') if ux.visible(n) and n.get('text') in variants]
        self.click(nodes[-1])

    def enter_text(self, text):
        field = next(n for n in ux.nodes()[0].iter('node')
                     if n.get('class') == 'android.widget.EditText' and ux.visible(n))
        self.click(field)
        encoded = base64.b64encode(text.encode()).decode()
        for _ in range(3):
            try:
                ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false', '/system/bin',
                       'com.mybrowser.validation.FastUiDump', 'setText', encoded)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                pass  # Old ART can fail on shutdown after successfully editing the field.
            if any(n.get('text') == text for n in ux.nodes()[0].iter('node')
                   if n.get('class') == 'android.widget.EditText'):
                return
            time.sleep(.3)
        raise AssertionError('Text field did not accept ' + text)

    def downloads_directory(self):
        ux.open_downloads_directory()

    def choose_document(self, name):
        ux.choose_download_document(name)

    def set_switch(self, label, checked):
        self.scroll_to(label)
        containers = [n for n in ux.nodes()[0].iter('node') if ux.match(n, label) is not None
                      and any(c.get('checkable') == 'true' for c in n.iter('node'))]
        container = min(containers, key=lambda n: len(list(n.iter('node'))))
        switch = next(c for c in container.iter('node') if c.get('checkable') == 'true')
        if switch.get('checked') != str(checked).lower():
            self.click(switch)

    def reset_site(self):
        ux.menu_item('网站设置')
        self.click(self.scroll_to('重置网站设置'))
        self.last_button('重置网站设置')
        time.sleep(.5)

    def site_edges(self):
        ux.menu_item('网站设置')
        root, _ = ux.nodes()
        region = max((n for n in root.iter('node') if n.get('scrollable') == 'true' and ux.visible(n)),
                     key=lambda n: ux.bounds(n)[3] - ux.bounds(n)[1])
        x1, y1, x2, y2 = ux.bounds(region)
        header = ux.bounds(ux.match(root, '网站设置'))
        footer = ux.bounds(ux.match(root, '保存并刷新'))

        def pixels():
            # Compare rendered pixels: accessibility bounds do not expose stretch.
            # Exclude the system bars and the webpage behind the scrim.
            raw = subprocess.check_output(ux.ADB + ['exec-out', 'screencap'], timeout=20)
            width, height, pixel_format = struct.unpack('<III', raw[:12])
            assert pixel_format == 1 and len(raw) >= width * height * 4, 'Expected RGBA screenshot'
            rgba = raw[-width * height * 4:]
            def strip(top, bottom):
                return hashlib.sha256(b''.join(rgba[(y * width + x1 + 8) * 4:(y * width + x2 - 8) * 4]
                                               for y in range(top, bottom))).hexdigest()
            return {'body': strip(y1 + 8, y2 - 8),
                    'header': strip(header[1] - 8, y1 - 8),
                    'footer': strip(y2 + 8, footer[3] + 8)}

        anchors = pixels()
        for edge in ('bottom', 'top'):
            low, high = y1 + (y2 - y1) * 4 // 5, y1 + (y2 - y1) // 5
            start, end = (low, high) if edge == 'bottom' else (high, low)
            for duration in (350, 120, 120, 120, 350, 120):
                ux.adb('shell', 'input', 'swipe', str((x1+x2)//2), str(start),
                       str((x1+x2)//2), str(end), str(duration))
            time.sleep(1)
            root, _ = ux.nodes()
            # Compose may change a Text node's clipped bottom bound after a scroll
            # even when its pixels do not move. Check its anchor and rendered strip.
            assert ux.bounds(ux.match(root, '网站设置'))[:2] == header[:2], 'Edge fling moved or dismissed the sheet'
            assert ux.bounds(ux.match(root, '保存并刷新'))[:2] == footer[:2], 'Edge fling moved the fixed action row'
            frames = []
            for _ in range(3):
                frame = pixels()
                assert all(frame[key] == anchors[key] for key in ('header', 'footer')), 'Edge fling changed a fixed action/header'
                frames.append(frame['body'])
                time.sleep(.25)
            assert len(set(frames)) == 1, 'Site form continues animating after the ' + edge + ' fling'
            self.snapshot('scroll-' + edge)
            self.record('Repeated ' + edge + ' edge flings leave a stationary, usable website form',
                        frameHashes=frames, header=header, footer=footer,
                        headerHash=anchors['header'], footerHash=anchors['footer'])
        ux.tap('取消')
        ux.expect_menu()
        self.back()

    def site(self):
        self.page()
        self.site_edges()
        self.reset_site()
        self.telemetry(lambda e: e['hidden'])
        ux.menu_item('网站设置')
        self.set_switch('过滤此网站的广告', False)
        self.set_switch('JavaScript', False)
        self.snapshot('preferences')
        ux.tap('保存并刷新')
        self.javascript_disabled()
        self.snapshot('javascript-disabled')
        self.page(port=8876)
        self.telemetry()
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        self.javascript_disabled()
        ux.menu_item('网站设置')
        self.set_switch('JavaScript', True)
        ux.tap('保存并刷新')
        time.sleep(1)
        self.telemetry(lambda e: not e['hidden'])
        self.record('JavaScript and filtering apply per origin, including port isolation and process restart')
        ux.menu_item('网站设置')
        self.set_switch('桌面版网站', True)
        ux.tap('保存并刷新')
        self.telemetry(lambda e: e['desktop'])
        self.reset_site()
        self.telemetry(lambda e: not e['desktop'])
        self.record('Desktop mode is a site preference and reset restores defaults')

    def permissions(self):
        self.page()
        self.reset_site()
        ux.adb('shell', 'pm', 'grant', ux.PACKAGE, 'android.permission.CAMERA')
        started = time.time()
        self.web_tap('camera')
        ux.expect('网站请求权限')
        ux.expect('http://127.0.0.1:8875')
        self.snapshot('camera-consent')
        ux.tap('记住此网站的选择')
        ux.tap('阻止')
        time.sleep(.5)
        self.web_tap('camera')
        ux.expect('网站请求权限', present=False)
        events = json.load(urllib.request.urlopen(self.base + '__state?case=capability-permissions'))
        denied = [e for e in events if e['receivedAt'] >= started and e.get('kind') == 'camera' and e.get('result') != 'allowed']
        assert len(denied) >= 2, events
        self.record('Android camera permission does not bypass website consent; remembered denial suppresses later prompts')
        self.reset_site()
        self.web_tap('location')
        ux.expect('网站请求权限')
        self.snapshot('location-consent')
        self.page(port=8876)
        ux.expect('网站请求权限', present=False)
        self.web_tap('location')
        ux.expect('网站请求权限')
        ux.expect('http://127.0.0.1:8876')
        ux.tap('阻止')
        self.record('Navigation dismisses the old permission request; another port requests its own consent')
        self.page()

    def bookmarks(self):
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        self.page()
        fixture = 'capabilities-bookmarks.html'
        ux.adb('push', str(ROOT / fixture), '/sdcard/Download/' + fixture)
        ux.menu_item('书签')
        for attempt in range(2):
            ux.tap('导入或导出书签')
            ux.tap('导入书签')
            self.choose_document(fixture)
            ux.expect('Web bookmarks to import: 1. Invalid or duplicate entries skipped: 2. Existing bookmarks will be kept.')
            self.snapshot('import-preview-' + str(attempt))
            self.last_button('导入书签')
            # The pagination regression may have seeded newer dates; search the whole library.
            self.enter_text('capability-import')
            self.back()
            ux.expect('Imported & escaped fixture')
            ux.expect('Duplicate fixture', present=False)
            ux.expect('Rejected script fixture', present=False)
        self.record('SAF imports a Netscape bookmark file with preview, safe URLs and repeat-import deduplication')
        ux.tap('导入或导出书签')
        ux.tap('导出书签')
        name = 'pure-capability-bookmarks-api' + self.sdk + '-' + str(time.time_ns()) + '.html'
        self.downloads_directory()
        self.enter_text(name)
        ux.tap('Save')
        deadline = time.monotonic() + 10
        text = ''
        path = '/sdcard/Download/' + name
        while time.monotonic() < deadline:
            completed = subprocess.run(ux.ADB + ['shell', 'cat', path], capture_output=True, text=True, encoding="utf-8")
            if completed.returncode == 0 and completed.stdout.endswith('</DL><p>\n'):
                text = completed.stdout
                break
            time.sleep(.3)
        assert text.startswith('<!DOCTYPE NETSCAPE-Bookmark-file-1>'), text
        assert text.count('https://example.com/capability-import?a=1&amp;b=2') == 1, text
        assert 'Imported &amp; escaped fixture' in text
        assert 'Duplicate fixture' not in text and 'javascript:' not in text
        self.snapshot('exported')
        self.record('SAF exports escaped UTF-8 HTML with exactly one imported URL and no executable link',
                    file=path, sha256=hashlib.sha256(text.encode()).hexdigest())
        self.back()

    def download_requests(self, since):
        events = json.load(urllib.request.urlopen(self.base + '__state?case=download-requests', timeout=5))
        return [e for e in events if e.get('path') == '/download-resume.bin' and e['receivedAt'] >= since]

    def wait_ranges(self, since, previous_starts):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            ranges = [e for e in self.download_requests(since) if e.get('ifRange') == '"pure-range-fixture-v1"'
                      and e['start'] > 0 and e['start'] not in previous_starts]
            if ranges:
                return ranges
            time.sleep(.5)
        raise AssertionError('No validator-backed request from a retained partial offset')

    def downloads(self):
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        self.page()
        before = set(ux.adb('shell', 'sh', '-c', 'ls /sdcard/Download/pure-resume-test*.bin 2>/dev/null || true').splitlines())
        started = time.time()
        self.web_tap('download')
        # The download confirmation dialog is deliberate design and has no skip setting.
        # The WebView may take a moment to report the download, so poll for the button.
        deadline = time.monotonic() + 10
        while True:
            root, _ = ux.nodes()
            node = next((n for n in root.iter('node') if ux.visible(n)
                         and n.get('text') in ('下载', '下載', 'Download')), None)
            if node is not None:
                ux.tap_node(node)
                break
            assert time.monotonic() < deadline, 'Download confirmation dialog missing'
            time.sleep(.3)
        ux.menu_item('下载')
        ux.expect('暂停下载')
        time.sleep(3)
        initial = self.download_requests(started)
        starts = {e['start'] for e in initial}
        assert any(e['start'] > 0 for e in initial), 'Download did not use concurrent ranges'
        ux.tap('暂停下载')
        ux.expect('继续下载')
        self.snapshot('paused')
        paused_at = time.time()
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.launch()
        ux.menu_item('下载')
        ux.expect('继续下载')
        assert not self.download_requests(paused_at), 'User-paused task restarted automatically'
        self.record('Pause retains a real ranged download and remains paused after process restart')
        resumed_at = time.time()
        ux.tap('继续下载')
        resumed = self.wait_ranges(resumed_at, starts)
        ux.expect('暂停下载')
        time.sleep(3)
        self.snapshot('resumed')
        self.record('Resume uses If-Range and starts after retained bytes', ranges=resumed)
        all_starts = {e['start'] for e in self.download_requests(started)}
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        restarted_at = time.time()
        ux.launch()
        recovered = self.wait_ranges(restarted_at, all_starts)
        ux.menu_item('下载')
        ux.expect('暂停下载')
        self.snapshot('process-recovered')
        self.record('A running ordinary task automatically resumes validated parts after process death', ranges=recovered)
        expected = hashlib.sha256(bytes(range(256)) * (48 * 1024 * 1024 // 256)).hexdigest()
        deadline = time.monotonic() + 150
        verified = None
        while time.monotonic() < deadline:
            files = set(ux.adb('shell', 'sh', '-c', 'ls /sdcard/Download/pure-resume-test*.bin 2>/dev/null || true').splitlines())
            for path in files - before:
                actual = ux.adb('shell', 'sha256sum', path).split()[0]
                if actual == expected:
                    verified = path
                    break
            if verified:
                break
            time.sleep(2)
        assert verified, 'Recovered file did not match the complete 48 MiB fixture'
        ux.expect('暂停下载', present=False)
        self.snapshot('completed')
        self.record('Final recovered file matches all 48 MiB; foreground service stops', file=verified, sha256=expected)
        assert 'isForeground=true' not in ux.adb('shell', 'dumpsys', 'activity', 'services', ux.PACKAGE)
        self.back()

    def layout(self):
        old_font = ux.adb('shell', 'settings', 'get', 'system', 'font_scale')
        old_rotation = ux.adb('shell', 'settings', 'get', 'system', 'user_rotation')
        old_auto = ux.adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation')
        try:
            ux.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.5')
            ux.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
            for rotation, name in [('0', 'portrait'), ('1', 'landscape')]:
                ux.adb('shell', 'settings', 'put', 'system', 'user_rotation', rotation)
                time.sleep(1)
                self.page()
                ux.menu_item('网站设置')
                ux.expect('保存并刷新')
                self.scroll_to('重置网站设置')
                ux.expect('保存并刷新')
                self.snapshot('site-large-font-' + name)
                ux.tap('取消')
                self.record('Website settings remain usable at 150% font scale in ' + name)
        except Exception:
            self.snapshot('failure-before-restore')
            raise
        finally:
            for key, value in [('font_scale', old_font), ('user_rotation', old_rotation), ('accelerometer_rotation', old_auto)]:
                ux.adb('shell', 'settings', 'delete', 'system', key) if value == 'null' else ux.adb('shell', 'settings', 'put', 'system', key, value)

    def run(self):
        status = 'completed'
        try:
            getattr(self, self.section)()
        except Exception:
            status = 'failed'
            self.snapshot('failure')
            raise
        finally:
            (ROOT / 'results' / (self.prefix + '.json')).write_text(json.dumps({'sdk': self.sdk,
                'apkSha256': self.apk_hash, 'section': self.section, 'status': status, 'checks': self.checks}, indent=2) + '\n', encoding="utf-8")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--section', required=True, choices=['site','permissions','bookmarks','downloads','layout'])
    args = parser.parse_args()
    Regression(args.serial, args.section).run()
