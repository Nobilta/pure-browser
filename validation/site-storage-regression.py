#!/usr/bin/env python3
"""Corrupt site settings, exercise the repair UI, then restore the emulator's original prefs."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ux', ROOT / 'emulator-ux.py')
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator: this stage rewrites prefs'
    ux.ADB = ['adb', '-s', args.serial]
    output = ROOT / 'results' / 'site-storage'
    output.mkdir(parents=True, exist_ok=True)
    prefs = '/data/data/com.mybrowser/shared_prefs/site_settings.xml'
    checks = []
    original = None
    captured = False
    report = {'checks': checks, 'passed': False}

    def record(message):
        checks.append(message)
        print('PASS:', message, flush=True)

    def stop():
        ux.adb('shell', 'am', 'force-stop', ux.PACKAGE)

    def write_prefs(data):
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / 'site_settings.xml'
            local.write_bytes(data)
            remote = '/data/local/tmp/pure-site-settings.xml'
            ux.adb('push', str(local), remote)
            owner = ux.adb('shell', 'stat', '-c', '%u:%g', '/data/data/com.mybrowser')
            ux.adb('shell', 'cp', remote, prefs)
            ux.adb('shell', 'chown', owner, prefs)
            ux.adb('shell', 'chmod', '600', prefs)
            ux.adb('shell', 'restorecon', prefs)
            ux.adb('shell', 'rm', '-f', remote)

    def stored():
        raw = subprocess.check_output(ux.ADB + ['exec-out', 'cat', prefs], timeout=30)
        return ET.fromstring(raw).find("string[@name='sites']").text

    def open_site():
        ux.launch('http://127.0.0.1:8875/browser-ux.html')
        ux.expect('Pure UX First Page', timeout=15)
        ux.menu_item('Website settings')

    def control(root, label, attribute='clickable'):
        # Compose keeps the label in an enabled TextView even when its button or
        # toggle ancestor is disabled. Read state from the interactive owner.
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter('node'):
            if node.get('text') not in ux.labels(label) or not ux.visible(node):
                continue
            while node is not None:
                if node.get(attribute) == 'true':
                    return node
                node = parents.get(node)
        raise AssertionError('Interactive control missing: ' + label)

    try:
        subprocess.run(ux.ADB + ['root'], check=True, timeout=30, stdout=subprocess.DEVNULL)
        subprocess.run(ux.ADB + ['wait-for-device'], check=True, timeout=30)
        assert 'uid=0' in ux.adb('shell', 'id'), 'This stage requires adb root'
        ux.adb('reverse', 'tcp:8875', 'tcp:8875')
        stop()
        snapshot = subprocess.run(ux.ADB + ['exec-out', 'cat', prefs], capture_output=True, timeout=30)
        if snapshot.returncode == 0:
            original = snapshot.stdout
        captured = True
        corrupt = b'<map><string name="sites">{not json</string></map>'
        write_prefs(corrupt)
        open_site()
        ux.expect('Website settings could not be read. Saving is paused until you reset them.')
        root, raw = ux.nodes()
        save = control(root, 'Save and reload')
        (output / 'unreadable.xml').write_text(raw, encoding='utf-8')
        assert save.get('enabled') == 'false', 'Save must be disabled'
        record('Unreadable settings show a repair notice and disable saving')
        ux.tap('Reset all website settings')
        ux.tap('Cancel')
        assert stored() == '{not json', 'Cancelling repair must preserve the original bytes'
        record('Cancelling repair preserves the unreadable settings')
        ux.tap('Reset all website settings')
        root, _ = ux.nodes()
        ux.tap_node(control(root, 'Reset all website settings'))
        ux.expect('Website settings could not be read. Saving is paused until you reset them.', present=False)
        assert json.loads(stored()) == {}, 'Repair must commit an empty valid store'
        root, _ = ux.nodes()
        assert control(root, 'Save and reload').get('enabled') == 'true'
        ux.tap('JavaScript')
        ux.tap('Save and reload')
        assert json.loads(stored())['http://127.0.0.1:8875']['javascript'] is False
        stop()
        open_site()
        root, raw = ux.nodes()
        assert control(root, 'JavaScript', 'checkable').get('checked') == 'false', 'Saved setting did not survive restart'
        (output / 'restored.xml').write_text(raw, encoding='utf-8')
        record('Confirmed repair restores saving and JavaScript preference survives a process restart')
        report['passed'] = True
    finally:
        if captured:
            stop()
            if original is not None:
                write_prefs(original)
            else:
                ux.adb('shell', 'rm', '-f', prefs)
            ux.launch()
        (output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
