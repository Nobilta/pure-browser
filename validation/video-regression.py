#!/usr/bin/env python3
"""Exercise the signed APK with real touches; assert playback through fixture telemetry.

Run qa-server.py first. Requires an arm64 emulator with the release APK installed.
Generated screenshots and JSON results are written below validation/results/.
"""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ux", ROOT / "emulator-ux.py")
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


class Regression:
    def __init__(self, serial, variant, expect_enhanced=False):
        self.serial, self.variant = serial, variant
        self.expect_enhanced = expect_enhanced
        ux.ADB = ["adb", "-s", serial]
        self.sdk = ux.adb("shell", "getprop", "ro.build.version.sdk")
        self.case = "api" + self.sdk + "-" + variant + "-" + str(int(time.time()))
        self.output = ROOT / "results"
        self.output.mkdir(exist_ok=True)
        self.checks = []
        self.width = self.height = 0
        apk_path = ux.adb("shell", "pm", "path", "com.mybrowser").partition(":")[2].strip()
        self.apk_hash = ux.adb("shell", "sha256sum", apk_path).split()[0]

    def record(self, name, **details):
        self.checks.append({"check": name, **details})
        print("PASS:", name, json.dumps(details, ensure_ascii=False), flush=True)

    def events(self):
        url = "http://127.0.0.1:8875/__state?case=" + urllib.parse.quote(self.case)
        events = json.load(urllib.request.urlopen(url, timeout=5))
        # Concurrent WebView requests can arrive out of order on a busy emulator.
        # Assertions must use the order in which playback was actually observed.
        return sorted(events, key=lambda s: s.get("capturedAt", s["receivedAt"] * 1000))

    def wait(self, predicate, timeout=8):
        deadline = time.monotonic() + timeout
        latest = None
        while time.monotonic() < deadline:
            events = self.events()
            latest = events[-1] if events else None
            if latest and predicate(latest):
                return latest
            time.sleep(.2)
        raise AssertionError("Playback condition not met: " + json.dumps(latest))

    def snapshot(self, suffix, expected_size=None):
        deadline = time.monotonic() + 10
        stable = 0
        while True:
            png = subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"], timeout=20)
            self.width, self.height = struct.unpack(">II", png[16:24])
            if expected_size is None:
                break
            stable = stable + 1 if (self.width, self.height) == expected_size else 0
            if stable >= 2:
                break
            assert time.monotonic() < deadline, "Display did not settle at " + str(expected_size)
            time.sleep(.3)
        (self.output / (self.case + "-" + suffix + ".png")).write_bytes(png)
        return self.width, self.height

    @staticmethod
    def restored_inline(state):
        # The original webpage controls are restored after fullscreen.
        return (not state["fullscreen"] and state["sameElement"]
                and state["transientMarkers"] == 0
                and state["controls"] and not state["enhanced"])

    def touch(self, x=.5, y=.5):
        ux.adb("shell", "input", "tap", str(int(self.width * x)), str(int(self.height * y)))

    def swipe(self, start, end, duration=450):
        coordinates = [int(start[0] * self.width), int(start[1] * self.height),
                       int(end[0] * self.width), int(end[1] * self.height)]
        ux.adb("shell", "input", "swipe", *map(str, coordinates), str(duration))

    def controls(self):
        for _ in range(3):
            root, _ = self.player_nodes(reveal=True)
            if ux.match(root, "退出全屏") is not None:
                removed = {"Rewind 10 seconds", "Forward 10 seconds", "Switch to web controls", "Switch to enhanced controls",
                           "快退10秒", "快进10秒", "快進10秒", "切换到网页控件", "切换到增强控件", "切換到網頁控件", "切換到增強控件"}
                assert not any(ux.visible(n) and (n.get("content-desc") in removed or n.get("text") in removed)
                               for n in root.iter("node")), "Removed fullscreen buttons are still visible"
                return root
            self.touch()
            time.sleep(.4)
        raise AssertionError("A single tap did not reveal fullscreen controls")

    def player_nodes(self, reveal=False):
        action = ["playerReveal", str(self.width // 2), str(self.height // 2)] if reveal else ["playerDump"]
        raw = ux.adb("shell", "env", "CLASSPATH=" + ux.UI_PROBE, "app_process", "-Xusejit:false", "/system/bin",
                     "com.mybrowser.validation.FastUiDump", *action)
        raw = raw[raw.index("<?xml"):raw.index("</hierarchy>") + len("</hierarchy>")]
        return ET.fromstring(raw), raw

    def player_tap(self, label):
        encoded = base64.b64encode(json.dumps(sorted(ux.labels(label))).encode()).decode()
        result = ux.adb("shell", "env", "CLASSPATH=" + ux.UI_PROBE, "app_process", "-Xusejit:false", "/system/bin",
                        "com.mybrowser.validation.FastUiDump", "playerTap", encoded,
                        str(self.width // 2), str(self.height // 2))
        return "Tapped" in result

    def button(self, label, reveal=True):
        if label == "Web play/pause" and self.variant.startswith("custom"):
            # Older providers expose stale or incomplete fullscreen accessibility nodes.
            # Touch the fixture's live DOM geometry; callers verify real playback after it.
            state = self.wait(lambda s: s["fullscreen"] and s["webControlsVisible"]
                              and s.get("webPlayRect", {}).get("width", 0) > 0)
            rect, scale = state["webPlayRect"], state["viewport"]["dpr"]
            ux.adb("shell", "input", "tap", str(int((rect["x"] + rect["width"] / 2) * scale)),
                   str(int((rect["y"] + rect["height"] / 2) * scale)))
            time.sleep(.4)
            return
        for _ in range(3):
            # Controls can auto-hide between a hierarchy dump and a separate shell
            # process. Reveal, resolve and tap inside one connected service.
            if (self.player_tap(label) if reveal else ux.tap_now(label)):
                time.sleep(.4)
                return
            if reveal:
                self.touch()
            time.sleep(.3)
        raise AssertionError("Control missing: " + label)

    def double_tap(self, x=.5):
        try:
            ux.adb("shell", "env", "CLASSPATH=" + ux.UI_PROBE, "app_process", "-Xusejit:false", "/system/bin",
                   "com.mybrowser.validation.FastUiDump", "doubleTap", str(int(self.width * x)), str(self.height // 2))
        except subprocess.CalledProcessError as error:
            # ART can kill the shell helper during disconnect. Accept only an explicit
            # completed-injection receipt; every caller still asserts real playback state.
            if error.returncode != 137 or "Double tapped" not in (error.output or ""):
                raise
        time.sleep(.5)

    def app_window(self):
        dump = ux.adb("shell", "dumpsys", "window", "windows")
        start = dump.index("package=com.mybrowser")
        return dump[start:dump.find("Window #", start)]

    def player_windows(self):
        dump = ux.adb("shell", "dumpsys", "window", "windows")
        tokens = re.findall(r'Window #\d+ Window\{(\S+) [^}\n]*com\.mybrowser/com\.mybrowser\.MainActivity', dump)
        assert tokens, "The fullscreen Activity window is missing"
        return sorted(tokens)

    def popup_baseline(self):
        root = self.controls()
        bar = ux.match(root, "player_control_bar")
        assert bar is not None, "Material player control bar missing"
        return {"video": self.wait(lambda s: s["fullscreen"] and s["enhanced"]),
                "bar": ux.bounds(bar), "windows": self.player_windows()}

    def stable_player(self, baseline):
        before = baseline["video"]
        latest = self.wait(lambda s: s["capturedAt"] > before["capturedAt"] + 600 and s["fullscreen"])
        events = [s for s in self.events() if before["capturedAt"] < s["capturedAt"] <= latest["capturedAt"]]
        assert events, "No telemetry received while the menu was open"
        for state in events:
            assert state["fullscreen"] and state["sameElement"] and state["source"] == before["source"]
            assert state["viewport"] == before["viewport"], "Menu resized the video viewport"
            for key in ("x", "y", "width", "height"):
                assert abs(state["videoRect"][key] - before["videoRect"][key]) < .1, "Menu moved the video: " + key
        assert self.player_windows() == baseline["windows"], "Player menu opened another window"

    def floating_menu(self, kind, baseline):
        root, _ = self.player_nodes()
        popup = ux.match(root, "player_" + kind + "_menu")
        bar = ux.match(root, "player_control_bar")
        assert popup is not None and bar is not None, "Floating menu or player controls disappeared"
        rect, controls = ux.bounds(popup), ux.bounds(bar)
        assert controls == baseline["bar"], "Opening the menu moved the control bar"
        assert abs(rect[2] - controls[2]) <= 2 and rect[3] < controls[1], "Menu is not anchored above the right corner"
        scale = baseline["video"]["viewport"]["dpr"]
        assert 0 <= rect[0] < rect[2] <= self.width and 0 <= rect[1] < rect[3] <= self.height
        assert rect[2] - rect[0] <= (288 if kind == "speed" else 336) * scale + 2
        assert rect[3] - rect[1] <= 360 * scale + 2
        if self.width > self.height:
            assert rect[0] > self.width * .45, "Menu covers most of the landscape video"
        self.stable_player(baseline)
        self.snapshot(kind + ("-landscape" if self.width > self.height else "-portrait") + "-floating")
        self.record(kind + " menu stays in the lower right without moving video or opening a window",
                    menuBounds=rect, controlBounds=controls, windowTokens=baseline["windows"])

    def brightness(self):
        match = re.search(r"sbrt=([\d.-]+)", self.app_window())
        return float(match[1]) if match else -1.0

    def volume(self):
        command = ["cmd", "media_session", "volume"]
        output = ux.adb("shell", *command, "--stream", "3", "--get")
        match = re.search(r"volume is (\d+)", output)
        if not match:
            raise AssertionError(output)
        return int(match[1])

    def inline_button(self, label, rect_key):
        inline = self.wait(lambda s: s["duration"] > 0 and not s["fullscreen"]
                           and s.get(rect_key, {}).get("width", 0) > 0)
        root, _ = ux.nodes()
        play = ux.match(root, label)
        if play is not None:
            x1, y1, x2, y2 = ux.bounds(play)
            x, y = (x1 + x2) // 2, (y1 + y2) // 2
        else:
            web = next(n for n in root.iter("node") if n.get("class") == "android.webkit.WebView")
            left, top, right, bottom = ux.bounds(web)
            # Popup frames can be painted and playable while an old provider omits
            # their DOM from accessibility. Use reported geometry for a real touch.
            rect, scale = inline[rect_key], inline["viewport"]["dpr"]
            frame_top = inline.get("frameTop", 0)
            x = int(left + (rect["x"] + rect["width"] / 2) * scale)
            y = int(top + (frame_top + rect["y"] + rect["height"] / 2) * scale)
            assert left <= x < right and top <= y < bottom, "Fixture button is outside the visible WebView"
        ux.adb("shell", "input", "tap", str(x), str(y))

    def enter_fullscreen(self):
        self.inline_button("Play fullscreen", "startRect")
        self.wait(lambda s: s["fullscreen"] and not s["paused"])
        time.sleep(1)

    def custom_player(self, original_size, before):
        if self.variant == "custom-csp":
            self.wait(lambda s: s["fullscreen"] and not s["enhanced"] and s["webControlsVisible"])
            self.button("Web play/pause", reveal=False)
            self.wait(lambda s: s["paused"] and not s["controls"])
            self.button("Web play/pause", reveal=False)
            self.wait(lambda s: not s["paused"])
            self.record("CSP-blocked handoff restores usable website controls")
        else:
            state = self.wait(lambda s: s["fullscreen"] and s["enhanced"] and not s["paused"]
                              and not s["webControlsVisible"] and s["dynamicControlAdded"] and not s["dynamicControlVisible"])
            assert state["sameElement"] and state["source"] == before["source"]
            assert state["currentTime"] >= before["currentTime"] and not state["controls"]
            for key in ("left", "top", "width", "height"):
                assert abs(state["videoRect"][key] - state["rootRect"][key]) <= 2, state
            self.record("custom container automatically uses enhanced controls without reloading video")
            self.record("nested transformed video fills fullscreen and new website controls stay hidden")
            self.snapshot("custom-enhanced")
            self.button("暂停视频")
            paused = self.wait(lambda s: s["paused"])
            self.double_tap(.83)
            self.wait(lambda s: s["paused"] and s["currentTime"] >= paused["currentTime"] + 9)
            baseline = self.popup_baseline()
            self.button("播放速度 1×")
            self.floating_menu("speed", baseline)
            self.button("1.5×", reveal=False)
            self.wait(lambda s: s["paused"] and s["rate"] == 1.5)
            ux.expect("player_speed_menu", present=False)
            self.stable_player(baseline)
            self.record("native pause, seek and speed control the original custom video")

            self.button("播放视频")
            self.wait(lambda s: not s["paused"] and s["rate"] == 1.5)
            self.record("enhanced toolbar omits mode switching and skip buttons; side double tap still seeks")
            self.button("锁定屏幕")
            ux.adb("shell", "input", "keyevent", "4")
            self.wait(lambda s: s["fullscreen"] and s["enhanced"])
            assert ux.match(self.controls(), "锁定屏幕") is not None
            self.record("custom fullscreen Back unlocks before exiting")

        ux.adb("shell", "input", "keyevent", "4")
        self.wait(lambda s: not s["fullscreen"] and not s["controls"] and s["transientMarkers"] == 0)
        for _ in range(2):
            self.enter_fullscreen()
            self.wait(lambda s: s["enhanced"] == (self.variant != "custom-csp"))
            ux.adb("shell", "input", "keyevent", "4")
            self.wait(lambda s: not s["fullscreen"] and not s["controls"] and s["transientMarkers"] == 0)
        time.sleep(1)
        assert self.snapshot("exited") == original_size
        self.record("repeated custom fullscreen exits restore the original controls and orientation")
        if self.variant == "custom":
            self.website_preference()

    def run(self):
        # UiAutomation restores a frozen rotation on disconnect; use the sensor so
        # hierarchy snapshots cannot overwrite the system's portrait lock setting.
        ux.adb("shell", "settings", "put", "system", "accelerometer_rotation", "1")
        ux.adb("shell", "settings", "put", "system", "user_rotation", "0")
        ux.adb("emu", "sensor", "set", "acceleration", "0:9.8:0")
        time.sleep(1)
        for port in (8875, 8876):
            ux.adb("reverse", "tcp:" + str(port), "tcp:" + str(port))
        ux.adb("shell", "am", "force-stop", "com.mybrowser")
        popup = self.variant in ("popup", "popup-cross")
        cross = self.variant in ("cross", "popup-cross", "custom-cross")
        custom = self.variant.startswith("custom")
        page = ("player-popup-fixture.html" if popup else
                "player-cross-frame.html" if cross else "player-fixture.html")
        query = "?case=" + self.case + ("&" + self.variant + "=1" if self.variant in ("blob", "square", "custom") else "")
        if custom and self.variant != "custom":
            query += "&custom=1"
        if self.variant == "custom-blob":
            query += "&blob=1"
        if self.variant == "custom-csp":
            query += "&csp=1"
        if self.variant == "popup-cross":
            query += "&cross=1"
        launch_started = time.monotonic()
        self.page_url = "http://127.0.0.1:8875/" + page + query
        ux.launch(self.page_url)
        if popup:
            ux.tap("Open video in new tab", timeout=25)
        # A cold emulator must build the bundled filter engine and start the media
        # process. Measure that separately; interaction assertions still use 8 seconds.
        inline = self.wait(lambda s: s["duration"] > 0 and not s["fullscreen"], timeout=25)
        self.record("cold page reaches playable metadata", seconds=round(time.monotonic() - launch_started, 2))
        assert not inline["enhanced"] and inline["transientMarkers"] == 0
        assert inline["controls"] == (not custom), "Non-fullscreen controls were replaced"
        self.record("non-fullscreen playback keeps the original webpage controls")
        if popup:
            for attempt in range(2):
                after = int(ux.adb("shell", "date", "+%s%3N"))
                ux.launch("http://127.0.0.1:8875/" + page + query + "&repeat=" + str(attempt))
                ux.tap("Open video in new tab", timeout=25)
                inline = self.wait(lambda s: s["capturedAt"] >= after and s["duration"] > 0 and not s["fullscreen"], timeout=25)
            self.record("three consecutive popup tabs load without reusing a navigated WebView")
        if self.expect_enhanced:
            assert inline.get("probe"), "Document-start media probe missing from the popup frame"
        # Each case owns its local fixture origin, including after a failed opt-out check.
        self.set_website_playback(True)
        inline = self.wait(lambda s: s["duration"] > 0 and not s["fullscreen"])
        original_brightness = self.brightness()
        original_size = self.snapshot("inline")
        root, _ = ux.nodes()
        assert ux.match(root, "播放速度，当前 1×") is None
        cast_patterns = [re.escape(strings['cast_detected_sources']).replace(re.escape('%1$d'), r'\d+')
                         for strings in ux._translations]
        cast_buttons = [n for n in root.iter('node') if ux.visible(n) and
                        any(re.fullmatch(p, n.get('content-desc', '')) for p in cast_patterns)]
        if inline["source"].startswith("blob:"):
            # A decoded Blob is controllable without a directly fetchable cast URL.
            # Providers may serve the fetch from cache or finish it before navigation
            # has established its candidate scope; neither should disable the player.
            assert len(cast_buttons) <= 1, 'Duplicate floating cast actions'
        else:
            assert len(cast_buttons) == 1, 'Expected one floating cast action for the loaded fixture source'
        self.record("inline page has no duplicate cast or playback controls", castActions=len(cast_buttons))
        if popup and (not cross or inline.get("probe")):
            self.inline_button("Play inline", "inlinePlayRect")
            self.wait(lambda s: not s["paused"])
            ux.tap(cast_buttons[0].get("content-desc"))
            ux.expect("选择要投送的内容")
            ux.expect("正在播放")
            self.snapshot("popup-playing-cast")
            ux.adb("shell", "input", "keyevent", "4")
            ux.expect("选择要投送的内容", present=False)
            self.record("new popup tab retains its playing-media marker after the previous WebView is released")
        if custom:
            self.inline_button("Play inline", "inlinePlayRect")
            inline = self.wait(lambda s: not s["paused"] and s["currentTime"] >= 2)
        self.enter_fullscreen()
        root, _ = ux.nodes()
        if ux.match(root, "Got it") is not None:
            self.button("Got it", reveal=False)
        fullscreen_size = self.snapshot("entered")
        self.record("fullscreen opened", width=self.width, height=self.height)

        if custom and (not cross or inline.get("probe")):
            self.custom_player(original_size, inline)
            return

        if cross and not inline.get("probe"):
            assert ux.match(root, "退出全屏") is None
            ux.adb("shell", "input", "keyevent", "4")
            self.wait(lambda s: not s["fullscreen"])
            self.record("unsupported cross-origin provider preserves webpage playback")
            return

        time.sleep(4)
        root, _ = self.player_nodes()
        assert ux.match(root, "退出全屏") is None, "Telemetry repeatedly revealed native controls"
        self.record("native controls auto-hide while telemetry continues")
        self.touch()
        time.sleep(.4)
        root, _ = self.player_nodes()
        assert ux.match(root, "退出全屏") is not None, "Single tap confirmation was cancelled"
        enhanced = ux.match(root, "锁定屏幕") is not None
        self.record("single tap reveals controls", enhanced=enhanced)
        self.snapshot("controls")
        if self.variant == "square":
            assert enhanced and self.width < self.height
            self.record("square video preserves portrait orientation")
            for kind in ("speed", "cast"):
                baseline = self.popup_baseline()
                self.button("player_" + kind + "_action")
                self.floating_menu(kind, baseline)
                self.button("关闭", reveal=False)
                ux.expect("player_" + kind + "_menu", present=False)
            self.button("切换横竖屏")
            time.sleep(1)
            self.snapshot("rotated")
            assert self.width > self.height
            for kind in ("speed", "cast"):
                baseline = self.popup_baseline()
                self.button("player_" + kind + "_action")
                self.floating_menu(kind, baseline)
                self.button("关闭", reveal=False)
                ux.expect("player_" + kind + "_menu", present=False)
            self.button("退出全屏")
            self.wait(self.restored_inline)
            time.sleep(1)
            assert self.snapshot("exited") == original_size
            self.record("manual rotation and exit restore the original orientation")
            return
        if not enhanced:
            assert cross and not self.expect_enhanced, "Expected enhanced controls failed"
            self.button("退出全屏")
            self.wait(lambda s: not s["fullscreen"])
            self.record("unsupported cross-origin provider preserves webpage playback")
            return
        assert self.width > self.height, "Landscape video did not rotate"
        self.record("landscape video rotates automatically")

        if cast_buttons:
            baseline = self.popup_baseline()
            self.button("投屏")
            ux.expect("选择要投送的内容")
            self.wait(lambda s: s["fullscreen"])
            self.floating_menu("cast", baseline)
            time.sleep(4)
            self.floating_menu("cast", baseline)
            dismiss_started = time.time()
            ux.adb("shell", "input", "keyevent", "4")
            self.wait(lambda s: s["receivedAt"] > dismiss_started + .5 and s["fullscreen"] and not s["controls"])
            ux.expect("选择要投送的内容", present=False)
            self.stable_player(baseline)
            self.record("cast picker opens and dismisses without leaving fullscreen")
        else:
            assert inline["source"].startswith("blob:")
            self.record("Blob video keeps enhanced controls without a direct cast candidate")

        self.button("暂停视频")
        self.wait(lambda s: s["paused"])
        self.snapshot("paused")
        self.button("播放视频")
        self.wait(lambda s: not s["paused"])
        self.record("pause and resume retain control target")

        baseline = self.popup_baseline()
        self.button("播放速度 1×")
        self.floating_menu("speed", baseline)
        # Dismissal consumes this touch; it must not pause or seek the video below it.
        self.touch(.2, .3)
        ux.expect("player_speed_menu", present=False)
        self.wait(lambda s: s["fullscreen"] and not s["paused"] and s["rate"] == 1)
        self.stable_player(baseline)
        self.button("播放速度 1×")
        self.button("1.5×", reveal=False)
        self.wait(lambda s: s["rate"] == 1.5)
        ux.expect("player_speed_menu", present=False)
        self.stable_player(baseline)
        started = time.time()
        self.swipe((.5, .5), (.5, .5), 1500)
        deadline = time.monotonic() + 8
        while True:
            during = [s for s in self.events() if s["receivedAt"] >= started]
            boosted = next((s for s in during if s["rate"] == 2), None)
            if boosted is not None:
                break
            assert time.monotonic() < deadline, "Long press did not boost playback"
            time.sleep(.2)
        state = self.wait(lambda s: s["capturedAt"] > boosted["capturedAt"] and s["rate"] == 1.5)
        assert state["defaultRate"] == 1.5
        self.record("long press boosts to 2x and release restores 1.5x")

        hold = subprocess.Popen(ux.ADB + ["shell", "input", "swipe",
            str(self.width // 2), str(self.height // 2), str(self.width // 2),
            str(self.height // 2), "3000"], stdout=subprocess.DEVNULL)
        self.wait(lambda s: s["rate"] == 2)
        ux.adb("shell", "input", "keyevent", "3")
        hold.wait(timeout=10)
        # Home can still be transitioning into PiP after the held gesture ends.
        # Launching during that transition may leave the Activity pinned underneath
        # a successful am-start receipt, where fullscreen buttons are unavailable.
        deadline = time.monotonic() + 15
        while True:
            activity = ux.adb("shell", "dumpsys", "activity", "activities")
            pinned = "mode=pinned" in activity or "windowingMode=2" in activity or "mWindowingMode=2" in activity
            foreground = re.search(r'(?:topResumedActivity|mResumedActivity)[^\n]*com\.mybrowser/[^\n]*MainActivity', activity)
            if (pinned and "mLastReportedPictureInPictureMode=true" in activity) or (not pinned and not foreground):
                break
            assert time.monotonic() < deadline, "Home did not finish leaving the fullscreen Activity"
            time.sleep(.3)
        ux.launch()
        deadline = time.monotonic() + 15
        while True:
            activity = ux.adb("shell", "dumpsys", "activity", "activities")
            pinned = "mode=pinned" in activity or "windowingMode=2" in activity or "mWindowingMode=2" in activity
            foreground = re.search(r'(?:topResumedActivity|mResumedActivity)[^\n]*com\.mybrowser/[^\n]*MainActivity', activity)
            if foreground and not pinned:
                break
            assert time.monotonic() < deadline, "Browser did not expand out of PiP on resume"
            time.sleep(.3)
        # Receiving a queued background event is not evidence of the resumed page.
        # Use the emulator clock so host/device skew cannot admit an older snapshot.
        resumed_after = int(ux.adb("shell", "date", "+%s%3N"))
        resumed = self.wait(lambda s: s["capturedAt"] >= resumed_after and s["rate"] == 1.5)
        # PiP expansion can report a resumed Activity while the screenshot/input
        # display is still rotating. A cached portrait height makes later reveal
        # taps land outside the landscape screen, even though playback is healthy.
        self.snapshot("resumed", expected_size=fullscreen_size if resumed["fullscreen"] else original_size)
        self.record("backgrounding cancels temporary speed before returning", fullscreen=resumed["fullscreen"])
        if not resumed["fullscreen"]:
            self.wait(self.restored_inline)
            assert self.brightness() == original_brightness
            self.enter_fullscreen()
            self.wait(lambda s: not s["controls"])
            self.snapshot("reentered")
        else:
            self.button("退出全屏")
            self.wait(self.restored_inline)
            self.enter_fullscreen()
            self.wait(lambda s: not s["controls"] and not s["paused"])
            self.snapshot("reentered")
        self.record("fullscreen handoff works again after backgrounding")

        if self.variant == "blob":
            for _ in range(3):
                self.button("退出全屏")
                self.wait(self.restored_inline)
                self.enter_fullscreen()
                self.wait(lambda s: not s["controls"] and not s["paused"])
                self.snapshot("reentered-repeated")
            self.record("three immediate Blob fullscreen reentries preserve confirmed control handoff")

        self.double_tap()
        self.wait(lambda s: s["paused"])
        self.double_tap()
        self.wait(lambda s: not s["paused"])
        self.record("center double tap pauses and resumes")
        self.button("暂停视频")
        paused = self.wait(lambda s: s["paused"])
        direction = -1 if paused["currentTime"] > 15 else 1
        before = paused["currentTime"]
        self.swipe((.5, .5), (.5 + .25 * direction, .5))
        state = self.wait(lambda s: abs(s["currentTime"] - before) > 2)
        assert state["paused"]
        self.record("horizontal swipe seeks while paused", before=before, after=state["currentTime"])

        self.swipe((.22, .7), (.22, .4))
        adjusted = self.brightness()
        assert adjusted >= 0 and adjusted != original_brightness, (original_brightness, adjusted)
        self.record("left vertical swipe changes window brightness", brightness=adjusted)
        before_volume = self.volume()
        start, end = ((.78, .4), (.78, .7)) if before_volume >= 10 else ((.78, .7), (.78, .4))
        self.swipe(start, end)
        after_volume = self.volume()
        assert after_volume != before_volume, (before_volume, after_volume)
        self.record("right vertical swipe changes media volume", before=before_volume, after=after_volume)
        command = ["cmd", "media_session", "volume"]
        ux.adb("shell", *command, "--stream", "3", "--set", str(before_volume))

        self.button("锁定屏幕")
        before = self.events()[-1]["currentTime"]
        self.double_tap()
        self.swipe((.5, .5), (.8, .5))
        state = self.events()[-1]
        assert state["paused"] and abs(state["currentTime"] - before) < .5
        back_started = time.time()
        ux.adb("shell", "input", "keyevent", "4")
        self.wait(lambda s: s["receivedAt"] > back_started + .4 and s["fullscreen"])
        assert ux.match(self.controls(), "锁定屏幕") is not None
        self.record("lock blocks gestures and Back unlocks before exiting")

        if ux.adb("shell", "settings", "get", "secure", "navigation_mode").strip() == "2":
            self.button("锁定屏幕")
            # Immersive mode can consume the first swipe to reveal system bars. Stop
            # as soon as Back unlocks; a second delivered Back would correctly exit.
            for _ in range(2):
                self.swipe((.999, .5), (.7, .5), 500)
                time.sleep(.2)
                # A full WebView hierarchy can outlast Android's transient system
                # bars. Check the native lock first so a second edge swipe arrives
                # while Back is enabled, instead of only revealing the bars again.
                if not ux.visible_now("解锁屏幕"):
                    break
            self.wait(lambda s: s["fullscreen"])
            assert ux.match(self.controls(), "锁定屏幕") is not None, "System Back did not unlock the player"
            self.record("system edge Back gesture unlocks without exiting the fullscreen video")

        self.button("退出全屏")
        self.wait(self.restored_inline)
        time.sleep(1)
        size = self.snapshot("exited")
        assert size == original_size, (size, original_size)
        assert self.brightness() == original_brightness
        self.record("exit restores orientation, brightness and the original webpage controls")
        if self.variant == "standard":
            self.website_preference()

    def set_website_playback(self, enabled):
        ux.menu_item("网站设置")
        for _ in range(8):
            root, _ = ux.nodes()
            label = ux.match(root, "增强全屏播放")
            if label is not None:
                break
            regions = [n for n in root.iter("node") if n.get("scrollable") == "true" and ux.visible(n)]
            assert regions, "Website playback setting is not scrollable into view"
            x1, y1, x2, y2 = ux.bounds(max(regions, key=lambda n: ux.bounds(n)[3] - ux.bounds(n)[1]))
            ux.adb("shell", "input", "swipe", str((x1+x2)//2), str(y1+(y2-y1)*4//5),
                   str((x1+x2)//2), str(y1+(y2-y1)//4), "350")
        assert label is not None, "Website playback switch is missing"
        containers = [n for n in root.iter("node") if ux.match(n, "增强全屏播放") is not None
                      and any(c.get("checkable") == "true" for c in n.iter("node"))]
        row = min(containers, key=lambda n: len(list(n.iter("node"))))
        switch = next(n for n in row.iter("node") if n.get("checkable") == "true")
        if switch.get("checked") != str(enabled).lower():
            ux.tap_node(switch)
        saved_at = time.time()
        ux.tap("保存并刷新")
        # Save/reload intentionally closes the full menu path; Cancel returns one level.
        ux.expect("编辑网址")
        self.wait(lambda state: state["receivedAt"] > saved_at and not state["fullscreen"] and state["duration"] > 0, timeout=25)

    def website_preference(self):
        self.set_website_playback(False)
        for attempt in range(2):
            if attempt:
                ux.adb("shell", "am", "force-stop", ux.PACKAGE)
                ux.launch(self.page_url)
                self.wait(lambda state: not state["fullscreen"] and state["duration"] > 0, timeout=25)
            self.enter_fullscreen()
            self.wait(lambda state: state["fullscreen"] and not state["enhanced"]
                      and state["controls"] == (not self.variant.startswith("custom")))
            self.snapshot("website-controls-disabled-" + str(attempt))
            root, _ = ux.nodes()
            assert ux.match(root, "锁定屏幕") is None, "Disabled website still has enhanced controls"
            if self.variant.startswith("custom"):
                self.button("Web play/pause", reveal=False)
                self.wait(lambda state: state["paused"])
            ux.adb("shell", "input", "keyevent", "4")
            self.wait(lambda state: not state["fullscreen"] and state["transientMarkers"] == 0)
        self.record("website opt-out preserves fullscreen web controls and survives process restart")
        self.set_website_playback(True)
        self.enter_fullscreen()
        self.wait(lambda state: state["fullscreen"] and state["enhanced"])
        self.snapshot("website-enhanced-enabled")
        self.button("退出全屏")
        self.wait(lambda state: not state["fullscreen"] and state["transientMarkers"] == 0)
        self.record("website opt-in enables takeover again only in fullscreen")

    def save(self, error=None):
        value = {"serial": self.serial, "sdk": self.sdk, "case": self.case,
                 "apkSha256": self.apk_hash,
                 "checks": self.checks, "error": error, "lastPlayback": self.events()[-1:]}
        (self.output / (self.case + ".json")).write_text(
            json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--variant", choices=["standard", "blob", "cross", "square", "custom", "custom-blob", "custom-cross", "custom-csp", "popup", "popup-cross"], default="standard")
    parser.add_argument("--expect-enhanced", action="store_true", help="Require the known provider to inject the media probe; do not accept cross-frame fallback")
    args = parser.parse_args()
    test = Regression(args.serial, args.variant, args.expect_enhanced)
    try:
        test.run()
    except Exception as error:
        test.snapshot("failure")
        test.save(str(error))
        raise
    else:
        test.save()
