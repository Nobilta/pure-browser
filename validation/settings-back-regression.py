#!/usr/bin/env python3
"""Check one-level settings Back behavior on an installed signed APK."""
import argparse
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ux", ROOT / "emulator-ux.py")
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
# Edge-gesture round trips alternate direction, so four cycles still cover both directions
# twice; the historical six only repeated the same pair. Pass a larger value for a stress run.
parser.add_argument("--cycles", type=int, default=4)
args = parser.parse_args()
ux.ADB = ["adb", "-s", args.serial]
sdk = ux.adb("shell", "getprop", "ro.build.version.sdk")
case = "api" + sdk + "-settings-back-" + str(int(time.time()))
output = ROOT / "results"
output.mkdir(exist_ok=True)
apk = ux.adb("shell", "pm", "path", ux.PACKAGE).partition(":")[2].strip()
result = {"serial": args.serial, "sdk": sdk, "case": case,
          "apkSha256": ux.adb("shell", "sha256sum", apk).split()[0],
          "navigationMode": ux.adb("shell", "settings", "get", "secure", "navigation_mode"),
          "checks": [], "skipped": [], "error": None}
original = {name: ux.adb("shell", "settings", "get", "system", name)
            for name in ("accelerometer_rotation", "user_rotation", "font_scale")}
categories = ("浏览与启动", "外观", "隐私与过滤", "下载设置", "视频播放", "关于")


def record(name):
    result["checks"].append(name)
    print("PASS:", name, flush=True)


def screenshot(name):
    _, raw = ux.nodes()
    (output / (case + "-" + name + ".xml")).write_text(raw, encoding="utf-8")
    data = subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"], timeout=20)
    (output / (case + "-" + name + ".png")).write_bytes(data)


def back():
    ux.adb("shell", "input", "keyevent", "4")
    time.sleep(.6)


def toolbar_buttons(root):
    labels = ux.resource_labels("cd_back")
    return [node for node in root.iter("node") if ux.visible(node)
            and node.get("content-desc") in labels]


def toolbar_back(detail=True):
    # The toolbar animates in with the surface, so wait for the control on the device instead
    # of failing on the first tree read; the assert below still catches a missing Back.
    root, _ = ux.nodes(await_labels=list(ux.resource_labels("cd_back")), timeout_ms=4000)
    buttons = toolbar_buttons(root)
    assert buttons, "Settings toolbar Back is missing"
    top = min(ux.bounds(node)[1] for node in buttons)
    buttons = [node for node in buttons if ux.bounds(node)[1] <= top + 32]
    # The right-hand toolbar belongs to the detail pane in a two-pane layout.
    select = max if detail else min
    x1, y1, x2, y2 = ux.bounds(select(buttons, key=lambda node: ux.bounds(node)[0]))
    ux.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.5)


def settings_root():
    ux.expect("设置")
    root, _ = ux.nodes()
    assert any(ux.match(root, name) is not None for name in categories), "Category list is missing"
    assert len(toolbar_buttons(root)) == 1, "A detail pane remained open"
    assert ux.match(root, "默认启用增强播放") is None, "Detail page remained open"


def browser_root():
    root, _ = ux.nodes()
    assert not ux.menu_open(root), "Menu window remained open"
    assert ux.match(root, "编辑网址") is not None, "Browser toolbar is not visible"
    windows, _ = ux.window_nodes()
    assert not any(node.get("resource-id") in ("settings_root", "settings_detail")
                   for node in windows.iter("node")), "Retired settings content remains over the browser"
    count = len(re.findall(r"Window #\d+ Window\{[^}\n]*com\.mybrowser/com\.mybrowser\.MainActivity",
                           ux.adb("shell", "dumpsys", "window", "windows")))
    assert count == 1, "A retired dialog still owns browser input"
    activity = ux.adb("shell", "dumpsys", "activity", "activities")
    assert re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*com\.mybrowser/", activity), \
        "Back from the menu exited the browser"


def category(name):
    # A wide root can require scrolling, and returning can retain its scroll position.
    for downward in (True, False):
        for index in range(8):
            # Already-visible categories come back from one waited snapshot; only the
            # scrolled cases keep stepping through the list.
            root, _ = ux.nodes(await_labels=[name], timeout_ms=1200 if index == 0 else 0)
            if ux.match(root, name) is not None:
                ux.tap(name)
                return
            ux.swipe(root, downward=downward)
    raise AssertionError("Settings category missing: " + name)


