#!/usr/bin/env python3
"""Exercise the signed APK with real touches; assert playback through fixture telemetry.

Run qa-server.py first. Requires an arm64 emulator with the release APK installed.
Generated screenshots and JSON results are written below validation/results/.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import time
import urllib.parse
import urllib.request

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

    def snapshot(self, suffix):
        png = subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"], timeout=20)
        self.width, self.height = struct.unpack(">II", png[16:24])
        (self.output / (self.case + "-" + suffix + ".png")).write_bytes(png)
        return self.width, self.height

    def touch(self, x=.5, y=.5):
        ux.adb("shell", "input", "tap", str(int(self.width * x)), str(int(self.height * y)))

    def swipe(self, start, end, duration=450):
        coordinates = [int(start[0] * self.width), int(start[1] * self.height),
                       int(end[0] * self.width), int(end[1] * self.height)]
        ux.adb("shell", "input", "swipe", *map(str, coordinates), str(duration))

    def controls(self):
        for _ in range(3):
            root, _ = ux.nodes()
            if ux.match(root, "退出全屏") is not None:
                return root
            self.touch()
            time.sleep(.4)
        raise AssertionError("A single tap did not reveal fullscreen controls")

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
            # input process. Resolve visibility and inject this tap together.
            if ux.tap_now(label):
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

    def brightness(self):
        match = re.search(r"sbrt=([\d.-]+)", self.app_window())
        return float(match[1]) if match else -1.0

    def volume(self):
        command = ["media", "volume"] if int(self.sdk) < 30 else ["cmd", "media_session", "volume"]
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
            self.button("快进10秒")
            self.wait(lambda s: s["paused"] and s["currentTime"] >= paused["currentTime"] + 9)
            self.button("播放速度 1×")
            self.button("1.5×", reveal=False)
            self.wait(lambda s: s["paused"] and s["rate"] == 1.5)
            self.record("native pause, seek and speed control the original custom video")

            self.button("切换到网页控件")
            self.wait(lambda s: s["fullscreen"] and not s["enhanced"] and not s["controls"]
                      and s["webControlsVisible"] and s["dynamicControlVisible"] and s["transientMarkers"] == 0)
            self.button("Web play/pause", reveal=False)
            self.wait(lambda s: not s["paused"] and s["rate"] == 1.5)
            self.snapshot("custom-web-controls")
            self.button("切换到增强控件")
            self.wait(lambda s: s["enhanced"] and not s["webControlsVisible"] and s["source"] == before["source"])
            self.record("switching back restores working website controls and preserves playback state")
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
        ux.launch("http://127.0.0.1:8875/" + page + query)
        if popup:
            ux.tap("Open video in new tab", timeout=25)
        # A cold emulator must build the bundled filter engine and start the media
        # process. Measure that separately; interaction assertions still use 8 seconds.
        inline = self.wait(lambda s: s["duration"] > 0 and not s["fullscreen"], timeout=25)
        self.record("cold page reaches playable metadata", seconds=round(time.monotonic() - launch_started, 2))
        if popup:
            for attempt in range(2):
                after = int(ux.adb("shell", "date", "+%s%3N"))
                ux.launch("http://127.0.0.1:8875/" + page + query + "&repeat=" + str(attempt))
                ux.tap("Open video in new tab", timeout=25)
                inline = self.wait(lambda s: s["capturedAt"] >= after and s["duration"] > 0 and not s["fullscreen"], timeout=25)
            self.record("three consecutive popup tabs load without reusing a navigated WebView")
        if self.expect_enhanced:
            assert inline.get("probe"), "Document-start media probe missing from the popup frame"
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
        self.snapshot("entered")
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
        root, _ = ux.nodes()
        assert ux.match(root, "退出全屏") is None, "Telemetry repeatedly revealed native controls"
        self.record("native controls auto-hide while telemetry continues")
        self.touch()
        time.sleep(.4)
        root, _ = ux.nodes()
        assert ux.match(root, "退出全屏") is not None, "Single tap confirmation was cancelled"
        enhanced = ux.match(root, "切换到网页控件") is not None
        self.record("single tap reveals controls", enhanced=enhanced)
        self.snapshot("controls")
        if self.variant == "square":
            assert enhanced and self.width < self.height
            self.record("square video preserves portrait orientation")
            self.button("切换横竖屏")
            time.sleep(1)
            self.snapshot("rotated")
            assert self.width > self.height
            self.button("退出全屏")
            self.wait(lambda s: not s["fullscreen"] and s["controls"])
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
            self.button("投屏")
            ux.expect("选择要投送的内容")
            self.wait(lambda s: s["fullscreen"])
            self.snapshot("cast-sheet")
            dismiss_started = time.time()
            ux.adb("shell", "input", "keyevent", "4")
            self.wait(lambda s: s["receivedAt"] > dismiss_started + .5 and s["fullscreen"] and not s["controls"])
            ux.expect("选择要投送的内容", present=False)
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

        self.button("播放速度 1×")
        self.button("1.5×", reveal=False)
        self.wait(lambda s: s["rate"] == 1.5)
        ux.expect("1.5×", present=False)
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
        ux.launch()
        # Receiving a queued background event is not evidence of the resumed page.
        # Use the emulator clock so host/device skew cannot admit an older snapshot.
        resumed_after = int(ux.adb("shell", "date", "+%s%3N"))
        resumed = self.wait(lambda s: s["capturedAt"] >= resumed_after and s["rate"] == 1.5)
        self.snapshot("resumed")
        self.record("backgrounding cancels temporary speed before returning", fullscreen=resumed["fullscreen"])
        if not resumed["fullscreen"]:
            assert resumed["controls"] and self.brightness() == original_brightness
            self.enter_fullscreen()
            self.wait(lambda s: not s["controls"])
            self.snapshot("reentered")
        else:
            self.button("退出全屏")
            self.wait(lambda s: not s["fullscreen"] and s["controls"])
            self.enter_fullscreen()
            self.wait(lambda s: not s["controls"] and not s["paused"])
            self.snapshot("reentered")
        self.record("fullscreen handoff works again after backgrounding")

        if self.variant == "blob":
            for _ in range(3):
                self.button("退出全屏")
                self.wait(lambda s: not s["fullscreen"] and s["controls"])
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
        command = ["media", "volume"] if int(self.sdk) < 30 else ["cmd", "media_session", "volume"]
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
                gesture_root, _ = ux.nodes()
                if ux.match(gesture_root, "锁定屏幕") is not None:
                    break
                assert ux.match(gesture_root, "解锁屏幕") is not None, "Edge gesture unexpectedly exited fullscreen"
            self.wait(lambda s: s["fullscreen"])
            assert ux.match(gesture_root, "锁定屏幕") is not None, "System Back did not unlock the player"
            self.record("system edge Back gesture unlocks without exiting the fullscreen video")

        self.button("切换到网页控件")
        self.wait(lambda s: s["controls"])
        self.button("切换到增强控件")
        self.wait(lambda s: not s["controls"])
        self.record("webpage and enhanced controls can be switched")
        self.button("退出全屏")
        self.wait(lambda s: not s["fullscreen"] and s["controls"])
        time.sleep(1)
        size = self.snapshot("exited")
        assert size == original_size, (size, original_size)
        assert self.brightness() == original_brightness
        self.record("exit restores orientation, brightness and webpage controls")

    def save(self, error=None):
        value = {"serial": self.serial, "sdk": self.sdk, "case": self.case,
                 "apkSha256": self.apk_hash,
                 "checks": self.checks, "error": error, "lastPlayback": self.events()[-1:]}
        (self.output / ("api" + self.sdk + "-" + self.variant + ".json")).write_text(
            json.dumps(value, ensure_ascii=False, indent=2))


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
