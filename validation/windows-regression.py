#!/usr/bin/env python3
"""Two independent Android tasks, current-document ownership and private-mode gating."""
import argparse, importlib.util, json, subprocess, time, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('ux',ROOT/'emulator-ux.py');ux=importlib.util.module_from_spec(spec);spec.loader.exec_module(ux)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--package',default='com.mybrowser.debug');p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();assert a.serial.startswith('emulator-');ux.ADB=['adb','-s',a.serial];ux.PACKAGE=a.package;a.output.mkdir(parents=True,exist_ok=True)
    key='windows-'+str(time.time_ns());base='http://127.0.0.1:8875/';checks=[]
    def wait(name,since=0):
        end=time.monotonic()+15;last=None
        while time.monotonic()<end:
            rows=json.load(urllib.request.urlopen(base+'__state?case='+key,timeout=4));rows=[r for r in rows if r.get('name')==name and r['receivedAt']>since]
            if rows and rows[-1]['ready']=='complete':return rows[-1]
            time.sleep(.3)
        raise AssertionError((name,last))
    def type_url(url):
        ux.tap('Edit address');ux.adb('shell','env','CLASSPATH='+ux.UI_PROBE,'app_process','-Xusejit:false','/system/bin','com.mybrowser.validation.FastUiDump','selectAll')
        ux.adb('shell','input','text',url);ux.adb('shell','input','keyevent','66');time.sleep(.7)
    def record(name):
        checks.append(name);print('PASS',name,flush=True)
        (a.output/'result.json').write_text(json.dumps({'passed':False,'checks':checks},indent=2))
    try:
        ux.adb('reverse','tcp:8875','tcp:8875');ux.adb('shell','am','force-stop',a.package)
        ux.launch(base+'resident-fixture.html?case='+key+'&name=WindowA');first=wait('WindowA')
        ux.menu_item('Open other window');time.sleep(1)
        top=ux.adb('shell','dumpsys','activity','activities');assert 'SecondaryActivity' in top
        type_url(base+'resident-fixture.html?case='+key+'&name=WindowB');second=wait('WindowB')
        assert second['token']!=first['token'];record('Other window owns a separate Android task and page instance')
        since=time.time();ux.menu_item('Open other window');returned=wait('WindowA',since)
        assert returned['token']==first['token'];ux.expect('Resident WindowA')
        record('Returning to the first window retains its original document and URL')
        ux.menu_item('Enter incognito mode');ux.expect('Resident WindowA');ux.expect('Incognito',False)
        record('Private mode is blocked while a second regular window is open')
        ux.menu_item('Open other window');ux.expect('Resident WindowB')
        ux.menu_item('Exit browser');time.sleep(1);ux.launch();ux.expect('Resident WindowA')
        record('Closing the second window leaves the first window intact')
        (a.output/'result.json').write_text(json.dumps({'passed':True,'checks':checks,'first':first,'returned':returned},indent=2))
    finally:
        (a.output/'last-screen.png').write_bytes(subprocess.check_output(ux.ADB+['exec-out','screencap','-p'],timeout=20))
        (a.output/'last-screen.xml').write_text(ux.nodes()[1])
if __name__=='__main__':main()
