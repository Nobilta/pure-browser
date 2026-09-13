#!/usr/bin/env python3
"""Signed APK regression for user scripts, subscription updates, dialogs and cast entry.

Run qa-server.py first. Uses real browser UI and local fixture telemetry, never
release-only test hooks. The named Pure QA list/scripts are test data only.
"""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ux", ROOT / "emulator-ux.py")
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


class Regression:
    def __init__(self, serial, section):
        ux.ADB = ["adb", "-s", serial]
        self.serial, self.section = serial, section
        self.sdk = ux.adb("shell", "getprop", "ro.build.version.sdk")
        self.case = "api" + self.sdk + "-features-" + str(int(time.time()))
        self.base = "http://127.0.0.1:8875/"
        self.since = 0
        self.checks = []
        self.output = ROOT / "results"
        self.output.mkdir(exist_ok=True)
        apk = ux.adb("shell", "pm", "path", ux.PACKAGE).partition(":")[2].strip()
        self.apk_hash = ux.adb("shell", "sha256sum", apk).split()[0]

    def record(self, message, **details):
        self.checks.append({"check": message, **details})
        print("PASS:", message, json.dumps(details, ensure_ascii=False), flush=True)

    def snapshot(self, name):
        stem = self.output / ("api" + self.sdk + "-features-" + name)
        stem.with_suffix(".xml").write_text(ux.nodes()[1])
        stem.with_suffix(".png").write_bytes(subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"]))

    def page(self, extra=""):
        self.since = time.time()
        ux.launch(self.base + "feature-fixture.html?case=" + self.case + "&visit=" + str(time.time_ns()) + extra)

    def wait(self, predicate, timeout=15):
        deadline = time.monotonic() + timeout
        latest = None
        while time.monotonic() < deadline:
            events = json.load(urllib.request.urlopen(self.base + "__state?case=" + self.case, timeout=5))
            latest = events[-1] if events else None
            if latest and latest["receivedAt"] > self.since and predicate(latest):
                return latest
            time.sleep(.3)
        raise AssertionError("Feature condition failed: " + json.dumps(latest))

    def back(self):
        ux.adb("shell", "input", "keyevent", "4")
        time.sleep(.6)

    def click(self, node):
        x1, y1, x2, y2 = ux.bounds(node)
        ux.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.5)

    def card(self, label, action=None):
        for forward in (True, False):
            previous = None
            unchanged = 0
            for _ in range(10):
                root, _ = ux.nodes()
                containers = [n for n in root.iter("node") if any(c.get("text") in ux.labels(label) for c in n.iter("node"))
                              and any(c.get("checkable") == "true" for c in n.iter("node"))
                              and (action is None or ux.match(n, action) is not None)]
                if containers:
                    return min(containers, key=lambda n: len(list(n.iter("node"))))
                visible = [(n.get("text"), n.get("bounds")) for n in root.iter("node") if ux.visible(n) and n.get("text")]
                regions = [n for n in root.iter("node") if n.get("scrollable") == "true" and ux.visible(n)]
                if not regions:
                    # A dismissing dialog can still own the active window. Do not
                    # interpret that unchanged hierarchy as the end of the list.
                    previous = None
                    unchanged = 0
                    time.sleep(.3)
                    continue
                unchanged = unchanged + 1 if visible == previous else 0
                if unchanged >= 2:
                    break
                previous = visible
                # Swipe inside the actual list; sheet/window bounds can include a
                # header or an IME transition and are not reliable gesture targets.
                region = max(regions, key=lambda n: ux.bounds(n)[3] - ux.bounds(n)[1])
                x1, y1, x2, y2 = ux.bounds(region)
                start, end = (.9, .35) if forward else (.35, .9)
                ux.adb("shell", "input", "swipe", str((x1+x2)//2), str(int(y1+(y2-y1)*start)),
                       str((x1+x2)//2), str(int(y1+(y2-y1)*end)), "500")
                time.sleep(.6)
        raise AssertionError("Card missing: " + label)

    def set_switch(self, label, checked):
        card = self.card(label)
        switch = next(n for n in card.iter("node") if n.get("checkable") == "true")
        if switch.get("checked") != str(checked).lower():
            assert switch.get("enabled") == "true", "Switch disabled: " + label
            self.click(switch)

    def card_action(self, label, action):
        # LazyColumn only exposes visible card content. Looking for an action in a
        # parent container can accidentally select a neighboring card when the
        # target card's buttons are just below the viewport (more common on API 29).
        variants = ux.labels(label)
        action_variants = ux.labels(action)
        for forward in (True, False):
            for _ in range(12):
                root, _ = ux.nodes()
                titles = [n for n in root.iter("node") if ux.visible(n) and
                          (n.get("text") in variants or n.get("content-desc") in variants)]
                actions = [n for n in root.iter("node") if ux.visible(n) and
                           (n.get("text") in action_variants or n.get("content-desc") in action_variants)]
                for title in titles:
                    ty = (ux.bounds(title)[1] + ux.bounds(title)[3]) // 2
                    nearby = [n for n in actions if (ux.bounds(n)[1] + ux.bounds(n)[3]) // 2 >= ty and
                              (ux.bounds(n)[1] + ux.bounds(n)[3]) // 2 - ty <= 560]
                    if nearby:
                        self.click(min(nearby, key=lambda n: (ux.bounds(n)[1] + ux.bounds(n)[3]) // 2 - ty))
                        return
                regions = [n for n in root.iter("node") if n.get("scrollable") == "true" and ux.visible(n)]
                if not regions:
                    time.sleep(.3)
                    continue
                region = max(regions, key=lambda n: ux.bounds(n)[3] - ux.bounds(n)[1])
                x1, y1, x2, y2 = ux.bounds(region)
                start, end = (.88, .38) if forward else (.38, .88)
                ux.adb("shell", "input", "swipe", str((x1+x2)//2), str(int(y1+(y2-y1)*start)),
                       str((x1+x2)//2), str(int(y1+(y2-y1)*end)), "450")
                time.sleep(.5)
        raise AssertionError("Card action missing: " + label + " / " + action)

    def input(self, index, text):
        fields = [n for n in ux.nodes()[0].iter("node") if n.get("class") == "android.widget.EditText" and ux.visible(n)]
        self.click(fields[index])
        ux.adb("shell", "input", "text", text.replace(" ", "%s"))

    def confirm_delete(self):
        matches = [n for n in ux.nodes()[0].iter("node") if ux.visible(n) and n.get("text") in ux.labels("删除")]
        self.click(matches[-1])  # The dialog title has the same text as the action.

    def enter_source(self, source):
        field = next(n for n in ux.nodes()[0].iter("node") if n.get("class") == "android.widget.EditText" and ux.visible(n))
        self.click(field)
        encoded = base64.b64encode(source.encode()).decode()
        last_error = None
        for attempt in range(3):
            try:
                ux.adb("shell", "env", "CLASSPATH=" + ux.UI_PROBE, "app_process", "-Xusejit:false", "/system/bin",
                       "com.mybrowser.validation.FastUiDump", "setText", encoded)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                # An IME transition can temporarily hide the accessibility root. The
                # shell helper may die after performing the action, so verify the real
                # field before retrying this idempotent replacement.
                last_error = error
            time.sleep(.25)
            fields = [n for n in ux.nodes()[0].iter("node") if n.get("class") == "android.widget.EditText"]
            if any(n.get("text") == source for n in fields):
                return
            print("Retrying source entry after UI helper interruption:", attempt + 1, flush=True)
            time.sleep(.35)
        raise AssertionError("Script source was not entered completely") from last_error

    def imports(self):
        self.page()
        ux.open_settings("隐私与过滤")
        ux.tap("油猴脚本")
        ux.tap(ux._translations[0]["script_paste"])
        self.enter_source("// ==UserScript==\n// @name Pure paste fixture\n// @namespace pure.validation\n"
                          "// @match http://127.0.0.1/feature-fixture.html*\n// @grant none\n"
                          "// @run-at document-end\n// ==/UserScript==\n"
                          "document.documentElement.dataset.pasted='yes';")
        self.back()
        ux.tap(ux._translations[0]["script_review"])
        root, _ = ux.nodes()
        ux.tap("替换脚本" if ux.match(root, "替换脚本") is not None else "安装脚本")
        self.page()
        self.wait(lambda s: s.get("pasted") == "yes")
        self.record("pasted source is reviewed, installed and executed")
        ux.adb("push", str(ROOT / "feature-dom.user.js"), "/sdcard/Download/PureDOM.user.js")
        ux.open_settings("隐私与过滤")
        ux.tap("油猴脚本")
        ux.tap(ux._translations[0]["script_import_file"])
        ux.choose_download_document("PureDOM.user.js")
        ux.expect("Pure DOM fixture")
        self.snapshot("file-import-preview")
        root, _ = ux.nodes()
        ux.tap("替换脚本" if ux.match(root, "替换脚本") is not None else "安装脚本")
        self.page()
        self.wait(lambda s: s["domReady"] != "")
        self.record("system file picker imports and executes a local user script")
        for name in ["Pure paste fixture", "Pure DOM fixture"]:
            ux.open_settings("隐私与过滤")
            ux.tap("油猴脚本")
            self.card_action(name, "删除")
            self.confirm_delete()
            self.page()

    def scripts(self):
        self.page()
        ux.tap("Install storage fixture")
        ux.expect("Pure storage fixture")
        self.snapshot("script-preview")
        root, _ = ux.nodes()
        ux.tap("替换脚本" if ux.match(root, "替换脚本") is not None else "安装脚本")
        ux.expect("油猴脚本")
        root, _ = ux.nodes()
        legacy = ux.match(root, ux._translations[0]["script_old_webview"]) is not None
        self.record("script links require review before installation", legacyWebView=legacy)
        self.page()
        if not legacy:
            first = self.wait(lambda s: s["storageCount"] > 0 and s["dependency"] == "dependency-ok" and s["styleApplied"])
            count = first["storageCount"]
            self.page()
            second = self.wait(lambda s: s["storageCount"] > count)
            ux.adb("shell", "am", "force-stop", ux.PACKAGE)
            self.page()
            third = self.wait(lambda s: s["storageCount"] > second["storageCount"])
            self.record("GM values and required library survive reload and process restart", visits=third["storageCount"])
            self.page("&excluded=1")
            self.wait(lambda s: s["ready"] == "complete" and s["storageCount"] == 0)
            self.record("excluded page does not execute the storage script")
        else:
            self.wait(lambda s: s["ready"] == "complete" and s["storageCount"] == 0)
            self.record("old WebView leaves privileged storage scripts unexecuted")
        self.page()
        ux.tap("Install DOM fixture")
        root, _ = ux.nodes()
        ux.tap("替换脚本" if ux.match(root, "替换脚本") is not None else "安装脚本")
        self.page()
        start = self.wait(lambda s: s["domReady"] != "")
        assert start["domReady"] == ("complete" if legacy else "loading"), start
        self.record("document-start script uses the detected WebView capability", observedReadyState=start["domReady"])
        self.since = time.time()
        ux.launch(self.base + "feature-popup-fixture.html?case=" + self.case + "&popup=1")
        ux.tap("Open script fixture in new tab")
        popup = self.wait(lambda s: s.get("popup") and s["domReady"] != "" and (legacy or s["storageCount"] > start["storageCount"]))
        assert popup["domReady"] == ("complete" if legacy else "loading"), popup
        if legacy:
            assert popup["storageCount"] == 0
        else:
            assert popup["dependency"] == "dependency-ok" and popup["styleApplied"]
        self.record("popup transport preserves script timing and supported GM storage", legacyWebView=legacy)
        ux.open_settings("隐私与过滤")
        ux.tap("油猴脚本")
        self.set_switch("Pure DOM fixture", False)
        self.snapshot("script-list")
        self.page()
        self.wait(lambda s: s["ready"] == "complete" and s["domReady"] == "")
        self.record("disabled script stops on the next navigation")
        ux.open_settings("隐私与过滤")
        ux.tap("油猴脚本")
        self.set_switch("Pure DOM fixture", True)
        self.page()
        ux.menu_item("进入无痕模式")
        self.page()
        self.wait(lambda s: s["ready"] == "complete" and s["domReady"] == "" and s["storageCount"] == 0)
        self.record("incognito executes neither DOM scripts nor privileged scripts")
        ux.menu_item("退出无痕模式")
        self.page()
        self.wait(lambda s: s["domReady"] != "")
        ux.tap("Install unsupported fixture")
        root, _ = ux.nodes()
        ux.tap("替换脚本" if ux.match(root, "替换脚本") is not None else "安装脚本")
        card = self.card("Pure unsupported fixture")
        switch = next(n for n in card.iter("node") if n.get("checkable") == "true")
        assert switch.get("checked") == "false" and switch.get("enabled") == "false"
        self.record("unsupported GM API is explained and kept disabled")
        self.snapshot("unsupported-script")
        for name in ["Pure unsupported fixture", "Pure DOM fixture", "Pure storage fixture"]:
            self.page()
            ux.open_settings("隐私与过滤")
            ux.tap("油猴脚本")
            self.card_action(name, "删除")
            self.confirm_delete()
        self.page()
        self.wait(lambda s: s["ready"] == "complete" and s["domReady"] == "" and s["storageCount"] == 0)
        self.record("deleting scripts removes execution and stored data")

    def filter_mode(self, value):
        request = urllib.request.Request(self.base + "__filter-mode?value=" + value, data=b"", method="POST")
        urllib.request.urlopen(request, timeout=5).close()

    def filter_settings(self):
        ux.open_settings("隐私与过滤")
        ux.tap("自定义广告过滤规则")
        ux.expect("广告过滤设置")

    def filter_idle(self, label="Pure QA"):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            switch = next(n for n in self.card(label).iter("node") if n.get("checkable") == "true")
            if switch.get("enabled") == "true": return
            time.sleep(.25)
        raise AssertionError("Filter update remained busy")

    def filters(self):
        self.filter_mode("valid")
        self.page()
        self.filter_settings()
        for name in ["EasyList", "EasyPrivacy", "EasyList China"]:
            self.card(name)
        self.record("three bundled mainstream subscriptions are present")
        try:
            self.card("Pure QA")
        except AssertionError:
            pass
        else:
            self.card_action("Pure QA", "删除")
            self.confirm_delete()
        self.page()
        self.filter_settings()
        ux.tap("添加过滤列表")
        self.input(0, "Pure QA")
        self.input(1, self.base + "__filter-list")
        self.back()  # keyboard
        ux.tap("添加")
        # Adding a subscription also rebuilds the bundled native/CSS rules on a
        # worker. Wait for completion, rather than the ordinary UI transition.
        ux.expect("广告过滤设置", timeout=20)
        self.filter_idle()
        self.snapshot("filter-subscribed")
        self.page()
        self.wait(lambda s: s["ready"] == "complete" and s["cosmeticHidden"] and not s["markerLoaded"])
        self.record("custom subscription blocks requests and hides matching elements")
        self.filter_settings()
        self.card_action("Pure QA", "更新")
        self.filter_idle()
        requests = json.load(urllib.request.urlopen(self.base + "__state?case=filter-requests"))
        assert any(r.get("validator") == '"pure-valid"' for r in requests)
        self.record("conditional update sends ETag and accepts unchanged content")
        for mode in ["invalid", "offline", "updated"]:
            self.filter_mode(mode)
            self.page()
            self.filter_settings()
            self.card_action("Pure QA", "更新")
            self.filter_idle()
            self.snapshot("filter-" + mode)
            self.page()
            self.wait(lambda s: s["cosmeticHidden"] and not s["markerLoaded"])
            self.record("subscription update keeps filtering usable", response=mode)
        self.filter_settings()
        self.set_switch("Pure QA", False)
        self.filter_idle()
        self.page()
        self.wait(lambda s: s["ready"] == "complete" and not s["cosmeticHidden"] and s["markerLoaded"])
        ux.adb("shell", "am", "force-stop", ux.PACKAGE)
        self.page()
        self.wait(lambda s: s["ready"] == "complete" and not s["cosmeticHidden"] and s["markerLoaded"])
        self.record("subscription disable survives process restart")
        self.filter_settings()
        self.set_switch("Pure QA", True)
        self.filter_idle()
        self.page()
        self.wait(lambda s: s["cosmeticHidden"] and not s["markerLoaded"])
        self.filter_settings()
        self.card_action("Pure QA", "删除")
        self.confirm_delete()
        self.record("custom subscription can be re-enabled and deleted")
        self.page()
        self.filter_settings()
        self.set_switch("自动更新规则", False)
        result = subprocess.run(ux.ADB + ["shell", "cmd", "jobscheduler", "get-job-state", ux.PACKAGE, "4107"],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        assert "could not find job" in result.stdout.lower() or "unknown" in result.stdout.lower(), result.stdout
        self.set_switch("自动更新规则", True)
        jobs = ux.adb("shell", "dumpsys", "jobscheduler", ux.PACKAGE)
        assert "4107" in jobs and "FilterUpdateJob" in jobs
        self.record("automatic update switch cancels and schedules the persisted job")

    def dialogs(self):
        self.page()
        ux.tap("Show alert")
        ux.expect("MD3 alert fixture")
        self.snapshot("md3-alert")
        ux.tap("确定")
        self.wait(lambda s: s.get("dialogResult") == "Alert completed")
        ux.tap("Show confirm")
        self.back()
        self.wait(lambda s: s.get("dialogResult") == "Confirm false")
        ux.tap("Show prompt")
        ux.tap("确定")
        self.wait(lambda s: s.get("dialogResult") == "Prompt example")
        self.record("Material dialogs answer alert, confirm cancellation and prompt callbacks")
        ux.launch(self.base + "__auth")
        ux.expect("需要身份验证")
        self.input(0, "test")
        self.input(1, "pure")
        self.snapshot("md3-http-auth")
        self.back()
        ux.tap("登录")
        ux.expect("Authentication completed")
        self.record("themed HTTP auth dialog submits masked credentials")

    def media(self):
        self.since = time.time()
        ux.launch(self.base + "media-fixture.html?case=" + self.case)
        self.wait(lambda s: bool(s.get("mediaButtons")))
        patterns = [re.escape(s["cast_detected_sources"]).replace(re.escape("%1$d"), r"\d+") for s in ux._translations]
        def cast_button():
            nodes = [n for n in ux.nodes()[0].iter("node") if ux.visible(n) and
                     any(re.fullmatch(p, n.get("content-desc", "")) for p in patterns)]
            assert len(nodes) == 1
            return nodes[0]
        def tap_media_button(name):
            try:
                ux.tap(name, timeout=25)
                return
            except AssertionError:
                # Old WebView can paint both buttons but omit them after a dialog
                # closes. Use fresh DOM geometry inside the verified page viewport.
                root, _ = ux.nodes()
                assert ux.match(root, "Pure media detection fixture") is not None
                web = next(n for n in root.iter("node") if n.get("class") == "android.webkit.WebView" and ux.visible(n))
                after = int(ux.adb("shell", "date", "+%s%3N"))
                state = self.wait(lambda s: s.get("capturedAt", 0) >= after
                                  and s.get("mediaButtons", {}).get(name, {}).get("width", 0) > 0)
                rect, scale = state["mediaButtons"][name], state["viewport"]["dpr"]
                left, top, right, bottom = ux.bounds(web)
                x, y = int(left + (rect["x"] + rect["width"] / 2) * scale), int(top + (rect["y"] + rect["height"] / 2) * scale)
                assert left <= x < right and top <= y < bottom, "Media button is outside the visible page"
                ux.adb("shell", "input", "tap", str(x), str(y))
                time.sleep(.7)
        for name, label in [("Play primary", "sample-default.mp4"), ("Play secondary", "player-sample.mp4")]:
            tap_media_button(name)
            self.wait(lambda s: s.get("playing") == name.removeprefix("Play "))
            time.sleep(1.2)
            self.snapshot("floating-cast-" + name[-7:])
            self.click(cast_button())
            ux.expect("选择要投送的内容")
            root, _ = ux.nodes()
            rows = [n for n in root.iter("node") if ux.match(n, "正在播放") is not None and
                    (n.get("selected") == "true" or n.get("checkable") == "true") and
                    any(label in c.get("text", "") for c in n.iter("node"))]
            assert rows, label
            row = min(rows, key=lambda n: len(list(n.iter("node"))))
            assert row.get("selected") == "true" or row.get("checked") == "true", "Current stream should be selected by default"
            self.snapshot("cast-playing-" + name[-7:])
            self.back()
            self.record("floating cast selects the currently playing media", media=label)
        self.page()
        assert not [n for n in ux.nodes()[0].iter("node") if any(re.fullmatch(p, n.get("content-desc", "")) for p in patterns)]
        self.record("cast action disappears when navigating to a page without media")

    def run(self):
        ux.adb("reverse", "tcp:8875", "tcp:8875")
        ux.adb("shell", "settings", "put", "system", "font_scale", "1.0")
        ux.adb("shell", "settings", "put", "system", "accelerometer_rotation", "1")
        ux.adb("emu", "sensor", "set", "acceleration", "0:9.8:0")
        ux.adb("shell", "am", "force-stop", ux.PACKAGE)
        for section in (["scripts", "imports", "filters", "dialogs", "media"] if self.section == "all" else [self.section]):
            getattr(self, section)()

    def save(self, error=None):
        (self.output / ("api" + self.sdk + "-features-" + self.section + ".json")).write_text(json.dumps({
            "serial": self.serial, "sdk": self.sdk, "case": self.case,
            "apkSha256": self.apk_hash, "checks": self.checks, "error": error
        }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--section", choices=["scripts", "imports", "filters", "dialogs", "media", "all"], default="all")
    args = parser.parse_args()
    test = Regression(args.serial, args.section)
    try:
        test.run()
    except Exception as error:
        test.save(str(error))
        raise
    test.save()