def gesture_back(right=False):
    root, _ = ux.nodes()
    x1, y1, x2, y2 = ux.bounds(next(root.iter("node")))
    width = x2 - x1
    start = x2 - 1 if right else x1 + 1
    end = x2 - width // 3 if right else x1 + width // 3
    y = (y1 + y2) // 2
    ux.adb("shell", "input", "swipe", str(start), str(y), str(end), str(y), "350")
    time.sleep(.8)


def orient(value):
    ux.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    ux.adb("shell", "settings", "put", "system", "user_rotation", value)
    time.sleep(1.2)


try:
    orient("0")
    ux.adb("reverse", "tcp:8875", "tcp:8875")
    ux.adb("shell", "am", "force-stop", ux.PACKAGE)
    ux.launch("http://127.0.0.1:8875/browser-ux.html")
    ux.open_settings()
    for name in categories:
        category(name)
        back()
        settings_root()
        category(name)
        toolbar_back()
        settings_root()
    record("all six portrait categories return to settings with system and toolbar Back")

    category("浏览与启动")
    ux.tap("搜索引擎")
    toolbar_back()
    ux.expect("启动时恢复上次网页")
    ux.tap("主页")
    back()
    ux.expect("启动时恢复上次网页")
    back()
    settings_root()
    record("search and homepage pickers return to their browsing category")

    category("外观")
    ux.tap("应用主题")
    back()
    ux.expect("应用主题")
    back()
    settings_root()
    category("隐私与过滤")
    ux.tap("自定义广告过滤规则")
    ux.expect("广告过滤设置")
    back()
    ux.expect("自定义广告过滤规则")
    back()
    settings_root()
    record("theme picker and filter management preserve their parent category")

    category("视频播放")
    ux.adb("shell", "input", "keyevent", "3")
    ux.launch()
    ux.expect("默认启用增强播放")
    back()
    settings_root()
    record("returning from the background preserves the category Back handler")

    category("浏览与启动")
    ux.tap("搜索引擎")
    ux.adb("shell", "settings", "put", "system", "font_scale", "1.3")
    time.sleep(1.5)
    ux.expect("Google")
    back()
    ux.expect("启动时恢复上次网页")
    back()
    settings_root()
    screenshot("restored-root")
    ux.adb("shell", "settings", "put", "system", "font_scale", "1.0")
    time.sleep(1.5)
    record("Activity recreation retains picker and category Back navigation")

    if result["navigationMode"] == "2":
        category("浏览与启动")
        ux.tap("搜索引擎")
        gesture_back()
        ux.expect("启动时恢复上次网页")
        gesture_back(right=True)
        settings_root()
        gesture_back()
        ux.expect_menu()
        gesture_back(right=True)
        browser_root()
        ux.open_settings()
        settings_root()
        record("left and right edge gestures traverse picker, category, settings, menu and browser")
        for cycle in range(args.cycles):
            gesture_back(right=bool(cycle % 2))
            ux.expect_menu()
            gesture_back(right=not bool(cycle % 2))
            browser_root()
            ux.open_settings()
            settings_root()
            toolbar_back()
            ux.expect_menu()
            back()
            browser_root()
            ux.open_settings()
        record(f"{args.cycles} repeated edge and toolbar return cycles leave no retired settings window "
               f"or blocked close button")
    else:
        result["skipped"].append("edge gestures: device does not use gesture navigation")

    category("视频播放")
    orient("1")
    ux.expect("默认启用增强播放")
    screenshot("landscape-detail")
    toolbar_back()
    settings_root()
    screenshot("landscape-root")
    category("下载设置")
    back()
    settings_root()
    category("外观")
    toolbar_back(detail=False)
    settings_root()
    record("landscape system and both toolbar Back buttons return to the root")

    category("视频播放")
    orient("0")
    ux.expect("默认启用增强播放")
    back()
    settings_root()
    back()
    ux.expect_menu()
    ux.expect("默认启用增强播放", present=False)
    back()
    browser_root()
    ux.open_settings()
    settings_root()
    toolbar_back()
    ux.expect_menu()
    back()
    browser_root()
    record("rotation retains the complete settings → menu → browser return path")
except Exception as error:
    result["error"] = str(error)
    screenshot("failure")
    raise
finally:
    for name, value in original.items():
        if value == "null":
            ux.adb("shell", "settings", "delete", "system", name)
        else:
            ux.adb("shell", "settings", "put", "system", name, value)
    (output / (case + ".json")).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
