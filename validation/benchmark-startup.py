#!/usr/bin/env python3
"""Process-cold initial display, local page ready and app/isolated renderer PSS snapshots."""
import argparse,json,re,statistics,subprocess,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True)
p.add_argument('--package',default='com.mybrowser');p.add_argument('--rounds',type=int,default=7);p.add_argument('--warmups',type=int,default=2)
p.add_argument('--modes',nargs='+',choices=['launcher','page'],default=['launcher','page']);a=p.parse_args()
assert a.serial.startswith('emulator-') and a.rounds>0 and a.warmups>=0
adb=['adb','-s',a.serial]
def run(*cmd):return subprocess.check_output(adb+list(cmd),text=True,timeout=40).strip()
def pss(pid):
    raw=run('shell','dumpsys','meminfo',str(pid));match=re.search(r'TOTAL PSS:\s*(\d+)',raw) or re.search(r'^\s*TOTAL\s+(\d+)',raw,re.M)
    return int(match[1]) if match else None
run('reverse','tcp:8875','tcp:8875')
uid=re.search(r'package:'+re.escape(a.package)+r' uid:(\d+)',run('shell','pm','list','packages','-U',a.package))[1]
values={}
for mode in a.modes:
    rows=[]
    for i in range(a.rounds+a.warmups):
        run('shell','am','force-stop',a.package);key='startup-'+str(time.time_ns())
        command=['shell','am','start','-W','-n',a.package+'/com.mybrowser.MainActivity']
        command+=['-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER'] if mode=='launcher' else ['-a','android.intent.action.VIEW','-d','http://127.0.0.1:8875/startup-fixture.html?case='+key]
        started=time.time();raw=run(*command);match=re.search(r'TotalTime:\s*(\d+)',raw)
        if not match:raise RuntimeError(raw)
        row={'displayMs':int(match[1])}
        if mode=='page':
            deadline=time.monotonic()+20
            while time.monotonic()<deadline:
                events=json.load(urllib.request.urlopen('http://127.0.0.1:8875/__state?case='+key,timeout=4))
                if events and events[-1].get('painted'):
                    row['observedPageReadyMs']=round((events[-1]['receivedAt']-started)*1000,2);break
                time.sleep(.05)
            assert 'observedPageReadyMs' in row
        time.sleep(1)
        row['appPssKb']=pss(a.package)
        try:
            isolated=json.loads(run('shell','am','get-isolated-pids',uid))
            row['isolatedProcessPssKb']={str(pid):pss(pid) for pid in isolated}
        except (ValueError,subprocess.CalledProcessError):row['isolatedProcessPssKb']=None
        if i>=a.warmups:rows.append(row);print(mode,len(rows),row,flush=True)
    values[mode]={'samples':rows,'medianDisplayMs':statistics.median(r['displayMs'] for r in rows),'medianAppPssKb':statistics.median(r['appPssKb'] for r in rows)}
    if mode=='page':values[mode]['medianObservedPageReadyMs']=statistics.median(r['observedPageReadyMs'] for r in rows)
apk=run('shell','pm','path',a.package).partition(':')[2].strip()
result={'serial':a.serial,'sdk':run('shell','getprop','ro.build.version.sdk'),'apkSha256':run('shell','sha256sum',apk).split()[0],
 'warmups':a.warmups,'modes':values,'scope':'Process cold, OS caches warm. Framework TotalTime is initial display. Page ready includes adb launch and loopback telemetry after two animation frames. Launcher follows saved startup preference. PSS is a snapshot, not peak memory. Isolated PIDs are scoped to the app UID, normally WebView renderer(s).'}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
