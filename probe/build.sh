#!/bin/sh
# Builds the probe APK with the Debian/Ubuntu Android tools (no Gradle/SDK manager needed).
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/classes
aapt package -f -M AndroidManifest.xml -I "$SDK" -F build/unsigned.apk
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$SDK" -d build/classes $(find src -name '*.java')
dalvik-exchange --dex --min-sdk-version=26 --output=build/classes.dex build/classes
(cd build && cp classes.dex classes.dex.tmp && mv classes.dex.tmp classes.dex && aapt add unsigned.apk classes.dex >/dev/null)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias probe -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=N6 Probe" >/dev/null 2>&1
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android --out n6-hinge-probe.apk build/aligned.apk
apksigner verify n6-hinge-probe.apk && ls -la n6-hinge-probe.apk
