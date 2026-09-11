#!/usr/bin/env python3
"""Real WebView playback, lifecycle policy, PiP and Android MediaSession controls."""
import argparse, importlib.util, json, subprocess, time, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('ux',ROOT/'emulator-ux.py');ux=importlib.util.module_from_spec(spec);spec.loader.exec_module(ux)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--package',default='com.mybrowser.debug');p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();assert a.serial.startswith('emulator-');ux.ADB=['adb','-s',a.serial];ux.PACKAGE=a.package;a.output.mkdir(parents=True,exist_ok=True)
    key='system-media-'+str(time.time_ns());base='http://127.0.0.1:8875/';checks=[]
    def wait(condition,since=0):
        deadline=time.monotonic()+15;last=None
        while time.monotonic()<deadline:
            rows=json.load(urllib.request.urlopen(base+'__state?case='+key,timeout=4));last=rows[-1] if rows else None
            if last and last['receivedAt']>since and condition(last):return last
            time.sleep(.3)
        raise AssertionError(last)
    def record(name):
        checks.append(name);print('PASS',name,flush=True)
        (a.output/'result.json').write_text(json.dumps({'passed':False,'checks':checks},indent=2))
    def setting(title,value):
        ux.open_settings('视频播放');root,_=ux.nodes()
        row=next(n for n in root.iter('node') if n.get('checkable')=='true' and ux.match(n,title) is not None)
        if row.get('checked')!=str(value).lower():ux.tap_node(row)
    def page():ux.launch(base+'player-fixture.html?case='+key+'&visit='+str(time.time_ns()))
    ux.adb('reverse','tcp:8875','tcp:8875')
    try:
        ux.adb('shell','am','force-stop',a.package);page()
        setting('Allow background media',False);page();ux.tap('Play inline')
        wait(lambda r:not r['paused'] and r['currentTime']>0)
        left=time.time();ux.adb('shell','input','keyevent','3');wait(lambda r:r['paused'],left)
        record('Default background policy pauses a playing inline video when Home is pressed')
        ux.launch();ux.tap('Play fullscreen')
        # DOM fullscreen can precede the Activity's landscape/control handoff.
        ready=wait(lambda r:r['fullscreen'] and r['enhanced'] and not r['paused']
                   and r['viewport']['width']>r['viewport']['height'])
        wait(lambda r:r['fullscreen'] and r['enhanced'] and not r['paused']
             and r['viewport']==ready['viewport'] and r['capturedAt']>ready['capturedAt']+300)
        before=time.time();ux.adb('shell','input','keyevent','3')
        deadline=time.monotonic()+15
        while True:
            activity=ux.adb('shell','dumpsys','activity','activities')
            (a.output/'pip-activity.txt').write_text(activity)
            if 'mode=pinned' in activity or 'windowingMode=2' in activity or 'mWindowingMode=2' in activity:break
            assert time.monotonic()<deadline, 'Activity did not enter PiP'
            time.sleep(.3)
        wait(lambda r:not r['paused'],before)
        (a.output/'pip.png').write_bytes(subprocess.check_output(ux.ADB+['exec-out','screencap','-p'],timeout=20))
        record('Home from fullscreen enters Android PiP and keeps the existing video playing')
        ux.adb('shell','cmd','media_session','dispatch','pause');wait(lambda r:r['paused'])
        record('Android MediaSession pause reaches the selected WebView video')
        ux.launch();page();setting('Allow background media',True);page();ux.tap('Play inline')
        first=wait(lambda r:not r['paused'] and r['currentTime']>0);left=time.time()
        ux.adb('shell','input','keyevent','3');time.sleep(2)
        after=wait(lambda r:not r['paused'] and r['currentTime']>first['currentTime']+1,left)
        services=ux.adb('shell','dumpsys','activity','services',a.package);(a.output/'services.txt').write_text(services)
        assert 'MediaPlaybackService' in services and 'isForeground=true' in services
        record('Explicit background playback continues with a media foreground service')
        ux.adb('shell','cmd','media_session','dispatch','pause');wait(lambda r:r['paused'])
        record('System pause also stops explicitly enabled background playback')
        ux.launch();page();setting('Allow background media',False);page()
        (a.output/'result.json').write_text(json.dumps({'passed':True,'checks':checks,'background':after},indent=2))
    finally:
        (a.output/'last-screen.png').write_bytes(subprocess.check_output(ux.ADB+['exec-out','screencap','-p'],timeout=20))
        (a.output/'last-screen.xml').write_text(ux.nodes()[1])
if __name__=='__main__':main()
