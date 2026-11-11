"""Exercise the real debug APK on a disposable Android emulator (no login/game launch)."""
import glob
import re
import subprocess
import time
from pathlib import Path
import xml.etree.ElementTree as ET
from ui_rotation import lock_rotation

PACKAGE='com.gurkha.launcher.debug'
OUT=Path('ui-smoke'); OUT.mkdir(exist_ok=True)
def adb(*args, binary=False):
    return subprocess.check_output(['adb',*args],text=not binary,stderr=subprocess.STDOUT,timeout=120)
def dump():
    # UiAutomation may have no root while Splash/rotation is replacing the window.
    error = None
    for attempt in range(8):
        try:
            adb('shell','uiautomator','dump','/sdcard/gurkha-ui.xml')
            text=adb('shell','cat','/sdcard/gurkha-ui.xml')
            root=ET.fromstring(text)
            (OUT/'last-hierarchy.xml').write_text(text)
            return root
        except (subprocess.CalledProcessError, ET.ParseError) as exc:
            error=exc
            time.sleep(2)
    raise RuntimeError('Cannot capture UI hierarchy: '+str(error)+' '+str(getattr(error,'output','')))

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
        # First fullscreen use on a real phone profile shows Android's own tutorial.
        if any(n.get('resource-id')=='android:id/immersive_cling_title' for n in root.iter('node')):
            button=next((n for n in root.iter('node') if n.get('resource-id')=='android:id/ok'),None)
            if button is not None: tap(button); continue
        dismiss=find(root,'notice_got_button')
        if dismiss is not None: tap(dismiss); continue
        node=find(root,name)
        if node is not None:return root,node
        start=find(root,'start_button')
        if start is not None and start.get('clickable')=='true':tap(start)
        time.sleep(2)
    raise AssertionError('Control did not appear: '+name)
def wait_portrait_home():
    # Splash forces landscape. Freeze again AFTER it has finished, otherwise its
    # pending rotation can invalidate coordinates captured on the first home frame.
    wait('home_button',180)
    lock_rotation(adb,0)
    deadline=time.monotonic()+60
    consecutive=0
    while time.monotonic()<deadline:
        root,_=wait('home_button')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',find(root,'container_fragment').get('bounds')))
        consecutive=consecutive+1 if y2-y1>x2-x1 else 0
        if consecutive>=2:return root
        time.sleep(1)
    raise AssertionError('Launcher did not settle in portrait after Splash')

