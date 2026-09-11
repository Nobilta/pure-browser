#!/usr/bin/env python3
"""Identical Android arm64 JNI fixture across z/2/3; generated APKs are overwritten."""
import argparse, hashlib, json, os, statistics, subprocess, zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--levels',nargs='+',choices=['z','2','3'],default=['z','2','3']);a=p.parse_args()
assert a.serial.startswith('emulator-')
a.output.mkdir(parents=True,exist_ok=True)
env={**os.environ,'ANDROID_HOME':'/opt/homebrew/share/android-commandlinetools','ANDROID_SDK_ROOT':'/opt/homebrew/share/android-commandlinetools'}
def run(cmd,**kwargs): return subprocess.run(cmd,cwd=root,env=env,check=True,**kwargs)
results=[]
for level in a.levels:
 with (a.output/('build-'+level+'.log')).open('w') as log:
  run(['./gradlew',':app:assembleDebug',':app:assembleDebugAndroidTest','--max-workers=2','-Pmybrowser.filterOpt='+level],stdout=log,stderr=log)
 apk=root/'app/build/outputs/apk/debug/app-debug.apk'
 run(['adb','-s',a.serial,'install','-r',str(apk)],stdout=subprocess.DEVNULL)
 run(['adb','-s',a.serial,'install','-r',str(root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')],stdout=subprocess.DEVNULL)
 output=run(['adb','-s',a.serial,'shell','am','instrument','-w','com.mybrowser.debug.test/com.mybrowser.validation.NativeFilterInstrumentation'],capture_output=True,text=True).stdout
 (a.output/('instrument-'+level+'.log')).write_text(output)
 assert 'INSTRUMENTATION_CODE: -1' in output,output
 report=json.loads(next(l.partition('report=')[2] for l in output.splitlines() if 'report=' in l))
 with zipfile.ZipFile(apk) as z:
  lib=z.getinfo('lib/arm64-v8a/libmybrowser_adblock.so')
  report.update(optLevel=level,apkBytes=apk.stat().st_size,libraryBytes=lib.file_size,compressedLibraryBytes=lib.compress_size,apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest())
 for cached in [False,True]:
  samples=[s for s in report['samples'] if s['cached']==cached]
  report['cached' if cached else 'uncached']={k:statistics.median(s[k] for s in samples) for k in ['p50Us','p95Us']}
 results.append(report)
 (a.output/'result.json').write_text(json.dumps(results,indent=2))
 print(json.dumps({k:v for k,v in report.items() if k!='samples'}),flush=True)
assert all(r['decisions']==results[0]['decisions'] for r in results)
