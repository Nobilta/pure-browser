#!/usr/bin/env python3
"""Compare process-cold launches on the same emulator and local page, without clearing data."""
import argparse,json,re,statistics,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--output',required=True)
p.add_argument('--rounds',type=int,default=5)
a=p.parse_args()
base=['adb','-s',a.serial]
def adb(*args): return subprocess.check_output(base+list(args),text=True,timeout=40)
values=[]
for i in range(a.rounds):
 adb('shell','am','force-stop','com.mybrowser')
 result=adb('shell','am','start','-W','-n','com.mybrowser/com.mybrowser.MainActivity','-a','android.intent.action.VIEW','-d','http://127.0.0.1:8875/browser-ux.html')
 match=re.search(r'TotalTime:\s*(\d+)',result)
 if not match: raise RuntimeError(result)
 values.append(int(match[1]))
 time.sleep(1.5)
mem=adb('shell','dumpsys','meminfo','com.mybrowser')
match=re.search(r'TOTAL PSS:\s*(\d+)',mem) or re.search(r'^\s*TOTAL\s+(\d+)',mem,re.M)
output={'serial':a.serial,'sdk':adb('shell','getprop','ro.build.version.sdk').strip(),
 'launch_ms':values,'median_ms':statistics.median(values),'app_pss_kb':int(match[1]) if match else None}
Path(a.output).parent.mkdir(parents=True,exist_ok=True)
Path(a.output).write_text(json.dumps(output,indent=2))
print(json.dumps(output,indent=2),flush=True)
