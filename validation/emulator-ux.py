#!/usr/bin/env python3
"""ADB/UIAutomator helpers for repeatable signed-APK regression on a local emulator."""
import argparse
from functools import lru_cache
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
UI_PROBE = "/data/local/tmp/pure-ui-dump.jar"
_probe_available = {}


def resource_strings(folder):
    resources = ROOT.parent / "app/src/main/res" / folder / "strings.xml"
    values = {}
    for item in ET.parse(resources).getroot().findall("string"):
        value = item.text or ""
        if value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        values[item.get("name")] = value.replace(r"\n", "\n").replace(r"\'", "'").replace(r'\"', '"')
    return values


_translations = [resource_strings(folder) for folder in ("values", "values-zh", "values-b+zh+Hant")]
_label_variants = {}
_formatted_labels = []
for key in _translations[0]:
    variants = {values[key] for values in _translations}
    for value in variants:
        _label_variants.setdefault(value, set()).update(variants)
        arguments = re.findall(r"%(\d+)\$[sdif]", value)
        if arguments:
            pattern = "(.+?)".join(re.escape(part.replace("%%", "%")) for part in re.split(r"%\d+\$[sdif]", value))
            _formatted_labels.append((re.compile(pattern), arguments, variants))


@lru_cache(maxsize=512)
def labels(label):
    variants = set(_label_variants.get(label, {label}))
    for pattern, arguments, templates in _formatted_labels:
        found = pattern.fullmatch(label)
        if found:
            values = dict(zip(arguments, found.groups()))
            variants.update(re.sub(r"%(\d+)\$[sdif]", lambda m: values[m[1]], template).replace("%%", "%")
                            for template in templates)
    return variants


def adb(*args):
    return subprocess.check_output(ADB + list(args), text=True, timeout=30).strip()


def nodes():
    device = tuple(ADB)
    if device not in _probe_available:
        _probe_available[device] = subprocess.run(
            ADB + ["shell", "test", "-r", UI_PROBE], stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=10).returncode == 0
    if _probe_available[device]:
        for _ in range(2):
            try:
                raw = adb("shell", "env", "CLASSPATH=" + UI_PROBE, "app_process", "/system/bin",
                          "com.mybrowser.validation.FastUiDump")
                raw = raw[raw.index("<?xml"):raw.index("</hierarchy>") + len("</hierarchy>")]
                return ET.fromstring(raw), raw
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError, ValueError):
                time.sleep(.15)
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
    return list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))


def visible(node):
    rect = bounds(node)
    return len(rect) == 4 and rect[2] > rect[0] and rect[3] > rect[1]


def match(root, label):
    variants = labels(label)
    return next((n for n in root.iter("node") if visible(n) and variants.intersection((
        n.get("text"), n.get("content-desc"), n.get("resource-id")))), None)


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


def open_settings(category=None):
    menu_item("设置")
    if category:
        tap(category)


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
    adb("reverse", "tcp:8875", "tcp:8875")
    base = "http://127.0.0.1:8875/browser-ux.html"
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
    open_settings("浏览与启动")
    tap("主页")
    tap("导航首页")
    adb("shell", "input", "keyevent", "4")
    expect("启动时恢复上次网页")
    root, _ = nodes()
    if match(root, "下次启动：主页") is not None:
        tap("启动时恢复上次网页")
    tap("返回")
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
    open_settings("浏览与启动")
    tap("启动时恢复上次网页")
    expect("下次启动：主页")
    tap("返回")
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
