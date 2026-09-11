#!/usr/bin/env python3
"""Exercise the system camera Activity and consume the captured bytes in a real web form."""
import argparse, importlib.util, json, subprocess, time, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('ux',ROOT/'emulator-ux.py')
ux=importlib.util.module_from_spec(spec);spec.loader.exec_module(ux)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True)
    p.add_argument('--package',default='com.mybrowser.debug');p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();assert a.serial.startswith('emulator-')
    ux.ADB=['adb','-s',a.serial];ux.PACKAGE=a.package;a.output.mkdir(parents=True,exist_ok=True)
    key='capture-'+str(time.time_ns());base='http://127.0.0.1:8875/'
    rotation=ux.adb('shell','settings','get','system','user_rotation')
    auto=ux.adb('shell','settings','get','system','accelerometer_rotation')
    checks=[]
    def events(): return json.load(urllib.request.urlopen(base+'__state?case='+key,timeout=4))
    def tap_id(suffix,timeout=10):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            root,_=ux.nodes()
            n=next((n for n in root.iter('node') if ux.visible(n) and n.get('resource-id','').endswith('/'+suffix)),None)
            if n is not None:
                # A permission sheet can still be animating after its nodes appear.
                # Re-resolve its current bounds in the same helper that injects the tap.
                time.sleep(.3)
                if not ux.tap_now(n.get('resource-id')): ux.tap_node(n)
                time.sleep(.5)
                return
            time.sleep(.25)
        raise AssertionError('Missing system control: '+suffix)
    def back(): ux.adb('shell','input','keyevent','4');time.sleep(.7)
    def record(name):
        checks.append(name);print('PASS',name,flush=True)
        (a.output/'result.json').write_text(json.dumps({'passed':False,'checks':checks,'events':events()},indent=2))
    def wait(kind):
        end=time.monotonic()+15
        while time.monotonic()<end:
            rows=[r for r in events() if r.get('kind')==kind]
            if rows:return rows[-1]
            time.sleep(.3)
        raise AssertionError('No uploaded '+kind)
    try:
        ux.adb('shell','am','force-stop',a.package)
        ux.adb('shell','pm','revoke',a.package,'android.permission.CAMERA')
        ux.adb('shell','pm','clear-permission-flags',a.package,'android.permission.CAMERA','user-set','user-fixed')
        ux.adb('reverse','tcp:8875','tcp:8875')
        ux.launch(base+'capture-fixture.html?case='+key)
        ux.tap('Capture photo');tap_id('permission_deny_button');ux.expect('No file selected');assert not events()
        record('Denied runtime camera permission returns to the unchanged form')
        ux.tap('Capture photo');tap_id('permission_allow_foreground_only_button');ux.expect('Shutter');back()
        ux.expect('No file selected');assert not events();record('Cancelling the system photo camera does not select a file')
        ux.tap('Capture photo');tap_id('shutter_button');tap_id('done_button')
        photo=wait('photo');assert photo['type']=='image/jpeg' and photo['bytes']==photo['size'] and photo['head'][:2]==[255,216]
        record('Camera JPEG returns through FileProvider and is read fully by the webpage')
        ux.tap('Capture video');tap_id('shutter_button');time.sleep(2);tap_id('shutter_button');tap_id('done_button')
        video=wait('video');assert video['type']=='video/mp4' and video['bytes']==video['size'] and video['size']>1024
        assert bytes(video['head'][4:8])==b'ftyp';record('Recorded MP4 is returned and readable by the webpage')
        count=len(events());ux.tap('Capture photo');ux.expect('Shutter')
        ux.adb('shell','settings','put','system','accelerometer_rotation','0')
        ux.adb('shell','settings','put','system','user_rotation','1');time.sleep(1);back();ux.expect('Capture photo')
        assert len(events())==count;record('Rotation while the camera owns the result can be cancelled without a stale upload')
        ux.adb('shell','settings','put','system','user_rotation',rotation)
        ux.adb('shell','settings','put','system','accelerometer_rotation',auto);time.sleep(.7)
        ux.tap('Capture photo');ux.expect('Shutter')
        ux.launch(base+'capture-fixture.html?case='+key+'&replacement=1');ux.expect('No file selected')
        assert len(events())==count;record('Navigating while capture is pending cancels the old document callback')
        (a.output/'result.json').write_text(json.dumps({'passed':True,'checks':checks,'events':events()},indent=2))
    finally:
        ux.adb('shell','settings','put','system','user_rotation',rotation)
        ux.adb('shell','settings','put','system','accelerometer_rotation',auto)
        (a.output/'last-screen.png').write_bytes(subprocess.check_output(ux.ADB+['exec-out','screencap','-p'],timeout=20))
        (a.output/'last-screen.xml').write_text(ux.nodes()[1])
if __name__=='__main__':main()
