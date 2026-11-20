#!/usr/bin/env python3
"""FTP and HLS downloads on the installed app: both protocols produce the expected bytes.

The fixture servers are started by this script so the run is self-contained:

    python3 validation/regressions/ftp-hls-regression.py --serial emulator-5554 --output validation/results/ftp-hls

It checks the saved file's digest, not just that a task reached COMPLETED: FTP must deliver the
served entity byte for byte, and HLS must append every segment of the playlist into one file.
"""
import argparse
import hashlib
import importlib.util
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ux', ROOT / 'tools/emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
fixture_spec = importlib.util.spec_from_file_location('fixtures', ROOT / 'fixtures/ftp-hls-server.py')
fixtures = importlib.util.module_from_spec(fixture_spec)
fixture_spec.loader.exec_module(fixtures)

FTP_NAME = 'pure-ftp.bin'
HLS_NAME = 'playlist.ts'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--http-port', type=int, default=8878)
    parser.add_argument('--ftp-port', type=int, default=2121)
    parser.add_argument('--passive-port', type=int, default=2122)
    parser.add_argument('--package', default=ux.PACKAGE, help='installed package, debug builds differ')
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    ux.ADB = ['adb', '-s', args.serial]
    ux.PACKAGE = args.package
    args.output.mkdir(parents=True, exist_ok=True)
    result = {'passed': False, 'checks': []}

    def record(name):
        result['checks'].append(name)
        print('PASS:', name, flush=True)

    def save(name):
        (args.output / (name + '.xml')).write_text(ux.nodes()[1], encoding='utf-8')
        (args.output / (name + '.png')).write_bytes(
            subprocess.check_output(ux.ADB + ['exec-out', 'screencap', '-p']))

    def confirm_download():
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            root, _ = ux.nodes()
            node = next((n for n in root.iter('node') if ux.visible(n)
                         and n.get('text') in ('下载', '下載', 'Download', '保存副本', '儲存副本', 'Save copy')), None)
            if node is not None:
                titles = ux.resource_labels('download_confirm_title') | ux.resource_labels('download_copy_title')
                confirmation = any(n.get('text') in titles and ux.visible(n) for n in root.iter('node'))
                ux.tap(node.get('text'))
                # A repeated attachment first offers Download on the existing-task
                # notice. That action opens the filename dialog; it does not confirm it.
                if confirmation:
                    return
            time.sleep(.3)
        raise AssertionError('Download confirmation dialog missing')

    def download_files():
        return set(ux.adb('shell', 'ls', '-1', '/sdcard/Download/').splitlines())

    def saved_digest(name, expected, before, timeout=60):
        stem, suffix = name.rsplit('.', 1)
        pattern = re.compile(re.escape(stem) + r'(?: \(\d+\))?\.' + re.escape(suffix))
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            added = [file for file in download_files() - before if pattern.fullmatch(file)]
            if added:
                assert len(added) == 1, 'One confirmation must produce one saved file'
                path = '/sdcard/Download/' + added[0]
                check = subprocess.run(ux.ADB + ['shell', 'sha256sum ' + shlex.quote(path)],
                                       capture_output=True, text=True, check=True)
                actual = check.stdout.split()[0]
                assert actual == expected, f'{name} digest {actual} != {expected}'
                return added[0]
            time.sleep(.3)
        raise AssertionError('New saved file missing: ' + name)

    def digest(data):
        return hashlib.sha256(data).hexdigest()

    server = subprocess.Popen(
        [sys.executable, str(ROOT / 'fixtures/ftp-hls-server.py'),
         '--ftp-port', str(args.ftp_port), '--passive-port', str(args.passive_port),
         '--http-port', str(args.http_port)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    try:
        deadline = time.monotonic() + 10
        while 'HTTP on' not in (server.stdout.readline() if server.poll() is None else ''):
            assert time.monotonic() < deadline, 'Fixture servers did not start'
        for port in (args.ftp_port, args.passive_port, args.http_port):
            ux.adb('reverse', f'tcp:{port}', f'tcp:{port}')
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)

        ux.launch(f'http://127.0.0.1:{args.http_port}/ftp-hls-fixture.html')
        ux.expect('FTP and HLS downloads')
        save('fixture')

        before = download_files()
        ux.tap('下载 FTP 文件')
        confirm_download()
        ftp_name = saved_digest(FTP_NAME, digest(fixtures.payload(FTP_NAME)), before)
        save('ftp-complete')
        record('an FTP link saves the served entity byte for byte')

        before = download_files()
        ux.tap('下载 HLS 视频')
        confirm_download()
        expected = fixtures.payload('one.ts') + fixtures.payload('two.ts')
        hls_name = saved_digest(HLS_NAME, digest(expected), before)
        save('hls-complete')
        record('an HLS playlist is appended into one .ts file and matches its segments')

        # An existing attachment opens Downloads focused on its previous record.
        # Re-enter from the menu to check the new copies at the top of the list.
        if ux.match(ux.nodes()[0], '下载管理') is not None:
            ux.adb('shell', 'input', 'keyevent', '4')
            time.sleep(.5)
        ux.menu_item('下载')
        ux.expect('下载管理')
        ux.expect(ftp_name)
        ux.expect(hls_name)
        save('download-list')
        record('both tasks are listed in the download manager')
        result['passed'] = True
    except Exception as error:
        result['error'] = repr(error)
        save('failure')
        raise
    finally:
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        server.terminate()
        server.wait(timeout=5)


if __name__ == '__main__':
    main()
