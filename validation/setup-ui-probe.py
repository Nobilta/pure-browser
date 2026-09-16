#!/usr/bin/env python3
"""Build and install the optional shell UI snapshot helper on specified emulators."""
import argparse
import shutil
import subprocess
import zipfile
from pathlib import Path

from androidhost import find_tool, newest
from hostencoding import ensure_utf8

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", nargs="+")
    args = parser.parse_args()
    platforms = newest("platforms/android-*", "android.jar")
    d8 = find_tool("d8")
    if not platforms or not d8:
        parser.error("Install an Android SDK platform and build tools, then set sdk.dir or ANDROID_SDK_ROOT")
    javac = shutil.which("javac")
    if not javac:
        parser.error("javac is not on PATH; install a JDK and make it available to this shell")
    android = platforms[-1]
    output = ROOT / "validation/results/ui-probe"
    classes, dex = output / "classes", output / "dex"
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    subprocess.run([javac, "--release", "8", "-classpath", str(android), "-d", str(classes),
                    str(ROOT / "validation/FastUiDump.java")], check=True)
    subprocess.run([str(d8), "--min-api", "30", "--lib", str(android), "--output", str(dex),
                    *map(str, classes.rglob("*.class"))], check=True)
    jar = output / "pure-ui-dump.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    for serial in args.serial:
        subprocess.run(["adb", "-s", serial, "push", str(jar), "/data/local/tmp/pure-ui-dump.jar"], check=True)
        print("UI snapshot helper ready:", serial, flush=True)


if __name__ == "__main__":
    ensure_utf8()
    main()
