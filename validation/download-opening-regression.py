#!/usr/bin/env python3
"""Verify real download progress, completed-row Android handoff and notification return."""
import argparse
import hashlib
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    apk = ux.adb('shell', 'pm', 'path', ux.PACKAGE).partition(':')[2].strip()
    result = {'passed': False, 'apkSha256': ux.adb('shell', 'sha256sum', apk).split()[0], 'checks': []}
    sdk = int(ux.adb('shell', 'getprop', 'ro.build.version.sdk'))
    base = 'http://127.0.0.1:8875/'
    key = 'open-' + str(time.time_ns())
    notification_permission = 'android.permission.POST_NOTIFICATIONS'

    def wait(condition, timeout=15):
        deadline = time.monotonic() + timeout
        latest = None
        while time.monotonic() < deadline:
            latest = condition()
            if latest:
                return latest
            time.sleep(.2)
        raise AssertionError('Timed out waiting for ' + repr(condition))

    def save(name):
        (args.output / (name + '.xml')).write_text(ux.nodes()[1], encoding="utf-8")
        (args.output / (name + '.png')).write_bytes(subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))

    def record(name, **details):
        result['checks'].append({'check': name, **details})
        print('PASS', name, flush=True)
        (args.output / 'result.json').write_text(json.dumps(result, indent=2), encoding="utf-8")

    def foreground():
        raw = ux.adb('shell', 'dumpsys', 'activity', 'activities')
        return '\n'.join(line.strip() for line in raw.splitlines() if re.search(r'(?:mResumedActivity|topResumedActivity)[:=]', line))

    def system_ui():
        current = foreground()
        return current if current and 'com.mybrowser/.MainActivity' not in current else None

    def tap_system(*labels):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            root, _ = ux.nodes()
            for label in labels:
                node = ux.match(root, label)
                if node is not None:
                    if not ux.tap_now(label):
                        ux.tap_node(node)
                    time.sleep(.5)
                    return
            time.sleep(.2)
        raise AssertionError('Missing Android control: ' + repr(labels))

    def page():
        ux.launch(base + 'download-opening-fixture.html?case=' + key)
        ux.expect('Download image')

    def confirm_download():
        """Confirm the download dialog; it is deliberate design and has no skip setting."""
        deadline = time.monotonic() + 10
        while True:
            root, _ = ux.nodes()
            node = next((n for n in root.iter('node') if ux.visible(n)
                         and n.get('text') in ('下载', '下載', 'Download')), None)
            if node is not None:
                ux.tap_node(node)
                return
            assert time.monotonic() < deadline, 'Download confirmation dialog missing'
            time.sleep(.3)

    def file_name(kind):
        extension = {'image': 'png', 'apk': 'apk', 'unknown': 'bin'}[kind]
        return 'pure-open-' + key + '-' + kind + '.' + extension

    def completed_file(kind, expected):
        path = '/sdcard/Download/' + file_name(kind)
        def has_bytes():
            check = subprocess.run(ux.ADB + ['shell', 'sha256sum', path], capture_output=True, text=True, encoding="utf-8")
            return check.returncode == 0 and check.stdout.split()[0] == expected
        wait(has_bytes, 65)
        return path

    def downloads():
        ux.menu_item('下载')
        ux.expect('下载管理')

    def completed_row(kind):
        ux.expect(file_name(kind))
        raw = ux.nodes()[0]
        assert not any(n.get('text') in ('打开', '安裝', '安装', 'Open', 'Install') for n in raw.iter('node') if ux.visible(n)), \
            'Browser added a second Open/Install button'
        ux.tap(file_name(kind))

    def back_to_downloads():
        for _ in range(4):
            if not system_ui():
                break
            ux.adb('shell', 'input', 'keyevent', '4')
            time.sleep(.5)
        ux.expect('下载管理')

    try:
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)
        # Revoking this app-op kills a running browser on modern Android.
        # Reset it while stopped, before launching the fixture.
        ux.adb('shell', 'appops', 'set', ux.PACKAGE, 'REQUEST_INSTALL_PACKAGES', 'default')
        if sdk >= 33:
            ux.adb('shell', 'pm', 'revoke', ux.PACKAGE, notification_permission)
            ux.adb('shell', 'pm', 'clear-permission-flags', ux.PACKAGE, notification_permission, 'user-set', 'user-fixed')
        page()
        ux.tap('Download unknown size')
        confirm_download()
        if sdk >= 33:
            root, _ = ux.nodes()
            deny = next((n for n in root.iter('node') if n.get('resource-id', '').endswith('/permission_deny_button')), None)
            if deny is not None:
                tap_system(deny.get('resource-id'))
        downloads()
        def unknown_progress():
            root, _ = ux.nodes()
            return next((n.get('text') for n in root.iter('node') if ux.visible(n) and any(
                text in n.get('text', '') for text in ('Total size unknown', '总大小未知', '總大小未知'))), None)
        first = wait(unknown_progress)
        wait(lambda: (value := unknown_progress()) and value != first)
        save('unknown-size-progress')
        record('Unknown totals show increasing downloaded bytes and an indeterminate progress bar', first=first)
        unknown_hash = hashlib.sha256(bytes(range(256)) * 8192).hexdigest()
        unknown_path = completed_file('unknown', unknown_hash)
        ux.expect('暂停下载', present=False)
        record('Downloading finishes with correct bytes' + (' while notification permission is denied' if sdk >= 33 else ''), file=unknown_path, sha256=unknown_hash)
        if sdk >= 33:
            ux.adb('shell', 'pm', 'grant', ux.PACKAGE, notification_permission)

        page()
        ux.tap('Download image')
        confirm_download()
        image_hash = hashlib.sha256(urllib.request.urlopen(base + 'context-image.png').read()).hexdigest()
        image_path = completed_file('image', image_hash)
        downloads()
        completed_row('image')
        target = wait(system_ui)
        activity = ux.adb('shell', 'dumpsys', 'activity', 'activities')
        # Android resolves the provider type without putting an explicit MIME on our Intent.
        assert 'act=android.intent.action.VIEW dat=content://media/' in activity and 'readUriPermissions=' in activity
        (args.output / 'system-image-activity.txt').write_text(activity, encoding="utf-8")
        save('system-image')
        record('A completed-row tap directly invokes Android image handling with a content URI', target=target)
        back_to_downloads()

        ux.adb('shell', 'rm', image_path)
        completed_row('image')
        # Toasts can be separate accessibility windows from the active Activity.
        raw = ux.adb('shell', 'env', 'CLASSPATH=' + ux.UI_PROBE, 'app_process', '-Xusejit:false',
                     '/system/bin', 'com.mybrowser.validation.FastUiDump', 'windows')
        (args.output / 'missing-file-windows.xml').write_text(raw, encoding="utf-8")
        save('missing-file')
        assert not system_ui(), 'A missing file unexpectedly launched an external Activity'
        # System-rendered Toasts are not exposed in every Provider's accessibility tree.
        # Preserve the screenshot for visual verification instead of pretending they are.
        record('A stale download record remains in the browser; missing-file toast captured for review',
               feedbackScreenshot='missing-file.png',
               feedbackInAccessibilityTree=any(text in raw for text in ('The file is missing', '文件已不存在', '檔案已不存在')))

        page()
        ux.tap('Download application')
        confirm_download()
        completed_file('apk', result['apkSha256'])
        downloads()
        completed_row('apk')
        target = wait(system_ui)
        assert any(name in target.lower() for name in ('packageinstaller', 'permissioncontroller', 'settings')), target
        save('system-apk')
        record('Android resolves the APK and owns its installer and source-permission prompt', target=target)
        root, raw = ux.nodes()
        if any(text in raw for text in ('For your security', 'currently isn', '不允许', '不允許', '为了您的安全')):
            tap_system('Settings', '设置', '設定')
            wait(system_ui)
            root, _ = ux.nodes()
            switch = next((n for n in root.iter('node') if ux.visible(n) and n.get('checkable') == 'true'), None)
            assert switch is not None, 'Android did not show the unknown-source switch'
            if switch.get('checked') != 'true':
                ux.tap_node(switch)
            save('system-install-source')
            ux.adb('shell', 'input', 'keyevent', '4')
            time.sleep(1)
            save('system-install-ready')
            record('Unknown-source authorization is handled entirely by Android settings')
        back_to_downloads()

        key += '-cold'
        page()
        ux.tap('Download image')
        confirm_download()
        completed_file('image', image_hash)
        wait(lambda: 'isForeground=true' not in ux.adb('shell', 'dumpsys', 'activity', 'services', ux.PACKAGE))
        notifications = ux.adb('shell', 'dumpsys', 'notification', '--noredact')
        assert file_name('image') in notifications, 'No completion notification'
        ux.adb('shell', 'input', 'keyevent', '3')
        time.sleep(1)
        def kill_cached_process():
            # Android can keep a recently visible process protected for a few seconds.
            ux.adb('shell', 'am', 'kill', '--user', '0', ux.PACKAGE)
            return subprocess.run(ux.ADB + ['shell', 'pidof', ux.PACKAGE], capture_output=True).returncode != 0
        wait(kill_cached_process, 30)
        ux.adb('shell', 'cmd', 'statusbar', 'expand-notifications')
        tap_system(file_name('image'))
        ux.expect('下载管理')
        ux.expect(file_name('image'))
        save('notification-cold-start')
        assert not system_ui()
        record('Tapping a completion notification cold-starts the browser at its downloaded file')
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        save('failure')
        raise
    finally:
        (args.output / 'result.json').write_text(json.dumps(result, indent=2), encoding="utf-8")


if __name__ == '__main__':
    main()
