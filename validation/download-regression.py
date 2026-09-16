#!/usr/bin/env python3
"""Verify a real ranged download, MediaStore output, and the default-browser entry."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ux", ROOT / "emulator-ux.py")
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
args = parser.parse_args()
ux.ADB = ["adb", "-s", args.serial]
sdk = ux.adb("shell", "getprop", "ro.build.version.sdk")


def files():
    return set(ux.adb("shell", "sh", "-c", "ls /sdcard/Download/pure-range-test*.bin 2>/dev/null || true").splitlines())


ux.adb("reverse", "tcp:8875", "tcp:8875")
ux.adb("shell", "am", "force-stop", ux.PACKAGE)
ux.launch("http://127.0.0.1:8875/download-fixture.html")
before = files()
root, _ = ux.nodes()
web = next(n for n in root.iter("node") if n.get("class") == "android.webkit.WebView")
x1, y1, x2, y2 = ux.bounds(web)
started = time.time()
ux.adb("shell", "input", "tap", str((x1 + x2) // 2), str(y1 + 95))
service_during = ux.adb("shell", "dumpsys", "activity", "services", ux.PACKAGE)
expected = hashlib.sha256(bytes(range(256)) * (6 * 1024 * 1024 // 256)).hexdigest()
deadline = time.monotonic() + 25
verified = None
while time.monotonic() < deadline:
    for path in files() - before:
        actual = ux.adb("shell", "sha256sum", path).split()[0]
        if actual == expected:
            verified = path
            break
    if verified:
        break
    time.sleep(.3)
assert verified, "MediaStore download did not produce the expected file"
time.sleep(1)
service_after = ux.adb("shell", "dumpsys", "activity", "services", ux.PACKAGE)
assert "isForeground=true" not in service_after
requests = json.load(urllib.request.urlopen("http://127.0.0.1:8875/__state?case=download-requests"))
ranges = [item for item in requests if item["receivedAt"] >= started and item["start"] > 0]
assert ranges, "No parallel byte-range requests observed"
ux.menu_item("下载")
ux.inspect("results/api" + sdk + "-downloads")
print("PASS: range download matches SHA-256 and the foreground service stops", flush=True)
ux.adb("shell", "input", "keyevent", "4")
ux.expect_menu()  # Wait for the download sheet's return animation before selecting Settings.
ux.open_settings("浏览与启动")
ux.tap("默认浏览器")
deadline = time.monotonic() + 10
while True:
    root, raw = ux.nodes()
    if "com.android.permissioncontroller" in raw or "com.android.settings" in raw:
        break
    assert time.monotonic() < deadline, "Android did not show its default-browser role/settings UI"
    time.sleep(.2)
ux.inspect("results/api" + sdk + "-default-browser")
print("PASS: default-browser setting opens the system role/settings UI", flush=True)
ux.adb("shell", "input", "keyevent", "4")
result = {"sdk": sdk, "file": verified, "sha256": expected, "rangeRequests": ranges,
          "foregroundServiceObserved": "isForeground=true" in service_during,
          "foregroundServiceStopped": True, "defaultBrowserUI": True}
(ROOT / "results" / ("api" + sdk + "-download.json")).write_text(json.dumps(result, indent=2), encoding="utf-8")
