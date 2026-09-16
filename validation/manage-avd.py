#!/usr/bin/env python3
"""Prepare and start the emulator the regression harness expects, on any host OS.

The harness needs an API the app supports (30 or newer), a google_apis image — the update-launch
stage calls `adb root`, which Play Store images refuse — and an ABI the host runs natively:
arm64-v8a on Apple Silicon, x86_64 elsewhere. The official release ships arm64-v8a only, so on an
x86_64 host build the test package with -Pmybrowser.abi=x86_64; see TESTING_GUIDE.md.
"""
import argparse
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import time

from androidhost import find_tool, sdk_root
from hostencoding import ensure_utf8

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DEVICE = 'pixel_7'


def default_api():
    """The API the project targets, so the emulator matches the release configuration."""
    catalog = (ROOT / 'gradle/libs.versions.toml').read_text(encoding='utf-8')
    match = re.search(r'^targetSdk\s*=\s*"(\d+)"', catalog, re.M)
    return match.group(1) if match else '37'


def host_abi():
    machine = platform.machine().lower()
    return 'arm64-v8a' if machine in ('arm64', 'aarch64') else 'x86_64'


def installed_images(api, abi):
    """Ask sdkmanager which google_apis images exist for this API and ABI.

    Package ids carry an API level that may or may not have a minor version (android-37.0), so
    listing beats guessing.
    """
    sdkmanager = find_tool('sdkmanager')
    if not sdkmanager:
        raise SystemExit('sdkmanager not found; install the Android command-line tools')
    listed = subprocess.run([str(sdkmanager), '--list'], capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=300)
    # Rows look like "  system-images;android-37.0;google_apis;arm64-v8a | 6 | Google APIs ...".
    pattern = re.compile(r'^\s*(system-images;android-' + re.escape(api) + r'(?:\.\d+)?)'
                         r';google_apis;' + re.escape(abi) + r'(?=\s*\|)', re.M)
    return sorted({match.group(0) for match in pattern.finditer(listed.stdout or '')})


def system_image(api, abi):
    available = installed_images(api, abi)
    if available:
        return available[-1]
    print(f'warn: no google_apis {abi} image listed for API {api}; '
          f'run "sdkmanager --list" and pass --image explicitly', file=sys.stderr)
    return f'system-images;android-{api};google_apis;{abi}'


def require(name):
    tool = find_tool(name)
    if not tool:
        raise SystemExit(f'{name} not found in {sdk_root()}; install the Android command-line tools')
    return tool


def create(args):
    sdkmanager, avdmanager = require('sdkmanager'), require('avdmanager')
    image = args.image or system_image(args.api, args.abi)
    packages = ['platform-tools', 'emulator', f'platforms;android-{args.api}', image]
    print('安装：', *packages, sep='\n  ')
    subprocess.run([str(sdkmanager), '--install', *packages], check=True)
    command = [str(avdmanager), 'create', 'avd', '--name', args.name, '--package', image,
               '--device', args.device, '--force']
    print('创建 AVD：', ' '.join(command), sep='\n  ')
    subprocess.run(command, input='no\n', text=True, check=True)
    print(f'\nAVD {args.name} 已创建（{image}）。')
    if args.abi == 'x86_64':
        print('x86_64 主机需要测试签名的 x86_64 包，官方 APK 只有 arm64-v8a：')
        print('  ./gradlew -Pmybrowser.abi=x86_64 :app:assembleRelease')
    print(f'启动：python3 validation/manage-avd.py start {args.name}')


def wait_for_boot(serial, timeout):
    adb = require('adb')
    deadline = time.monotonic() + timeout
    subprocess.run([str(adb), '-s', serial, 'wait-for-device'], check=True, timeout=timeout)
    while time.monotonic() < deadline:
        completed = subprocess.run([str(adb), '-s', serial, 'shell', 'getprop', 'sys.boot_completed'],
                                   capture_output=True, text=True, encoding='utf-8')
        if (completed.stdout or '').strip() == '1':
            return adb
        time.sleep(3)
    raise SystemExit(f'{serial} did not finish booting within {timeout}s')


def start(args):
    emulator = require('emulator')
    running = subprocess.run([str(emulator), '-list-avds'], capture_output=True, text=True,
                             encoding='utf-8').stdout.split()
    if args.name not in running:
        raise SystemExit(f'unknown AVD {args.name}; create it with "manage-avd.py create {args.name}"')
    # Detach so the emulator survives this script and the following regression run.
    extra = {'creationflags': subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP} \
        if os.name == 'nt' else {'start_new_session': True}
    subprocess.Popen([str(emulator), '-avd', args.name, '-no-snapshot-save', '-no-boot-anim'],
                     stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, **extra)
    adb = wait_for_boot(args.serial, args.timeout)
    # adb reports on stderr and fails on Play Store images ("cannot run as root in production builds").
    rooted = subprocess.run([str(adb), '-s', args.serial, 'root'], capture_output=True,
                            text=True, encoding='utf-8')
    can_root = rooted.returncode == 0 and 'cannot run as root' not in (rooted.stderr or '')
    state = '可 root' if can_root else 'adb root 不可用；update-launch 阶段需要 google_apis 镜像'
    print(f'{args.serial} 已启动（{state}）。')
    print('下一步：')
    print(f'  bash install_and_test.sh                     # 安装被测包（x86_64 主机传测试包路径）')
    print(f'  python3 validation/setup-ui-probe.py {args.serial}')
    print(f'  python3 validation/qa-server.py --apk <被测算 APK>   # 另一个终端')
    print(f'  python3 validation/run-regressions.py --serial {args.serial} --apk <被测算 APK> --label <标签>')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)

    image = commands.add_parser('image', help='print the system image this host should use')
    image.add_argument('--api', default=default_api())
    image.add_argument('--abi', default=host_abi())

    create_parser = commands.add_parser('create', help='install packages and create the AVD')
    create_parser.add_argument('name')
    create_parser.add_argument('--api', default=default_api())
    create_parser.add_argument('--abi', default=host_abi())
    create_parser.add_argument('--image', help='override the system image package id')
    create_parser.add_argument('--device', default=DEFAULT_DEVICE)
    create_parser.set_defaults(run=create)

    start_parser = commands.add_parser('start', help='start an AVD and wait for the boot to finish')
    start_parser.add_argument('name')
    start_parser.add_argument('--serial', default='emulator-5554')
    start_parser.add_argument('--timeout', type=int, default=300)
    start_parser.set_defaults(run=start)

    args = parser.parse_args()
    if args.command == 'image':
        print(system_image(args.api, args.abi))
        return
    args.run(args)


if __name__ == '__main__':
    ensure_utf8()
    main()
