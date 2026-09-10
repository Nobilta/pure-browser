#!/usr/bin/env python3
"""ADB/UIAutomator helpers for repeatable signed-APK regression on a local emulator."""
import argparse
import base64
from functools import lru_cache
import json
import os
from pathlib import Path
import re
import shlex
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
    command = ["shell", shlex.join(args[1:])] if args and args[0] == "shell" else list(args)
    return subprocess.check_output(ADB + command, text=True, timeout=30).strip()


def nodes():
    device = tuple(ADB)
    if device not in _probe_available:
        _probe_available[device] = subprocess.run(
            ADB + ["shell", "test", "-r", UI_PROBE], stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=10).returncode == 0
    if _probe_available[device]:
        for _ in range(2):
            try:
                raw = adb("shell", "env", "CLASSPATH=" + UI_PROBE, "app_process", "-Xusejit:false", "/system/bin",
                          "com.mybrowser.validation.FastUiDump")
                raw = raw[raw.index("<?xml"):raw.index("</hierarchy>") + len("</hierarchy>")]
                return ET.fromstring(raw), raw
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError, ValueError):
                time.sleep(.15)
        # A new ART/WebView build can reject this optional helper. Use the system
        # dumper for the rest of this run instead of repeatedly starting a killed VM.
        _probe_available[device] = False
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
    return node.get("visible-to-user") != "false" and len(rect) == 4 and rect[2] > rect[0] and rect[3] > rect[1]


def tap_now(label):
    """Resolve a current visible node and inject a real tap in one helper session."""
    if _probe_available.get(tuple(ADB)) is False:
        return False
    variants = labels(label) | {value.upper() for value in labels(label)}
    encoded = base64.b64encode(json.dumps(sorted(variants)).encode()).decode()
    command = ["env", "CLASSPATH=" + UI_PROBE, "app_process", "-Xusejit:false", "/system/bin",
               "com.mybrowser.validation.FastUiDump", "tap", encoded]
    result = subprocess.run(ADB + ["shell", shlex.join(command)], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    # Some old ART versions can fail during helper shutdown after a successful tap.
    return "Tapped" in result.stdout


def match(root, label):
    variants = labels(label)
    variants = variants | {value.upper() for value in variants}
    return next((n for n in root.iter("node") if visible(n) and variants.intersection((
        n.get("text"), n.get("content-desc"), n.get("resource-id")))), None)


def tap(label, timeout=4):
    deadline = time.monotonic() + timeout
    while True:
        root, _ = nodes()
        node = match(root, label)
        if node is not None:
            break
        if time.monotonic() >= deadline:
            raise AssertionError("Visible control missing: " + label)
        time.sleep(.15)
    tap_node(node)


def tap_node(node):
    x1, y1, x2, y2 = bounds(node)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.5)


def open_downloads_directory():
    """Navigate within the SAF drawer, ignoring same-named controls behind it."""
    deadline = time.monotonic() + 10
    while True:
        root, raw = nodes()
        if 'documentsui' in raw:
            break
        assert time.monotonic() < deadline, 'System document picker did not open'
        time.sleep(.2)
    drawer = next((n for n in root.iter('node') if visible(n) and n.get('content-desc') in
                   ('Show roots', '显示根目录', '顯示根目錄', 'Open navigation drawer')), None)
    assert drawer is not None, 'Document picker navigation is unavailable'
    tap_node(drawer)
    root, _ = nodes()
    roots = next((n for n in root.iter('node') if n.get('resource-id', '').endswith('/roots_list')), root)
    matches = [n for n in roots.iter('node') if visible(n) and n.get('text') in labels('Downloads')]
    assert matches, 'Downloads folder is unavailable'
    tap_node(matches[-1])


def stable_display_bounds(root):
    display = adb('shell', 'dumpsys', 'window', 'displays')
    stable = re.search(r'\bmStable=(\[\d+,\d+\]\[\d+,\d+\])', display)
    if stable:
        return tuple(map(int, re.findall(r'\d+', stable[1])))
    # Newer WindowManager exposes inset sources. App/root bounds can extend
    # beneath bars, so subtract visible edge obstructions from the whole display.
    size = re.search(r'\bcur=(\d+)x(\d+)', display)
    sources = re.findall(r'InsetsSource[^\n]*\btype=(?:statusBars|navigationBars|displayCutout)\b'
                         r'[^\n]*\bframe=\[(\d+),(\d+)\]\[(\d+),(\d+)\][^\n]*\bvisible=true\b', display)
    if size and sources:
        width, height = map(int, size.groups())
        safe = [0, 0, width, height]
        for source in sources:
            x1, y1, x2, y2 = map(int, source)
            if x1 == 0 and x2 == width:
                if y1 == 0 and 0 < y2 < height:
                    safe[1] = max(safe[1], y2)
                elif y2 == height and 0 < y1 < height:
                    safe[3] = min(safe[3], y1)
            if y1 == 0 and y2 == height:
                if x1 == 0 and 0 < x2 < width:
                    safe[0] = max(safe[0], x2)
                elif x2 == width and 0 < x1 < width:
                    safe[2] = min(safe[2], x1)
        return tuple(safe)
    app_bounds = re.search(r'\bmAppBounds=Rect\((\d+), (\d+) - (\d+), (\d+)\)', display)
    return tuple(map(int, app_bounds.groups())) if app_bounds else bounds(next(root.iter('node')))


