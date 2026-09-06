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
    def __init__(self, serial, variant):
        self.serial, self.variant = serial, variant
        ux.ADB = ["adb", "-s", serial]
        self.sdk = ux.adb("shell", "getprop", "ro.build.version.sdk")
        self.case = "api" + self.sdk + "-" + variant + "-" + str(int(time.time()))
        self.output = ROOT / "results"
        self.output.mkdir(exist_ok=True)
        self.checks = []
        self.width = self.height = 0

    def record(self, name, **details):
        self.checks.append({"check": name, **details})
        print("PASS:", name, json.dumps(details, ensure_ascii=False), flush=True)

    def events(self):
        url = "http://127.0.0.1:8875/__state?case=" + urllib.parse.quote(self.case)
        return json.load(urllib.request.urlopen(url, timeout=5))

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
        root = self.controls() if reveal else ux.nodes()[0]
        node = ux.match(root, label)
        if node is None:
            raise AssertionError("Control missing: " + label)
        x1, y1, x2, y2 = ux.bounds(node)
        ux.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(.4)

    def double_tap(self, x=.5):
        command = "input tap %d %d\n" % (int(self.width * x), self.height // 2)
        subprocess.run(ux.ADB + ["shell"], input=command * 2, text=True, check=True,
                       stdout=subprocess.DEVNULL, timeout=10)
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

    def run(self):
        for port in (8875, 8876):
            ux.adb("reverse", "tcp:" + str(port), "tcp:" + str(port))
        ux.adb("shell", "am", "force-stop", "com.mybrowser")
        page = "player-cross-frame.html" if self.variant == "cross" else "player-fixture.html"
        query = "?case=" + self.case + ("&blob=1" if self.variant == "blob" else "")
        ux.launch("http://127.0.0.1:8875/" + page + query)
        inline = self.wait(lambda s: s["duration"] > 0 and not s["fullscreen"])
        original_brightness = self.brightness()
        original_size = self.snapshot("inline")
        root, _ = ux.nodes()
        play = ux.match(root, "Play fullscreen")
        if play is not None:
            x1, y1, x2, y2 = ux.bounds(play)
            x, y = (x1 + x2) // 2, (y1 + y2) // 2
        else:
            web = next(n for n in root.iter("node") if n.get("class") == "android.webkit.WebView")
            left, top, _, _ = ux.bounds(web)
            rect, scale = inline["startRect"], inline["viewport"]["dpr"]
            # The cross-origin fixture places its iframe at a reported offset.
            frame_top = inline.get("frameTop", 0)
            x = int(left + (rect["x"] + rect["width"] / 2) * scale)
            y = int(top + (frame_top + rect["y"] + rect["height"] / 2) * scale)
        ux.adb("shell", "input", "tap", str(x), str(y))
        self.wait(lambda s: s["fullscreen"] and not s["paused"])
        time.sleep(1)
        root, _ = ux.nodes()
        if ux.match(root, "Got it") is not None:
            self.button("Got it", reveal=False)
        self.snapshot("entered")
        self.record("fullscreen opened", width=self.width, height=self.height)

        time.sleep(4)
        self.touch()
        time.sleep(.4)
        root, _ = ux.nodes()
        assert ux.match(root, "退出全屏") is not None, "Single tap confirmation was cancelled"
        enhanced = ux.match(root, "切换到网页控件") is not None
        self.record("single tap reveals controls", enhanced=enhanced)
        self.snapshot("controls")
        if not enhanced:
            assert self.variant == "cross", "Main-frame enhanced controls failed"
            self.button("退出全屏")
            self.wait(lambda s: not s["fullscreen"])
            self.record("unsupported cross-origin provider preserves webpage playback")
            return
        assert self.width > self.height, "Landscape video did not rotate"
        self.record("landscape video rotates automatically")

        self.button("暂停视频")
        self.wait(lambda s: s["paused"])
        self.button("播放视频")
        self.wait(lambda s: not s["paused"])
        self.record("pause and resume retain control target")

        self.button("播放速度 1×")
        self.button("1.5×", reveal=False)
        self.wait(lambda s: s["rate"] == 1.5)
        started = time.time()
        self.swipe((.5, .5), (.5, .5), 1500)
        state = self.wait(lambda s: s["receivedAt"] > started + 1 and s["rate"] == 1.5)
        during = [s for s in self.events() if s["receivedAt"] >= started]
        assert any(s["rate"] == 2 for s in during), "Long press did not boost playback"
        assert state["defaultRate"] == 1.5
        self.record("long press boosts to 2x and release restores 1.5x")

        hold = subprocess.Popen(ux.ADB + ["shell", "input", "swipe",
            str(self.width // 2), str(self.height // 2), str(self.width // 2),
            str(self.height // 2), "3000"], stdout=subprocess.DEVNULL)
        self.wait(lambda s: s["rate"] == 2)
        ux.adb("shell", "input", "keyevent", "3")
        hold.wait(timeout=10)
        ux.launch()
        self.wait(lambda s: s["rate"] == 1.5 and s["fullscreen"])
        self.record("backgrounding cancels temporary speed before returning")

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
        ux.adb("shell", "input", "keyevent", "4")
        self.wait(lambda s: s["fullscreen"])
        self.record("lock blocks gestures and Back unlocks before exiting")

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
                 "checks": self.checks, "error": error, "lastPlayback": self.events()[-1:]}
        (self.output / ("api" + self.sdk + "-" + self.variant + ".json")).write_text(
            json.dumps(value, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--variant", choices=["standard", "blob", "cross"], default="standard")
    args = parser.parse_args()
    test = Regression(args.serial, args.variant)
    try:
        test.run()
    except Exception as error:
        test.snapshot("failure")
        test.save(str(error))
        raise
    else:
        test.save()
