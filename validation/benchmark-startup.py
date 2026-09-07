#!/usr/bin/env python3
"""Measure process-cold launches on one idle emulator and a fixed local page."""
import argparse
import json
import re
import statistics
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--rounds", type=int, default=7)
parser.add_argument("--warmups", type=int, default=2)
args = parser.parse_args()
base = ["adb", "-s", args.serial]


def adb(*command):
    return subprocess.check_output(base + list(command), text=True, timeout=40)


assert args.rounds > 0 and args.warmups >= 0
adb("reverse", "tcp:8875", "tcp:8875")
values, memory = [], []
for i in range(args.warmups + args.rounds):
    adb("shell", "am", "force-stop", "com.mybrowser")
    result = adb("shell", "am", "start", "-W", "-n", "com.mybrowser/com.mybrowser.MainActivity",
                 "-a", "android.intent.action.VIEW", "-d", "http://127.0.0.1:8875/browser-ux.html")
    match = re.search(r"TotalTime:\s*(\d+)", result)
    if not match:
        raise RuntimeError(result)
    launch_ms = int(match[1])
    time.sleep(2)
    if i >= args.warmups:
        values.append(launch_ms)
        mem = adb("shell", "dumpsys", "meminfo", "com.mybrowser")
        match = re.search(r"TOTAL PSS:\s*(\d+)", mem) or re.search(r"^\s*TOTAL\s+(\d+)", mem, re.M)
        if not match:
            raise RuntimeError(mem)
        memory.append(int(match[1]))
        print("Round", len(values), "launch_ms", launch_ms, "app_pss_kb", memory[-1], flush=True)

package = adb("shell", "dumpsys", "package", "com.mybrowser")
version = re.search(r"versionName=(\S+)", package)
output = {"serial": args.serial, "sdk": adb("shell", "getprop", "ro.build.version.sdk").strip(),
          "version": version[1] if version else None, "warmups": args.warmups,
          "launch_ms": values, "median_ms": statistics.median(values),
          "app_pss_kb_samples": memory, "app_pss_kb": statistics.median(memory),
          "memory_scope": "app process only; excludes Chromium renderer child process",
          "launch_scope": "am start -W TotalTime; process cold, OS/page caches warm"}
Path(args.output).parent.mkdir(parents=True, exist_ok=True)
Path(args.output).write_text(json.dumps(output, indent=2))
print(json.dumps(output, indent=2), flush=True)
