#!/usr/bin/env python3
"""Capture a public page and read playback diagnostics through the release console.

This records observations, not a playback pass. Play/fullscreen actions are performed
separately through the website UI; a loaded document is not proof of playable media.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("features", ROOT / "features-regression.py")
features = importlib.util.module_from_spec(spec)
spec.loader.exec_module(features)
ux = features.ux

EXPRESSION = """JSON.stringify((()=>{const clean=s=>{try{const u=new URL(s,location.href);return u.protocol==='blob:'?'blob:':u.origin+u.pathname}catch(e){return String(s)}};const p=window.__pureBrowserVideoV2?window.__pureBrowserVideoV2.snapshot():{};return {title:document.title,url:clean(location.href),ready:document.readyState,text:document.body.innerText.slice(0,3500),fullscreen:!!document.fullscreenElement,videos:Array.from(document.querySelectorAll('video')).map(v=>({source:clean(v.currentSrc),paused:v.paused,time:v.currentTime,duration:Number.isFinite(v.duration)?v.duration:null,ready:v.readyState,width:v.videoWidth,height:v.videoHeight,controls:v.controls,error:v.error?{code:v.error.code,message:v.error.message}:null})),frames:Array.from(document.querySelectorAll('iframe')).map(f=>({url:clean(f.src),width:f.clientWidth,height:f.clientHeight})),probe:{hasVideo:p.hasVideo,playing:p.playing,fullscreen:p.fullscreen,nativeControlsAvailable:p.nativeControlsAvailable}}})())"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--url")
    parser.add_argument("--wait", type=float, default=8)
    args = parser.parse_args()
    test = features.Regression(args.serial, "media")
    stem = test.output / ("api" + test.sdk + "-online-" + args.name)
    if args.url:
        ux.launch(args.url)
    time.sleep(min(30, max(0, args.wait)))
    root, raw = ux.nodes()
    stem.with_suffix(".xml").write_text(raw)
    stem.with_suffix(".png").write_bytes(subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"]))
    result = {"serial": args.serial, "sdk": test.sdk, "apkSha256": test.apk_hash,
              "requestedUrl": args.url, "observedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
              "visibleLabels": [n.get("text") or n.get("content-desc") for n in root.iter("node")
                                if ux.visible(n) and (n.get("text") or n.get("content-desc"))]}
    ux.menu_item("开发者工具")
    test.enter_source(EXPRESSION)
    test.back()  # Dismiss the keyboard while leaving the console open.
    ux.tap("执行")
    deadline = time.monotonic() + 12
    while True:
        root, raw = ux.nodes()
        line = next((n.get("text") for n in root.iter("node")
                     if n.get("text", "").startswith("> " + EXPRESSION + "\n")), None)
        if line:
            result["page"] = json.loads(line.partition("\n")[2])
            break
        assert time.monotonic() < deadline, "Console did not return page diagnostics"
        time.sleep(.3)
    (test.output / (stem.name + "-console.xml")).write_text(raw)
    stem.with_suffix(".json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    ux.tap("关闭")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
