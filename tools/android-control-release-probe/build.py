"""Build a framework-only, same-signature instrumentation APK; never bundled into the app."""
from pathlib import Path
import os, subprocess, zipfile

root=Path(__file__).resolve().parent
repo=root.parent.parent
sdk=Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
jdk=Path(os.environ['JAVA_HOME'])
tools=sdk/'build-tools/37.0.0'
android=sdk/'platforms/android-37.0/android.jar'
output=repo/'build/control-release-probe'
classes=output/'classes';classes.mkdir(parents=True,exist_ok=True)
dex=output/'dex';dex.mkdir(exist_ok=True)
suffix='.exe' if os.name=='nt' else ''
script='.bat' if os.name=='nt' else ''
def run(*args):subprocess.run(list(map(str,args)),cwd=root,check=True)
run(jdk/f'bin/javac{suffix}','-source','8','-target','8','-encoding','UTF-8','-classpath',android,'-d',classes,root/'ControlReleaseProbe.java')
jar=output/'classes.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
    for file in classes.rglob('*.class'):z.write(file,file.relative_to(classes).as_posix())
run(tools/f'd8{script}','--lib',android,'--min-api','34','--output',dex,jar)
unsigned=output/'unsigned.apk'
run(tools/f'aapt2{suffix}','link','-I',android,'--manifest',root/'AndroidManifest.xml','-o',unsigned)
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:z.write(dex/'classes.dex','classes.dex')
aligned=output/'aligned.apk'
run(tools/f'zipalign{suffix}','-f','4',unsigned,aligned)
run(tools/f'apksigner{script}','sign','--ks',Path.home()/'.android/debug.keystore','--ks-pass','pass:android',
    '--key-pass','pass:android','--ks-key-alias','androiddebugkey','--out',output/'probe.apk',aligned)
run(tools/f'apksigner{script}','verify',output/'probe.apk')
print(output/'probe.apk')
