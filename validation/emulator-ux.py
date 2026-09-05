#!/usr/bin/env python3
"""ADB/UIAutomator helpers for repeatable signed-APK regression on a local emulator."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
PACKAGE = "com.mybrowser"
ADB = ["adb"] + (["-s", os.environ["ANDROID_SERIAL"]] if os.environ.get("ANDROID_SERIAL") else [])


def adb(*args):
    return subprocess.check_output(ADB + list(args), text=True, timeout=30).strip()


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/pure-ux.xml")
    raw = adb("shell", "cat", "/sdcard/pure-ux.xml")
    return ET.fromstring(raw), raw


def bounds(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def visible(node):
    rect = bounds(node)
    return len(rect) == 4 and rect[2] > rect[0] and rect[3] > rect[1]


def match(root, label):
    return next((n for n in root.iter("node") if visible(n) and label in (
        n.get("text"), n.get("content-desc"), n.get("resource-id"))), None)


def tap(label):
    root, _ = nodes()
    node = match(root, label)
    if node is None:
        raise AssertionError("Visible control missing: " + label)
    x1, y1, x2, y2 = bounds(node)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.5)


def inspect(name=None):
    root, raw = nodes()
    if name:
        (ROOT / (name + ".xml")).write_text(raw, encoding="utf-8")
        adb("shell", "screencap", "-p", "/sdcard/pure-ux.png")
        adb("pull", "/sdcard/pure-ux.png", str(ROOT / (name + ".png")))
    entries = [{k: n.get(k) for k in ("text", "content-desc", "class", "checked", "bounds")}
               for n in root.iter("node") if visible(n) and
               (n.get("text") or n.get("content-desc") or n.get("class") == "android.widget.EditText")]
    print(json.dumps(entries, ensure_ascii=False, indent=2))


def open_settings():
    tap("菜单")
    for _ in range(8):
        root, _ = nodes()
        if match(root, "设置") is not None:
            tap("设置")
            return
        adb("shell", "input", "swipe", "520", "1900", "520", "650", "350")
    raise AssertionError("Settings row missing")


def launch(url=None):
    args = ["shell", "am", "start", "-W", "-n", PACKAGE + "/com.mybrowser.MainActivity"]
    if url:
        args += ["-a", "android.intent.action.VIEW", "-d", url]
    else:
        args += ["-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"]
    print(adb(*args))
    time.sleep(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["inspect", "tap", "settings", "launch"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    if args.action == "inspect":
        inspect(args.value)
    elif args.action == "tap":
        tap(args.value)
    elif args.action == "settings":
        open_settings()
    elif args.action == "launch":
        launch(args.value)
