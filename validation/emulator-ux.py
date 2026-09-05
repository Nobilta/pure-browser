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
    for _ in range(3):
        adb("shell", "rm", "-f", "/sdcard/pure-ux.xml")
        adb("shell", "uiautomator", "dump", "/sdcard/pure-ux.xml")
        try:
            raw = adb("shell", "cat", "/sdcard/pure-ux.xml")
            return ET.fromstring(raw), raw
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(1)
    raise AssertionError("UIAutomator did not produce a current hierarchy")


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
    menu_item("设置")


def menu_item(label):
    tap("菜单")
    for _ in range(8):
        root, _ = nodes()
        if match(root, label) is not None:
            tap(label)
            return
        swipe(root, downward=False)
    raise AssertionError("Menu row missing: " + label)


def swipe(root, downward):
    _, _, width, height = bounds(next(root.iter("node")))
    top, bottom = int(height * 0.3), int(height * 0.8)
    start, end = (top, bottom) if downward else (bottom, top)
    adb("shell", "input", "swipe", str(width // 2), str(start), str(width // 2), str(end), "450")
    time.sleep(0.6)


def expect(label, present=True):
    root, _ = nodes()
    assert (match(root, label) is not None) == present, (label, present)
    print("PASS:", label, "visible" if present else "absent", flush=True)


def regress():
    base = "http://127.0.0.1:8765/browser-ux.html"
    launch(base)
    expect("Pure UX First Page")
    tap("SPA route")
    expect("Pure UX SPA Updated")
    tap("编辑网址")
    expect(base + "?route=updated")
    adb("shell", "input", "keyevent", "4")
    time.sleep(0.4)
    adb("shell", "input", "keyevent", "4")
    time.sleep(0.4)
    root, _ = nodes()
    swipe(root, downward=False)
    expect("编辑网址", present=False)
    root, _ = nodes()
    swipe(root, downward=True)
    expect("编辑网址")
    inspect("regression-toolbar")
    open_settings()
    tap("主页")
    tap("导航首页")
    adb("shell", "input", "keyevent", "4")
    expect("启动时恢复上次网页")
    root, _ = nodes()
    if match(root, "下次启动：主页") is not None:
        tap("启动时恢复上次网页")
    tap("返回")
    launch(base)
    tap("Popup page")
    expect("Pure UX popup Page")
    adb("shell", "input", "keyevent", "3")
    time.sleep(1)
    adb("shell", "am", "force-stop", PACKAGE)
    launch()
    expect("Pure UX popup Page")
    inspect("regression-restored")
    open_settings()
    tap("启动时恢复上次网页")
    expect("下次启动：主页")
    tap("返回")
    menu_item("退出浏览器")
    time.sleep(0.5)
    assert PACKAGE + "/" not in adb("shell", "dumpsys", "activity", "recents")
    print("PASS: exit removes recent task", flush=True)
    launch()
    expect("常用网站")
    inspect("regression-home")


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
    parser.add_argument("action", choices=["inspect", "tap", "settings", "launch", "menu", "regress"])
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
    elif args.action == "menu":
        menu_item(args.value)
    elif args.action == "regress":
        regress()
