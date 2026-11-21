"""Exercise the already-installed signed optimized APK on the dedicated lab AVD only."""
from pathlib import Path
import os, subprocess

repo=Path(__file__).resolve().parents[2]
output=repo/'build/control-release-probe'
sdk=Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
adb=[str(sdk/('platform-tools/adb.exe' if os.name=='nt' else 'platform-tools/adb')),
     '-P','5037','-s','emulator-5556']
def run(*args):
    result=subprocess.run(adb+list(map(str,args)),capture_output=True,timeout=120)
    text=(result.stdout+result.stderr).decode('utf-8','replace')
    if result.returncode:raise RuntimeError(text)
    return text
def instrument(mode):
    result=run('shell','am','instrument','-w','-e','mode',mode,
        'me.rerere.androidcontrol.releaseprobe/me.rerere.androidcontrol.releaseprobe.ControlReleaseProbe')
    (output/f'{mode}.log').write_text(result,encoding='utf-8')
    print(result,flush=True)
    assert 'INSTRUMENTATION_RESULT: status=PASS' in result and 'INSTRUMENTATION_CODE: -1' in result

assert run('shell','getprop','ro.boot.qemu.avd_name').strip()=='StatsUsageTest', 'Refusing a non-lab device'
assert run('shell','id','-u').strip()=='2000'
assert 'Success' in run('install','-r',output/'probe.apk')
run('shell','pm','grant','me.rerere.rikkahub.debug','android.permission.POST_NOTIFICATIONS')
instrument('bootstrap')
run('shell','sh','/sdcard/Android/data/me.rerere.rikkahub.debug/files/android-control-usb-start.sh')
instrument('auth')
