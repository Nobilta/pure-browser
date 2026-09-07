#!/usr/bin/env python3
"""Build and install the optional shell UI snapshot helper on specified emulators."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial", nargs="+")
args = parser.parse_args()
sdk = os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", ""))
properties = ROOT / "local.properties"
if properties.exists():
    for line in properties.read_text().splitlines():
        if line.startswith("sdk.dir="):
            sdk = line.partition("=")[2]
sdk = Path(sdk)
version = lambda path: tuple(map(int, re.findall(r"\d+", path.parent.name)))
platforms = sorted(sdk.glob("platforms/android-*/android.jar"), key=version)
compilers = sorted(sdk.glob("build-tools/*/d8"), key=version)
if not platforms or not compilers:
    parser.error("Install an Android SDK platform and build tools, then set sdk.dir or ANDROID_SDK_ROOT")
android, d8 = platforms[-1], compilers[-1]
output = ROOT / "validation/results/ui-probe"
classes, dex = output / "classes", output / "dex"
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(parents=True, exist_ok=True)
subprocess.run(["javac", "--release", "8", "-classpath", str(android), "-d", str(classes),
                str(ROOT / "validation/FastUiDump.java")], check=True)
subprocess.run([str(d8), "--min-api", "29", "--lib", str(android), "--output", str(dex),
                *map(str, classes.rglob("*.class"))], check=True)
jar = output / "pure-ui-dump.jar"
with zipfile.ZipFile(jar, "w") as archive:
    archive.write(dex / "classes.dex", "classes.dex")
for serial in args.serial:
    subprocess.run(["adb", "-s", serial, "push", str(jar), "/data/local/tmp/pure-ui-dump.jar"], check=True)
    print("UI snapshot helper ready:", serial, flush=True)