def scroll_to(name, scroll_id, attempts=8):
    for attempt in range(attempts):
        root=dump()
        node=find(root,name)
        if node is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            if x2>x1 and y2-y1>=30:return node
        scroll=find(root,scroll_id)
        assert scroll is not None,'Missing scroll view: '+scroll_id
        x1,y1,x2,y2=map(int,re.findall(r'\d+',scroll.get('bounds')))
        x=(x1+x2)//2
        adb('shell','input','swipe',str(x),str(y1+(y2-y1)*4//5),str(x),str(y1+(y2-y1)//5),'350')
        time.sleep(0.5)
    raise AssertionError('Cannot scroll to '+name)

def screenshot(name):
    (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
    (OUT/(name+'.xml')).write_text(ET.tostring(dump(),encoding='unicode'))
try:
    apks=glob.glob('ui-apk/**/*.apk',recursive=True);assert len(apks)==1
    adb('install','-g',apks[0])
    adb('shell','wm','size','720x1280');adb('shell','wm','density','240')
    adb('shell','settings','put','system','accelerometer_rotation','0')
    adb('shell','wm','set-user-rotation','lock','0')
    adb('shell','input','keyevent','82')
    adb('shell','monkey','-p',PACKAGE,'-c','android.intent.category.LAUNCHER','1')
    # Monkey thaws display rotation on exit; restore the test's portrait lock.
    adb('shell','wm','set-user-rotation','lock','0')
    root=wait_portrait_home()
    assert find(root,'play_button') is not None,'Primary action missing from portrait home'
    screenshot('portrait-home')
    root,node=wait('versions_button');tap(node);root,_=wait('install_new')
    assert find(root,'create_path_button') is not None,'Profile-path actions missing'
    screenshot('portrait-versions')
    root,node=wait('setting_button');tap(node);root,_=wait('home_button')
    tap(find(root,'settings_button'));root,_=wait('tab_launcher')
    tap(find(root,'tab_launcher'));wait('low_end_mode');screenshot('portrait-settings')
    root,_=wait('tab_game');tap(find(root,'tab_game'))
    deadline=time.monotonic()+30
    while True:
        root,node=wait('tab_game')
        if node.get('selected')=='true':break
        if time.monotonic()>deadline:raise AssertionError('Game settings tab did not become selected')
        time.sleep(0.5)
    # Recreate while settings are open; the selected tab and child fragments must survive.
    adb('shell','settings','put','system','font_scale','1.3')
    lock_rotation(adb,1)
    deadline=time.monotonic()+45
    while True:
        root,node=wait('tab_game')
        bounds=find(root,'container_fragment').get('bounds')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',bounds))
        if x2-x1>y2-y1: break
        if time.monotonic()>deadline: raise AssertionError('Emulator did not rotate to landscape: '+bounds)
        time.sleep(2)
    assert node.get('selected')=='true','Selected settings page lost on recreation'
    screenshot('landscape-settings-large-text')
    # Header's home button remains available on all secondary screens.
    root,node=wait('setting_button');tap(node);root,_=wait('home_button')
    screenshot('landscape-home-large-text')
    root,node=wait('tools_button');tap(node);wait('share_logs_button');screenshot('tools')
    tap(scroll_to('wardrobe_button','home_scroll'))
    wait('wardrobe_title');screenshot('wardrobe')
    scroll_to('import_skin','wardrobe_scroll')
    scroll_to('import_cape','wardrobe_scroll')
    screenshot('wardrobe-import-actions')
    adb('shell','input','keyevent','4');wait('home_button')
    # Exercise production capture/encoder/muxer on both actual Android surface types.
    # The shell-only Activity exists in debug builds only and draws its own synthetic game.
    for mode in ('surface','texture'):
        result_path='files/media-smoke-'+mode+'.txt'
        adb('shell','run-as',PACKAGE,'rm','-f',result_path)
        adb('shell','am','start','-W','-n',PACKAGE+'/com.movtery.zalithlauncher.feature.media.MediaSmokeActivity','--es','surface',mode)
        deadline=time.monotonic()+45
        result=''
        while time.monotonic()<deadline:
            try:
                result=adb('shell','run-as',PACKAGE,'cat',result_path).strip()
                if result:break
            except subprocess.CalledProcessError:
                pass
            time.sleep(1)
        (OUT/('recording-'+mode+'.txt')).write_text(result)
        assert result.startswith('PASS '),mode+' recorder: '+(result or 'no result before deadline')
        screenshot('recording-'+mode+'-opaque-overlay')
        adb('shell','input','keyevent','4');wait('home_button')
    # Application manifest isolates the UI in its named :launcher process.
    pid=adb('shell','pidof',PACKAGE+':launcher').strip()
    assert pid,'Launcher process died'
    logs=adb('logcat','-d','--pid='+pid,'-s','AndroidRuntime:E')
    assert 'FATAL EXCEPTION' not in logs,logs[-3000:]
    (OUT/'RESULT.txt').write_text('PASS: first-run setup, portrait home, settings, tab preservation across size/font recreation, home navigation, tools, wardrobe entry and actual surface-only MP4 capture/decode on SurfaceView and TextureView. Android API 29 x86_64 emulator; not a Helio A22 benchmark or game test.\n')
    print('::notice title=Native UI smoke test::'+(OUT/'RESULT.txt').read_text().strip())
except Exception as error:
    message=str(error)+' '+str(getattr(error,'output',''))
    displays=adb('shell','dumpsys','window','displays')
    details='\n'.join(l for l in displays.splitlines() if any(k in l for k in ('rotation','Rotation','DisplayInfo','mBaseDisplay','mInitialDisplay')))
    print('::error title=Emulator display state::'+details[-3500:].replace('%','%25').replace('\n','%0A'))
    print('::error title=Native UI test failure::'+message[-4000:].replace('%','%25').replace('\n','%0A'))
    raise
finally:
    (OUT/'logcat.txt').write_text(adb('logcat','-d','-t','2500'))
