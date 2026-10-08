#!/usr/bin/env python3
"""Save a file the page built in memory (a blob: download) and verify the bytes on disk.

The fixture reads its own body, shows its own progress, then clicks an anchor at a blob: address.
Nothing can fetch that address, so the only real question is whether the bytes still become a file;
the page half of that conversation has its own Node tests (validation/blob-download.test.cjs).
"""
import argparse
import hashlib
import importlib.util
import json
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ux", ROOT / "emulator-ux.py")
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)

CHUNK = 1 << 20
# download-test.bin is bytes(range(256)) * 256 * 96 = 6 MiB, so this crosses six slices.
SOURCE_SIZE = 6 * 1024 * 1024
SOURCE_HASH = hashlib.sha256(bytes(range(256)) * (256 * 96)).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    assert args.serial.startswith("emulator-"), "Use a dedicated emulator"
    ux.ADB = ["adb", "-s", args.serial]
    args.output.mkdir(parents=True, exist_ok=True)

    apk = ux.adb("shell", "pm", "path", ux.PACKAGE).partition(":")[2].strip()
    result = {"passed": False, "apkSha256": ux.adb("shell", "sha256sum", apk).split()[0], "checks": []}
    base = "http://127.0.0.1:8875/"
    key = "blob-" + str(time.time_ns())

    def record(name, **details):
        result["checks"].append({"check": name, **details})
        print("PASS", name, json.dumps(details, ensure_ascii=False), flush=True)
        (args.output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")

    def save(name):
        (args.output / (name + ".xml")).write_text(ux.nodes()[1], encoding="utf-8")
        (args.output / (name + ".png")).write_bytes(subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"]))

    def wait(condition, timeout=25):
        deadline = time.monotonic() + timeout
        latest = None
        while time.monotonic() < deadline:
            latest = condition()
            if latest:
                return latest
            time.sleep(.2)
        raise AssertionError("Timed out waiting for " + repr(condition))

    def shown_texts():
        return [n.get("text", "") for n in ux.nodes()[0].iter("node") if ux.visible(n)]

    def build():
        """Press the fixture. The page reads its own body, then asks to save what it built."""
        ux.tap("Build file then save (blob)")

    def confirm(filename):
        """Wait for the confirmation, which has to name the file the page produced."""
        deadline = time.monotonic() + 20
        while True:
            root, raw = ux.nodes()
            node = next((n for n in root.iter("node") if ux.visible(n)
                         and n.get("text") in ("下载", "下載", "Download")), None)
            if node is not None:
                shown = [n.get("text", "") for n in root.iter("node") if ux.visible(n)]
                assert filename in shown, "The confirmation did not name the page file: " + repr(shown)
                return node, shown, raw
            assert time.monotonic() < deadline, "Page-file confirmation missing: " + raw
            time.sleep(.3)

    def on_disk(name, expected_hash, expected_size):
        path = "/sdcard/Download/" + name

        def matches():
            check = subprocess.run(ux.ADB + ["shell", "sha256sum", path],
                                   capture_output=True, text=True, encoding="utf-8")
            return check.returncode == 0 and check.stdout.split()[0] == expected_hash

        wait(matches, timeout=90)
        size = int(ux.adb("shell", "stat", "-c", "%s", path).strip())
        assert size == expected_size, (size, expected_size)
        return path

    try:
        ux.adb("reverse", "tcp:8875", "tcp:8875")
        ux.adb("shell", "am", "force-stop", ux.PACKAGE)
        ux.launch(base + "blob-download-fixture.html?case=" + key)
        ux.expect("Build file then save (blob)")

        build()
        save("page-produced")
        first_name = key + "-1.bin"
        node, shown, _ = confirm(first_name)
        # The dialog has to describe the file: the page-reported name and size, not "unknown".
        assert any(("6" in text and "MB" in text) or "6291456" in text for text in shown), shown
        save("confirmation")
        record("The confirmation names the page-built file and its page-reported size",
               filename=first_name, dialog=shown)

        ux.tap_node(node)
        first = on_disk(first_name, SOURCE_HASH, SOURCE_SIZE)
        save("saved")
        record("A page-built file saved with the exact bytes across several slices",
               file=first, sha256=SOURCE_HASH, bytes=SOURCE_SIZE, slices=-(-SOURCE_SIZE // CHUNK))

        # A second file in the same document: the channel has to be reusable and the first transfer
        # has to have released its one in-flight slot.
        build()
        second_name = key + "-2.bin"
        node, _, _ = confirm(second_name)
        ux.tap_node(node)
        second = on_disk(second_name, SOURCE_HASH, SOURCE_SIZE)
        record("A second page-built file from the same document saves as well", file=second)

        # The completed rows must not offer a resume that cannot exist.
        ux.menu_item("下载")
        ux.expect("下载管理")
        ux.expect("继续下载", present=False)
        save("downloads-list")
        record("Completed page files offer no resume action", rows=[first_name, second_name])
        # Leave the app on the page, not inside a sheet: the next stage opens the menu from the
        # toolbar, which an open sheet covers. Downloads was opened from the menu, so walking back
        # can pass through it; the page is what says the stack is empty.
        for _ in range(3):
            if "Build file then save (blob)" in shown_texts():
                break
            ux.adb("shell", "input", "keyevent", "4")
            time.sleep(.8)
        ux.expect("Build file then save (blob)")
        # Inside the try, not a finally: the artifact is what a reader checks to see whether the
        # stage passed, and the sibling stage sets it the same way.
        result["passed"] = True
    except Exception as error:
        result["error"] = repr(error)
        raise
    finally:
        (args.output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
