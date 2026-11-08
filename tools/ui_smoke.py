"""Exercise the real debug APK on a disposable Android emulator (no login/game launch)."""
import glob
import re
import subprocess
import time
from pathlib import Path
import xml.etree.ElementTree as ET

PACKAGE='com.gurkha.launcher.debug'
OUT=Path('ui-smoke'); OUT.mkdir(exist_ok=True)
def adb(*args, binary=False):
    return subprocess.check_output(['adb',*args],text=not binary,timeout=120)
def dump():
    adb('shell','uiautomator','dump','/sdcard/gurkha-ui.xml')
    text=adb('shell','cat','/sdcard/gurkha-ui.xml')
    (OUT/'last-hierarchy.xml').write_text(text)
    return ET.fromstring(text)
def find(root,name):
    return next((n for n in root.iter('node') if n.get('resource-id')==f'{PACKAGE}:id/{name}'),None)
def tap(node):
    assert node is not None,'Missing control'
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    assert x2>x1 and y2>y1,'Zero-sized touch target'
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def wait(name,seconds=90):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        root=dump()
        dismiss=find(root,'notice_got_button')
        if dismiss is not None: tap(dismiss); continue
        node=find(root,name)
        if node is not None:return root,node
        start=find(root,'start_button')
        if start is not None and start.get('clickable')=='true':tap(start)
        time.sleep(2)
    raise AssertionError('Control did not appear: '+name)
def screenshot(name):
    (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
    (OUT/(name+'.xml')).write_text(ET.tostring(dump(),encoding='unicode'))
try:
    apks=glob.glob('ui-apk/**/*.apk',recursive=True);assert len(apks)==1
    adb('install','-g',apks[0])
    adb('shell','wm','size','720x1280');adb('shell','wm','density','240')
    adb('shell','settings','put','system','accelerometer_rotation','0')
    adb('shell','settings','put','system','user_rotation','0')
    adb('shell','monkey','-p',PACKAGE,'-c','android.intent.category.LAUNCHER','1')
    root,_=wait('home_button',180)
    assert find(root,'play_button') is not None,'Primary action missing from portrait home'
    screenshot('portrait-home')
    tap(find(root,'versions_button'));root,_=wait('install_new')
    assert find(root,'create_path_button') is not None,'Profile-path actions missing'
    screenshot('portrait-versions')
    root,node=wait('setting_button');tap(node);root,_=wait('home_button')
    tap(find(root,'settings_button'));root,_=wait('tab_launcher')
    tap(find(root,'tab_launcher'));wait('low_end_mode');screenshot('portrait-settings')
    root,_=wait('tab_game');tap(find(root,'tab_game'))
    # Recreate while settings are open; the selected tab and child fragments must survive.
    adb('shell','settings','put','system','font_scale','1.3')
    adb('shell','wm','size','1280x720')
    root,node=wait('tab_game')
    assert node.get('selected')=='true','Selected settings page lost on recreation'
    screenshot('landscape-settings-large-text')
    # Header's home button remains available on all secondary screens.
    root,node=wait('setting_button');tap(node);root,_=wait('home_button')
    screenshot('landscape-home-large-text')
    tap(find(root,'tools_button'));wait('share_logs_button');screenshot('tools')
    assert adb('shell','pidof',PACKAGE).strip(),'Launcher process died'
    logs=adb('logcat','-d','-s','AndroidRuntime:E')
    assert 'FATAL EXCEPTION' not in logs,logs[-3000:]
    (OUT/'RESULT.txt').write_text('PASS: first-run setup, portrait home, settings, tab preservation across size/font recreation, home navigation and tools. Android API 29 x86_64 emulator; not a Helio A22 benchmark or game test.\n')
    print('::notice title=Native UI smoke test::'+(OUT/'RESULT.txt').read_text().strip())
finally:
    (OUT/'logcat.txt').write_text(adb('logcat','-d','-t','2500'))
