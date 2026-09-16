#!/usr/bin/env python3
"""Verify categorized settings on the signed app using the shell UI helper."""
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
output = ROOT / "results"
output.mkdir(exist_ok=True)
checks = []


def record(name):
    checks.append(name)
    print("PASS:", name, flush=True)


def screenshot(name):
    _, raw = ux.nodes()
    (output / ("api" + sdk + "-settings-" + name + ".xml")).write_text(raw, encoding="utf-8")
    data = subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"], timeout=20)
    (output / ("api" + sdk + "-settings-" + name + ".png")).write_bytes(data)


def back():
    ux.adb("shell", "input", "keyevent", "4")
    time.sleep(.6)


def restart():
    ux.adb("shell", "am", "force-stop", ux.PACKAGE)
    ux.launch("http://127.0.0.1:8875/browser-ux.html")


try:
    ux.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    ux.adb("shell", "settings", "put", "system", "user_rotation", "0")
    ux.adb("reverse", "tcp:8875", "tcp:8875")
    restart()
    ux.open_settings()
    for name in ("浏览与启动", "外观", "隐私与过滤", "下载设置", "视频播放", "关于"):
        ux.expect(name)
    screenshot("root")
    record("six root categories are visible")
    ux.tap("浏览与启动")
    ux.tap("搜索引擎")
    ux.tap("Google")
    ux.expect("启动时恢复上次网页")
    ux.expect("Google")
    record("search selection returns to the browsing category")
    back()
    ux.tap("外观")
    ux.tap("应用主题")
    ux.tap("深色")
    ux.expect("应用主题")
    screenshot("dark")
    restart()
    ux.open_settings("外观")
    ux.expect("深色")
    record("theme selection survives a process restart")
    ux.tap("应用主题")
    ux.tap("浅色")
    screenshot("light")
    ux.tap("应用主题")
    ux.tap("跟随系统")
    back()
    ux.tap("隐私与过滤")
    ux.tap("自定义广告过滤规则")
    ux.expect("广告过滤设置")
    back()
    ux.expect("隐私与过滤")
    ux.expect("自定义广告过滤规则")
    record("filter management returns to the privacy category")
    ux.tap("自定义广告过滤规则")
    ux.launch("http://127.0.0.1:8875/browser-ux.html")
    ux.expect("广告过滤设置", present=False)
    record("an external navigation dismisses filter management")
    ux.open_settings("下载设置")
    root, _ = ux.nodes()
    slider = next(n for n in root.iter("node") if n.get("class") == "android.widget.SeekBar")
    x1, y1, x2, y2 = ux.bounds(slider)
    ux.adb("shell", "input", "tap", str(int(x1 + (x2 - x1) * .06)), str((y1 + y2) // 2))
    time.sleep(.6)
    ux.expect("1 线程")
    ux.adb("shell", "input", "tap", str(int(x1 + (x2 - x1) * .94)), str((y1 + y2) // 2))
    time.sleep(.6)
    ux.expect("16 线程")
    restart()
    ux.open_settings("下载设置")
    ux.expect("16 线程")
    record("download connection count survives a process restart")
    screenshot("downloads")
    back()
    ux.tap("视频播放")
    ux.expect("默认启用增强播放")
    screenshot("video")
    ux.adb("shell", "settings", "put", "system", "font_scale", "1.3")
    time.sleep(1)
    ux.expect("默认启用增强播放")
    screenshot("large-font")
    record("video settings remain accessible at 1.3 font scale")
    ux.adb("shell", "settings", "put", "system", "font_scale", "1.0")
    time.sleep(1)
    ux.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    ux.adb("shell", "settings", "put", "system", "user_rotation", "1")
    time.sleep(1.5)
    screenshot("landscape")
    if int(sdk) >= 34:
        ux.expect("浏览与启动")
        ux.expect("默认启用增强播放")
        record("wide settings show category navigation beside the detail pane")
finally:
    ux.adb("shell", "settings", "put", "system", "font_scale", "1.0")
    ux.adb("shell", "settings", "put", "system", "user_rotation", "0")
    ux.adb("shell", "settings", "put", "system", "accelerometer_rotation", "1")
    (output / ("api" + sdk + "-settings.json")).write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
