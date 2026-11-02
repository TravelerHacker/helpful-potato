#!/bin/bash
# Headless build: javac + d8 + aapt2 + apksigner (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
# Configure via env: ANDROID_SDK (or ANDROID_HOME), BUILD_TOOLS_VERSION, PLATFORM, JAVA_HOME.
SDK=${ANDROID_SDK:-${ANDROID_HOME:-$HOME/android-sdk}}
BT=$SDK/build-tools/${BUILD_TOOLS_VERSION:-37.0.0}; JAR=$SDK/platforms/${PLATFORM:-android-37.0}/android.jar
if [ -n "${JAVA_HOME:-}" ]; then export PATH=$JAVA_HOME/bin:$PATH; fi
rm -rf build && mkdir -p build/classes build/dex build/res
$BT/aapt2 compile --dir res -o build/res/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -o build/base.apk build/res/res.zip --java build/gen --auto-add-overlay
javac -nowarn -source 17 -target 17 -encoding UTF-8 -cp "$JAR:libs/usb-serial-3.10.0.jar" -d build/classes $(find src build/gen -name '*.java')
$BT/d8 --min-api 31 --lib $JAR --output build/dex $(find build/classes -name '*.class') libs/usb-serial-3.10.0.jar 2>&1 | grep -v -i "androidx.annotation" || true
cp build/base.apk build/unsigned.apk
python3 -c "import zipfile,glob,os; z=zipfile.ZipFile('build/unsigned.apk','a'); [z.write(f, os.path.basename(f)) for f in glob.glob('build/dex/classes*.dex')]; z.close()"
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
# Signing keystore (never commit it). A throwaway debug key is generated if missing.
KS=${KEYSTORE:-debug.keystore}
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass android -keypass android -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PhoneHands" >/dev/null 2>&1
$BT/apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android --out build/phone-hands-hid.apk build/aligned.apk
echo "built $(pwd)/build/phone-hands-hid.apk"
