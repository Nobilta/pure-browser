"""Shared Android SDK lookups for the host-side validation harness.

Every host platform installs the same tools under different file names and directory layouts:
Windows adds .exe/.bat suffixes, and the command-line tools live in a versioned directory. The
callers only want "the newest d8" or "adb", so the search order lives here once.
"""
import os
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEARCH_DIRS = ('platform-tools', 'emulator', 'build-tools/*', 'cmdline-tools/*/bin', 'tools/bin')
SUFFIXES = ('', '.exe', '.bat')


def unescape_property(value):
    """local.properties is a Java properties file: a Windows path arrives as C\\:\\\\Users\\\\me."""
    return re.sub(r'\\(.)', r'\1', value)


def sdk_root():
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or ''
    properties = ROOT / 'local.properties'
    if properties.is_file():
        for line in properties.read_text(encoding='utf-8').splitlines():
            if line.startswith('sdk.dir='):
                configured = unescape_property(line.partition('=')[2].strip())
                if Path(configured).is_dir():
                    sdk = configured
    if not sdk or not Path(sdk).is_dir():
        raise SystemExit('Android SDK not found: set sdk.dir in local.properties or ANDROID_SDK_ROOT')
    return Path(sdk)


def find_tool(name):
    """Return the newest matching SDK executable, or a PATH entry, or None."""
    for pattern in SEARCH_DIRS:
        for directory in sorted(sdk_root().glob(pattern), reverse=True):
            for suffix in SUFFIXES:
                candidate = directory / (name + suffix)
                if candidate.is_file():
                    return candidate
    found = shutil.which(name)
    return Path(found) if found else None


def newest(directory_pattern, file_name):
    version = lambda path: tuple(map(int, re.findall(r'\d+', path.parent.name)))
    matches = []
    for suffix in SUFFIXES:
        matches.extend(sdk_root().glob(f'{directory_pattern}/{file_name}{suffix}'))
    return sorted(matches, key=version)
