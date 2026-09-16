#!/usr/bin/env python3
"""Verify app language updates and settings navigation across Activity recreation."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ux", ROOT / "emulator-ux.py")
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
args = parser.parse_args()
ux.ADB = ["adb", "-s", args.serial]
sdk = ux.adb("shell", "getprop", "ro.build.version.sdk")
assert int(sdk) >= 33, "App language overrides require Android 13 or later"
output = ROOT / "results"
output.mkdir(exist_ok=True)
checks = []

ux.adb("reverse", "tcp:8875", "tcp:8875")
ux.adb("shell", "am", "force-stop", ux.PACKAGE)
ux.launch("http://127.0.0.1:8875/browser-ux.html")
ux.open_settings(ux.resource_strings("values")["ui_video_playback"])
try:
    for locale, folder in (("zh-Hans", "values-zh"), ("zh-Hant", "values-b+zh+Hant"), ("en", "values")):
        ux.adb("shell", "cmd", "locale", "set-app-locales", ux.PACKAGE, "--user", "0", "--locales", locale)
        expected = ux.resource_strings(folder)
        deadline = time.monotonic() + 6
        while True:
            root, raw = ux.nodes()
            text = {node.get("text") for node in root.iter("node") if ux.visible(node)}
            if all(expected[key] in text for key in ("ui_video_playback", "ui_enhanced_video_controls")):
                break
            if time.monotonic() >= deadline:
                raise AssertionError("Settings category or translated strings missing for " + locale)
            time.sleep(.2)
        stem = output / ("api" + sdk + "-locale-" + locale)
        stem.with_suffix(".xml").write_text(raw, encoding="utf-8")
        stem.with_suffix(".png").write_bytes(subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"], timeout=20))
        checks.append(locale)
        print("PASS:", locale, "updates visible strings and retains the video settings category", flush=True)
finally:
    ux.adb("shell", "cmd", "locale", "set-app-locales", ux.PACKAGE, "--user", "0")
    (output / ("api" + sdk + "-locales.json")).write_text(json.dumps(checks, indent=2), encoding="utf-8")
