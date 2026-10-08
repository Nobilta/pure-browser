#!/usr/bin/env python3
"""Signed APK regression for blocking by hand: the long-press sheet and the element picker.

Run qa-server.py first. Uses real browser UI and local fixture telemetry, never
release-only test hooks.

What this stage is for: both gestures end in a text rule, and the engine drops a rule it
cannot read without a word — so a rule that does nothing looks exactly like a rule that
works. Every step here is therefore checked against the page afterwards, and the count the
picker promises is compared with the number of elements that actually go away.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ux", ROOT / "emulator-ux.py")
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)

ADDRESS_RULE = "http://127.0.0.1:8875/context-image.png"
HOST_RULE = "||127.0.0.1^"
ELEMENT_RULE = "127.0.0.1##.sponsor-unit"
GENERIC_RULE = "###hero-unit"
# The picker's path fallback is exercised by the rail elements: each has no usable id, and the
# class matches more elements than the picker will name by class (13), so the only answer left is
# the exact path through the tree — a selector with a combinator in it. That is the common case,
# and the one a rule check that rejected whitespace used to refuse while still hiding the element
# locally. The exact text is the fixture's own selector, so the stage matches it by shape.


class Regression:
    def __init__(self, serial):
        ux.ADB = ["adb", "-s", serial]
        self.serial = serial
        self.sdk = ux.adb("shell", "getprop", "ro.build.version.sdk")
        self.case = "api" + self.sdk + "-block-element-" + str(int(time.time()))
        self.base = "http://127.0.0.1:8875/"
        self.since = time.time()
        self.checks = []
        self.output = ROOT / "results"
        self.output.mkdir(exist_ok=True)
        ux.adb("reverse", "tcp:8875", "tcp:8875")
        apk = ux.adb("shell", "pm", "path", ux.PACKAGE).partition(":")[2].strip()
        self.apk_hash = ux.adb("shell", "sha256sum", apk).split()[0]

    # --- plumbing ---------------------------------------------------------

    def record(self, message, **details):
        self.checks.append({"check": message, **details})
        print("PASS:", message, json.dumps(details, ensure_ascii=False), flush=True)

    def snapshot(self, name):
        stem = self.output / ("api" + self.sdk + "-block-element-" + name)
        stem.with_suffix(".xml").write_text(ux.nodes()[1], encoding="utf-8")
        stem.with_suffix(".png").write_bytes(subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"]))

    def page(self, extra=""):
        self.since = time.time()
        ux.launch(self.base + "block-element-fixture.html?case=" + self.case
                  + "&visit=" + str(time.time_ns()) + extra)

    def latest(self):
        rows = json.load(urllib.request.urlopen(self.base + "__state?case=" + self.case, timeout=5))
        current = [row for row in rows if row["receivedAt"] > self.since]
        return current[-1] if current else None

    def wait(self, predicate, timeout=20, label=""):
        deadline = time.monotonic() + timeout
        row = None
        while time.monotonic() < deadline:
            row = self.latest()
            if row and predicate(row):
                return row
            time.sleep(.4)
        raise AssertionError("Fixture condition failed" + (": " + label if label else "")
                             + " " + json.dumps(row, ensure_ascii=False))

    def press(self, target, hold_ms=0, timeout=15):
        """Press the middle of a fixture element, from the geometry the page reports.

        The picker answers `elementFromPoint`, so a press has to land on the element itself
        rather than on an accessibility node that may not exist for it at all.
        """
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            root, _ = ux.nodes()
            web = next((n for n in root.iter("node")
                        if n.get("class") == "android.webkit.WebView" and ux.visible(n)), None)
            row = self.latest()
            if web is not None and row and row.get("viewportWidth"):
                x1, y1, x2, y2 = ux.bounds(web)
                rect = row[target]
                assert rect["width"] > 0 and rect["height"] > 0, "Fixture element has no box: " + target
                scale = (x2 - x1) / row["viewportWidth"]
                x = x1 + (rect["left"] + rect["width"] / 2) * scale
                y = y1 + (rect["top"] + rect["height"] / 2) * scale
                assert x1 < x < x2 and y1 < y < y2, "Fixture element is outside the visible WebView: " + target
                x, y = str(round(x)), str(round(y))
                if hold_ms:
                    ux.adb("shell", "input", "swipe", x, y, x, y, str(hold_ms))
                else:
                    ux.adb("shell", "input", "tap", x, y)
                time.sleep(.7)
                return
            time.sleep(.2)
        raise AssertionError("No fixture geometry for " + target)

    def outside_webview(self, text):
        """Whether a native control says this, ignoring whatever the page draws itself."""
        root, _ = ux.nodes()
        web = next((n for n in root.iter("node") if n.get("class") == "android.webkit.WebView"), None)
        inside = {id(n) for n in web.iter("node")} if web is not None else set()
        wanted = ux.labels(text)
        return any(ux.visible(n) and n.get("text") in wanted
                   for n in root.iter("node") if id(n) not in inside)

    def expect_outside(self, text, present=True, timeout=8):
        deadline = time.monotonic() + timeout
        while True:
            if self.outside_webview(text) == present:
                print("PASS:", text, "shown" if present else "gone", flush=True)
                return
            assert time.monotonic() < deadline, (
                "Control %s: %s" % ("missing" if present else "still there", text))
            time.sleep(.2)

    def outside_webview_contains(self, fragment):
        """Whether a native control's text contains this fragment, ignoring the page's own drawing.

        The picker's bar shows the selector in a single-line field, so a long path selector is
        truncated on screen while its text attribute still holds all of it; the fragment is what
        can be asserted without depending on where the ellipsis lands.
        """
        root, _ = ux.nodes()
        web = next((n for n in root.iter("node") if n.get("class") == "android.webkit.WebView"), None)
        inside = {id(n) for n in web.iter("node")} if web is not None else set()
        return any(ux.visible(n) and fragment in (n.get("text") or "")
                   for n in root.iter("node") if id(n) not in inside)

    def expect_outside_contains(self, fragment, present=True, timeout=8):
        deadline = time.monotonic() + timeout
        while True:
            if self.outside_webview_contains(fragment) == present:
                print("PASS:", fragment, "shown" if present else "gone", flush=True)
                return
            assert time.monotonic() < deadline, (
                "Control %s: %s" % ("missing" if present else "still there", fragment))
            time.sleep(.2)

    def tap_bar(self, resource):
        """Tap a control of the picker's own bar: the bar is the lowest thing on screen."""
        wanted = ux.resource_labels(resource)
        root, _ = ux.nodes()
        nodes = [n for n in root.iter("node") if ux.visible(n) and n.get("content-desc") in wanted]
        assert nodes, "Picker control missing: " + resource
        ux.tap_node(max(nodes, key=lambda n: ux.bounds(n)[1]))
        time.sleep(.5)

    def open_rules(self):
        ux.open_settings("扩展")
        ux.tap("自定义广告过滤规则")
        ux.expect("广告过滤设置")

    def texts(self):
        root, _ = ux.nodes()
        return [n.get("text") for n in root.iter("node") if ux.visible(n) and n.get("text")]

    def delete_rule(self, rule):
        root, _ = ux.nodes()
        titles = [n for n in root.iter("node") if ux.visible(n) and n.get("text") == rule]
        assert titles, "Rule is not listed under My rules: " + rule
        top = ux.bounds(titles[0])[1]
        buttons = [n for n in root.iter("node") if ux.visible(n)
                   and n.get("content-desc") in ux.resource_labels("filter_user_rule_remove")]
        assert buttons, "The rule has no remove control: " + rule
        nearest = min(buttons, key=lambda n: abs(ux.bounds(n)[1] - top))
        assert abs(ux.bounds(nearest)[1] - top) < 200, "Remove control is not next to its rule: " + rule
        ux.tap_node(nearest)
        time.sleep(1.0)

    # --- stages -----------------------------------------------------------

    def element_picker(self):
        """The picker: what it selects, what it promises, and what actually goes away."""
        self.page()
        self.wait(lambda r: r["ready"] == "complete" and r["unitsVisible"] == 2
                  and r["contentVisible"] and r["heroVisible"], label="fixture page")

        ux.menu_item("Block an element")
        ux.expect("Tap the element to block", timeout=8)
        self.snapshot("picker-prompt")
        self.record("the picker bar comes up over the page")

        # A tap on an ad slot: it has no id, so the picker answers with the class — and says how
        # much of the page that covers before anything is written down.
        self.press("slotA")
        self.expect_outside(".sponsor-unit")
        self.expect_outside("2 matches")
        preview = self.wait(lambda r: r["unitsVisible"] == 0, label="the preview hides the matches")
        # The preview applies a stylesheet rule, the same mechanism the saved rule uses, so it is
        # counted there rather than as an inline declaration.
        assert preview["previewHidden"] == 2, "The preview did not hide exactly the matches it named"
        assert preview["inlineHidden"] == 0, "The preview wrote inline styles instead of the rule it promised"
        assert preview["heroVisible"] and preview["contentVisible"], "The preview took more than the matches"
        self.snapshot("picker-preview")
        self.record("choosing an element previews it on the page, hiding exactly the count it shows",
                    selector=".sponsor-unit", matches=2)

        # Up is the container, which is how one press comes to cover a whole ad region; down goes
        # back to what was pointed at.
        self.tap_bar("picker_parent")
        self.expect_outside("#sponsor-region")
        parent = self.wait(lambda r: r["regionVisible"] is False, label="the preview follows a step up")
        assert parent["unitsVisible"] == 0, "Stepping to the container left the slots showing"
        self.tap_bar("picker_child")
        self.expect_outside(".sponsor-unit")
        child = self.wait(lambda r: r["regionVisible"] and r["unitsVisible"] == 0,
                          label="the preview follows a step back down")
        assert child["previewHidden"] == 2, "Stepping back did not go back to hiding just the matches"
        self.record("the parent and child steps walk the page's own tree and re-preview each choice")

        ux.tap("Block on this site")
        self.expect_outside("Tap the element to block", present=False)
        immediate = self.wait(lambda r: r["unitsVisible"] == 0, label="the chosen elements go away")
        # Storing the rule re-injects the engine's own stylesheet, so the count may come from either
        # mechanism here; what matters is that exactly the promised two are away.
        assert immediate["previewHidden"] + immediate["inlineHidden"] >= 2, "The block did not hide exactly what was promised"
        assert immediate["heroVisible"] and immediate["contentVisible"], "Blocking took more of the page than chosen"
        self.snapshot("picker-blocked")
        # What the block itself did is proved further down, by a fresh load that hides the same
        # elements with no inline styles in sight (`inlineHidden == 0`). Here the page is already
        # hidden by the preview, so this only pins the state the user is left looking at.
        self.record("after blocking, the preview's hiding is the hiding that stays", hidden=2)

        self.open_rules()
        assert ELEMENT_RULE in self.texts(), (
            "The element rule was not stored: " + json.dumps(self.texts(), ensure_ascii=False))
        self.snapshot("rules-list")
        self.record("the element rule is stored under My rules", rule=ELEMENT_RULE)

        self.page()
        stored = self.wait(lambda r: r["ready"] == "complete" and r["unitsVisible"] == 0,
                           label="the stored rule keeps the elements away")
        assert stored["inlineHidden"] == 0, "The reload was hidden by the gesture, not by the rule"
        assert stored["heroVisible"] and stored["contentVisible"], "The stored rule hides more than it promised"
        self.snapshot("rule-applied")
        self.record("a fresh page hides them with the stored rule alone")

        self.open_rules()
        self.delete_rule(ELEMENT_RULE)
        assert ELEMENT_RULE not in self.texts(), "The rule survived removal"
        self.record("an element rule can be removed again")
        self.page()
        self.wait(lambda r: r["ready"] == "complete" and r["unitsVisible"] == 2,
                  label="elements return after the rule is removed")
        self.record("removing the element rule brings the elements back")

    def path_selector(self):
        """The fallback every element without a usable id or class goes through.

        The rail elements have no usable id and their class matches more than the picker will name
        by class, so the only answer left is the exact path through the tree — a selector with a
        combinator in it, and therefore a space in the rule text. That is one rule, not two, and
        the engine accepts it. A rule check that refused whitespace wrote nothing, hid the element
        locally anyway, and told the user no rule could be made; an element that visibly went away
        with an empty list under "My rules" is the failure this stage pins down.
        """
        self.page()
        self.wait(lambda r: r["ready"] == "complete" and r["railsVisible"] == 13,
                  label="fixture page with the rail")
        # The picker reaches what is on screen, so the rail is brought into view before it starts.
        ux.adb("shell", "input", "swipe", "206", "700", "206", "300", "400")
        time.sleep(1.0)
        on_screen = self.wait(lambda r: r["railA"]["top"] >= 0 and r["railA"]["height"] > 0
                              and r["railA"]["top"] + r["railA"]["height"] <= r["viewportHeight"],
                              label="the rail is on screen")
        ux.menu_item("Block an element")
        ux.expect("Tap the element to block", timeout=8)
        self.press("railA")
        # The bar shows the selector exactly as it will be written, spaces included: the picker
        # fell through to the path, so the text names both the element and its ancestor.
        self.expect_outside_contains("rail-unit")
        self.expect_outside_contains(" > ")
        # A unique path matches only the element that was pointed at; the other twelve stay.
        preview = self.wait(lambda r: r["previewHidden"] == 1, label="the preview hides the path match")
        assert preview["railsVisible"] == 12, "The path preview hid more than its one element"
        assert preview["heroVisible"] and preview["contentVisible"], "The path preview took more than its element"
        ux.tap("Block on this site")
        self.expect_outside("Tap the element to block", present=False)
        self.wait(lambda r: r["railA"]["height"] == 0, label="the chosen rail element goes away")

        self.open_rules()
        listed = self.texts()
        stored = [text for text in listed if "rail-unit" in text and "##" in text]
        assert stored, "No path rule was stored: " + json.dumps(listed, ensure_ascii=False)
        self.record("an element with no unique id or class is stored as its exact path", rule=stored[0])

        # And the stored rule is the rule, not the gesture: a fresh load hides the same element
        # with no inline style anywhere, which proves the whitespace-carrying text reached the
        # engine rather than being refused by the app and hidden locally.
        self.page()
        reapplied = self.wait(lambda r: r["ready"] == "complete" and r["railsVisible"] == 12,
                              label="the stored path rule hides the element")
        assert reapplied["inlineHidden"] == 0, "The reload was hidden by the gesture, not by the rule"
        assert reapplied["heroVisible"] and reapplied["contentVisible"], "The path rule hides more than promised"
        self.record("a fresh page hides the element with the stored path rule alone")

        self.open_rules()
        self.delete_rule(stored[0])
        self.record("the path rule can be removed again", rule=stored[0])
        self.page()
        self.wait(lambda r: r["ready"] == "complete" and r["railsVisible"] == 13 and r["inlineHidden"] == 0,
                  label="the rail elements return after removal")

    def picker_exits(self):
        """The two ways out of a pick that are not a block, and the id form of a selector."""
        self.wait(lambda r: r["ready"] == "complete" and r["unitsVisible"] == 2, label="fixture page")
        ux.menu_item("Block an element")
        ux.expect("Tap the element to block", timeout=8)
        self.press("hero")
        # An element carrying an id of its own is named by it, and matches once.
        self.expect_outside("#hero-unit")
        self.record("an element with a unique id is selected by that id", selector="#hero-unit")

        self.tap_bar("ui_close")
        self.expect_outside("Tap the element to block", present=False)
        state = self.wait(lambda r: r["heroVisible"] and r["unitsVisible"] == 2,
                          label="cancelling a preview puts the page back")
        assert state["inlineHidden"] == 0, "The preview's own hiding outlived the pick"
        self.record("cancelling a pick restores the preview and leaves the page alone")

        ux.menu_item("Block an element")
        ux.expect("Tap the element to block", timeout=8)
        self.press("hero")
        self.expect_outside("#hero-unit")
        ux.adb("shell", "input", "keyevent", "4")
        time.sleep(.6)
        self.expect_outside("Tap the element to block", present=False)
        state = self.wait(lambda r: r["heroVisible"] and r["unitsVisible"] == 2,
                          label="back puts the preview back")
        assert state["inlineHidden"] == 0, "The preview's own hiding outlived the pick"
        self.record("back leaves a pick without writing anything, preview included")

    def all_sites_rule(self):
        """The other scope: the same selection, written for every site.

        The element is one with an id, so the rule shows both halves of the generic form at once:
        the empty domain list of `##` and the id selector after it.
        """
        self.wait(lambda r: r["ready"] == "complete" and r["unitsVisible"] == 2, label="fixture page")
        ux.menu_item("Block an element")
        ux.expect("Tap the element to block", timeout=8)
        self.press("hero")
        self.expect_outside("#hero-unit")
        ux.tap("Block on every site")
        self.expect_outside("Tap the element to block", present=False)
        state = self.wait(lambda r: r["ready"] == "complete", label="page after a generic block")
        assert state["heroVisible"] is False, "Blocking on every site did not hide the element"
        self.record("a pick can be blocked on every site instead")
        self.open_rules()
        assert GENERIC_RULE in self.texts(), (
            "The generic rule was not stored: " + json.dumps(self.texts(), ensure_ascii=False))
        self.delete_rule(GENERIC_RULE)
        self.record("the generic rule is stored and can be removed", rule=GENERIC_RULE)
        # The generic rule reaches every site, so the fixture has to come back for what follows.
        self.page()
        self.wait(lambda r: r["ready"] == "complete" and r["heroVisible"], label="the element returns")

    def resource_blocking(self):
        """A long press on an image, and the rules the sheet says it will write."""
        self.wait(lambda r: r["ready"] == "complete" and r["imageLoaded"] and r["unitsVisible"] == 2,
                  label="fixture page")
        self.press("image", hold_ms=900)
        ux.expect("Block this address")
        self.snapshot("long-press-sheet")
        for text in (ADDRESS_RULE, HOST_RULE, "context-image.png"):
            assert self.outside_webview(text), "The sheet does not show what it would write: " + text
        self.record("a long press shows the exact rule each row would write",
                    address=ADDRESS_RULE, host=HOST_RULE)

        ux.tap("Block this address")
        time.sleep(1.2)
        self.open_rules()
        assert ADDRESS_RULE in self.texts(), (
            "The address rule was not stored: " + json.dumps(self.texts(), ensure_ascii=False))
        self.record("the address rule is stored and listed under My rules", rule=ADDRESS_RULE)

        self.page()
        self.wait(lambda r: r["ready"] == "complete" and not r["imageLoaded"], label="the blocked image request")
        time.sleep(1.5)
        settled = self.latest()
        assert not settled["imageLoaded"], "The image loaded after all"
        assert settled["unitsVisible"] == 2 and settled["contentVisible"], "Blocking the image took more than the image"
        self.record("the stored rule blocks that request on the next load")

        self.open_rules()
        self.delete_rule(ADDRESS_RULE)
        self.record("an address rule can be removed again")
        self.page()
        self.wait(lambda r: r["ready"] == "complete" and r["imageLoaded"], label="the image returns")
        self.record("removing the rule lets the image load again")

    def clear_leftovers(self):
        """Removes rules an interrupted earlier attempt left behind.

        This stage writes rules and removes them again, so a run stopped in between leaves one in
        the store — and these rules hide the very elements the next attempt presses, which reads as
        a failure of the app rather than of the harness.

        A previous stage can leave a sheet open over the toolbar, and the menu is only reachable
        from the page. Only a known sheet is dismissed; pressing back on an ordinary page would
        navigate instead, which would leave the fixture behind.
        """
        if any(text in ("下载管理", "Download manager") for text in self.texts()):
            ux.adb("shell", "input", "keyevent", "4")
            time.sleep(.8)
        self.open_rules()
        # The path rule's text is a selector the fixture decides, so it is matched by shape rather
        # than by an exact string; a half-written one from an interrupted run hides the rail.
        stale = [text for text in self.texts() if "rail-unit" in text and "##" in text]
        for rule in (ELEMENT_RULE, GENERIC_RULE, ADDRESS_RULE, *stale):
            if rule in self.texts():
                self.delete_rule(rule)
        self.page()

    def run(self):
        self.clear_leftovers()
        self.element_picker()
        self.path_selector()
        self.picker_exits()
        self.all_sites_rule()
        self.resource_blocking()

    def save(self, error=None):
        (self.output / ("api" + self.sdk + "-block-element-" + str(int(time.time())) + ".json")).write_text(
            json.dumps({"serial": self.serial, "sdk": self.sdk, "case": self.case, "apkSha256": self.apk_hash,
                        "checks": self.checks, "error": error}, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    test = Regression(args.serial)
    try:
        test.run()
    except Exception as error:
        test.save(str(error))
        raise
    test.save()
