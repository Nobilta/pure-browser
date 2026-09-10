#!/usr/bin/env python3
"""Exercise 0.5 browser capabilities through the installed signed APK and Android UI."""
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
        path.with_suffix('.xml').write_text(ux.nodes()[1])
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
        ux.launch(self.url)
        ux.nodes()  # Enable Chromium accessibility before reloading this fixture.
        ux.tap('刷新')
        time.sleep(.4)

    def telemetry(self, condition=lambda event: True, timeout=8):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            events = json.load(urllib.request.urlopen(self.base + '__state?case=capability-page', timeout=5))
            latest = next((e for e in reversed(events) if e['url'] == self.url), None)
            if latest and time.time() - latest['receivedAt'] < 2 and condition(latest):
                return latest
            time.sleep(.3)
        raise AssertionError('Missing fixture telemetry: ' + self.url)

    def web_tap(self, key):
        event = self.telemetry()
        rectangle = event['buttons'][key]
        view = next(n for n in ux.nodes()[0].iter('node') if n.get('class') == 'android.webkit.WebView')
        x1, y1, x2, y2 = ux.bounds(view)
        scale = (x2-x1)/event['width']
        x = x1 + (rectangle['x']+rectangle['width']/2)*scale
        y = y1 + (rectangle['y']+rectangle['height']/2)*scale
        assert y1 <= y <= y2
        ux.adb('shell', 'input', 'tap', str(int(x)), str(int(y)))
        time.sleep(.5)

    def javascript_disabled(self):
        self.page()
        time.sleep(1)
        events = json.load(urllib.request.urlopen(self.base + '__state?case=capability-page'))
        assert not any(e['url'] == self.url for e in events), 'JavaScript executed while disabled'
        ux.expect('Pure capability article')

    def scroll_to(self, label):
        for _ in range(8):
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

    def site(self):
        self.page()
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

    def tabs_button(self):
        root, _ = ux.nodes()
        for count in range(1, 33):
            node = ux.match(root, '标签页（' + str(count) + '）')
            if node is not None:
                self.click(node)
                return
        raise AssertionError('Tabs button missing')

    def close_fixture_tab(self):
        self.tabs_button()
        root, _ = ux.nodes()
        candidates = [n for n in root.iter('node') if ux.match(n, 'Pure capability article') is not None
                      and ux.match(n, '关闭标签页') is not None]
        card = min(candidates, key=lambda n: len(list(n.iter('node'))))
        self.click(ux.match(card, '关闭标签页'))

    def tabs(self):
        self.page()
        self.close_fixture_tab()
        ux.tap('撤销')
        ux.expect('Pure capability article')
        self.record('Closing a page offers a working Undo action')
        self.close_fixture_tab()
        self.back()
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.launch()
        self.tabs_button()
        ux.tap('标签页操作')
        ux.tap('恢复刚关闭的标签')
        ux.expect('Pure capability article')
        self.record('Recently closed page metadata survives process restart and restores the page')
        self.snapshot('restored')

    def reader(self):
        self.page()
        ux.menu_item('阅读模式')
        ux.expect('保存离线文章') if ux.match(ux.nodes()[0], '文章已离线保存') is None else None
        ux.tap('文章文字大小')
        self.snapshot('reading')
        ux.tap('保存离线文章') if ux.match(ux.nodes()[0], '保存离线文章') is not None else ux.tap('文章已离线保存')
        ux.expect('文章已离线保存')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        ux.adb('reverse', '--remove', 'tcp:8875')
        try:
            ux.launch()
            ux.menu_item('离线文章')
            ux.tap('Pure capability article')
            self.click(self.scroll_to('Offline reading sentence: calm pages stay available without a network connection.'))
            self.snapshot('offline-reopened')
            self.record('Saved article text reopens after process restart while the fixture server is unreachable')
        finally:
            ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        self.back()
        self.back()

    def printing(self):
        self.page()
        ux.menu_item('打印或保存为 PDF')
        root, raw = ux.nodes()
        assert 'com.android.printspooler' in raw, raw
        self.snapshot('android-print')
        self.record('Print / Save PDF opens the Android print adapter with the current page')
        if ux.match(root, 'Save as PDF') is None:
            ux.tap('Select a printer')
            ux.tap('Save as PDF')
        ux.tap('Save to PDF', timeout=10)
        self.downloads_directory()
        name = 'pure-capability-print-api' + self.sdk + '-' + str(time.time_ns()) + '.pdf'
        self.enter_text(name)
        ux.tap('Save')
        path = '/sdcard/Download/' + name
        deadline = time.monotonic() + 15
        data = b''
        while time.monotonic() < deadline:
            completed = subprocess.run(ux.ADB + ['exec-out', 'cat', path], capture_output=True)
            if completed.returncode == 0 and completed.stdout.rstrip().endswith(b'%%EOF'):
                data = completed.stdout
                break
            time.sleep(.3)
        assert data.startswith(b'%PDF-') and len(data) > 2048, 'Print output is not a complete PDF'
        assert len(re.findall(rb'/Type\s*/Page\b', data)) >= 1, 'PDF has no page objects'
        (ROOT / 'results' / (self.prefix + '.pdf')).write_bytes(data)
        ux.expect('菜单')
        self.record('Android Save as PDF writes a complete document through SAF', file=path,
                    sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))

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
            ux.expect('导入 1 个网页书签？已跳过 2 个无效或重复条目。已有书签会保留，文件夹将展开为列表。')
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
            completed = subprocess.run(ux.ADB + ['shell', 'cat', path], capture_output=True, text=True)
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
                ux.menu_item('阅读模式')
                ux.expect('复制文章正文')
                ux.tap('文章文字大小')
                root, _ = ux.nodes()
                slider = next(n for n in root.iter('node') if n.get('class') == 'android.widget.SeekBar' and ux.visible(n))
                assert ux.bounds(slider)[2] - ux.bounds(slider)[0] >= 90, 'Reader text slider collapsed'
                viewport = ux.stable_display_bounds(root)
                controls = [slider] + [ux.match(root, ux._translations[0][key])
                                       for key in ('reading_mode', 'reading_copy', 'reading_save')]
                reading_regions = [n for n in root.iter('node') if n.get('scrollable') == 'true' and ux.visible(n)]
                assert reading_regions, 'Reader scroll area missing'
                controls += reading_regions
                for control in controls:
                    assert control is not None, 'Reader control missing'
                    x1, y1, x2, y2 = ux.bounds(control)
                    assert viewport[0] <= x1 < x2 <= viewport[2] and viewport[1] <= y1 < y2 <= viewport[3], \
                        'Reader control overlaps system bars: ' + str(control.attrib)
                for region in reading_regions:
                    area = ux.bounds(region)
                    assert all(abs(area[edge] - viewport[edge]) <= 2 for edge in (0, 2, 3)), \
                        'Reader applies duplicate insets or leaves unused space: ' + str(area)
                width, height = self.snapshot('reader-large-font-' + name)
                assert (width > height) == (rotation == '1'), 'Requested orientation did not take effect'
                self.back()
                ux.menu_item('网站设置')
                ux.expect('保存并刷新')
                self.scroll_to('重置网站设置')
                ux.expect('保存并刷新')
                self.snapshot('site-large-font-' + name)
                ux.tap('取消')
                self.record('Reader actions and website settings remain usable at 150% font scale in ' + name,
                            viewport=viewport, readerBounds=[ux.bounds(control) for control in controls])
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
                'apkSha256': self.apk_hash, 'section': self.section, 'status': status, 'checks': self.checks}, indent=2) + '\n')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--section', required=True, choices=['site','permissions','tabs','reader','printing','bookmarks','downloads','layout'])
    args = parser.parse_args()
    Regression(args.serial, args.section).run()