def choose_download_document(name):
    """Select a fixture through SAF even when Recent omits it or Downloads spans pages."""
    open_downloads_directory()
    root, _ = nodes()
    list_view = next((n for n in root.iter('node') if visible(n) and n.get('content-desc') in
                      ('List view', '列表视图', '清單檢視')), None)
    if list_view is not None:
        tap_node(list_view)
    # Old DocumentsUI reports clipped rows behind the three-button navigation bar
    # as visible. A tap there presses Android Back and cancels the picker.
    viewport = stable_display_bounds(root)

    def in_viewport(node):
        x1, y1, x2, y2 = bounds(node)
        return viewport[0] < (x1+x2)//2 < viewport[2] and viewport[1] < (y1+y2)//2 < viewport[3]

    for _ in range(20):
        root, _ = nodes()
        target = next((n for n in root.iter('node') if visible(n) and in_viewport(n) and
                       (n.get('text') == name or n.get('content-desc', '').split(', ')[0] == name)), None)
        if target is not None:
            tap_node(target)
            return
        regions = [n for n in root.iter('node') if n.get('scrollable') == 'true' and visible(n)]
        assert regions, 'No document list for ' + name
        region = max(regions, key=lambda n: bounds(n)[3] - bounds(n)[1])
        x1, y1, x2, y2 = bounds(region)
        adb('shell', 'input', 'swipe', str((x1+x2)//2), str(y1+(y2-y1)*4//5),
            str((x1+x2)//2), str(y1+(y2-y1)//5), '350')
        time.sleep(.3)
    raise AssertionError('Document not found in Downloads: ' + name)


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


def menu_open(root):
    """Distinguish the actual sheet (including a scrolled sheet) from the toolbar button."""
    if match(root, 'browser_menu') is not None:
        return True
    texts = {node.get('text') for node in root.iter('node') if visible(node)}
    return bool(texts.intersection(labels('浏览器菜单'))) or all(
        texts.intersection(labels(label)) for label in ('设置', '开发者工具', '退出浏览器'))


def expect_menu():
    deadline = time.monotonic() + 4
    while True:
        root, _ = nodes()
        if menu_open(root):
            return root
        assert time.monotonic() < deadline, 'Browser menu sheet is missing (a toolbar Menu button is not a menu)'
        time.sleep(.15)


def close_menu():
    root, _ = nodes()
    if menu_open(root):
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(.6)
        root, _ = nodes()
        assert not menu_open(root), 'Browser menu did not close'
        assert match(root, '编辑网址') is not None, 'Closing the menu did not return to the browser'


def menu_item(label):
    root, _ = nodes()
    if not menu_open(root):
        tap("菜单")
    # Returning from a child keeps the menu's previous scroll position.
    for downward in (False, True):
        for _ in range(8):
            root, _ = nodes()
            if match(root, label) is not None:
                tap(label)
                return
            swipe(root, downward=downward)
    raise AssertionError("Menu row missing: " + label)


def swipe(root, downward):
    _, _, width, height = bounds(next(root.iter("node")))
    top, bottom = int(height * 0.3), int(height * 0.8)
    start, end = (top, bottom) if downward else (bottom, top)
    adb("shell", "input", "swipe", str(width // 2), str(start), str(width // 2), str(end), "450")
    time.sleep(0.6)


def expect(label, present=True):
    deadline = time.monotonic() + 4
    while True:
        root, _ = nodes()
        if (match(root, label) is not None) == present:
            break
        assert time.monotonic() < deadline, (label, present)
        time.sleep(.15)
    print("PASS:", label, "visible" if present else "absent", flush=True)


def regress():
    adb("reverse", "tcp:8875", "tcp:8875")
    evidence = "results/api" + adb("shell", "getprop", "ro.build.version.sdk") + "-browser-"
    base = "http://127.0.0.1:8875/browser-ux.html"
    # Start this stage with a fresh renderer. Older WebView accessibility trees can
    # retain missing header nodes after the preceding stage rotates its diagnostics UI.
    adb("shell", "am", "force-stop", PACKAGE)
    launch(base)
    nodes()  # Enable WebView accessibility before the fixture reload and style injection.
    tap("刷新")
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
    inspect(evidence + "toolbar")
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
    nodes()
    tap("刷新")
    tap("Popup page")
    expect("Pure UX popup Page")
    adb("shell", "input", "keyevent", "3")
    time.sleep(1)
    adb("shell", "am", "force-stop", PACKAGE)
    launch()
    expect("Pure UX popup Page")
    inspect(evidence + "restored")
    open_settings("浏览与启动")
    tap("启动时恢复上次网页")
    expect("下次启动：主页")
    tap("返回")
    tap("返回")
    # Reproduce a task whose surviving root is a system picker after process death.
    menu_item("书签")
    tap("导入或导出书签")
    tap("导入书签")
    assert "documentsui" in nodes()[1], "Bookmark file picker did not open"
    adb("shell", "am", "force-stop", PACKAGE)
    launch()
    activities = adb("shell", "dumpsys", "activity", "activities")
    current_task = re.search(r'(?:topResumedActivity|mResumedActivity)[^\n]*'
                             + re.escape(PACKAGE) + r'/[^\s]+ t(\d+)', activities)
    assert current_task is not None, "Browser is not the resumed activity"
    task_pattern = r'Task(?:Record)?\{[^}\n]* #' + current_task[1] + r'\b'
    assert re.search(task_pattern, adb("shell", "dumpsys", "activity", "recents")), \
        "Current browser task was not present before exit"
    menu_item("退出浏览器")
    deadline = time.monotonic() + 5
    while re.search(task_pattern, adb("shell", "dumpsys", "activity", "recents")):
        assert time.monotonic() < deadline, "Exit did not remove the current browser task"
        time.sleep(.25)
    print("PASS: exit removes the browser task and its orphaned system picker after process death", flush=True)
    launch()
    expect("常用网站")
    inspect(evidence + "home")


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
