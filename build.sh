#!/bin/sh
# Builds the APK with the Debian/Ubuntu Android tools (no Gradle/SDK manager needed).
# Needs: apt install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
set -e
cd "$(dirname "$0")"
# Resources are linked against the API 23 platform (attribute IDs are stable); Java is compiled
# against the Android 16 framework, which includes the hidden capture APIs used by ShellService.
RES_SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
FRAMEWORK=sdk/android-all-16.jar
if [ ! -f "$FRAMEWORK" ]; then
  mkdir -p sdk
  curl -sSL -o "$FRAMEWORK" https://repo1.maven.org/maven2/org/robolectric/android-all/16-robolectric-13921718/android-all-16-robolectric-13921718.jar
fi
LIBS=$(ls libs/*.jar | tr '\n' ':')
rm -rf build && mkdir -p build/classes
aapt package -f -M AndroidManifest.xml -S res -I "$RES_SDK" -F build/unsigned.apk
javac --release 8 -Xlint:-options -cp "$FRAMEWORK:$LIBS" -d build/classes $(find src -name '*.java')
# dx can't desugar Java lambdas: Android would crash on them at runtime. Fail the build instead.
# (libs/api-desugared.jar is Shizuku's api.jar run through Retrolambda; original in tools/original-libs.)
for input in build/classes libs/*.jar; do
  if [ -d "$input" ]; then classes=$(cd "$input" && find . -name '*.class' | sed 's#^\./##; s/\.class$//; s#/#.#g'); cp="$input"
  else classes=$(unzip -Z1 "$input" | grep 'class$' | sed 's/\.class$//; s#/#.#g'); cp="$input:$LIBS"; fi
  if javap -c -p -cp "$cp" $classes 2>/dev/null | grep -q invokedynamic; then echo "ERROR: lambda bytecode in $input"; exit 1; fi
done
dalvik-exchange --dex --min-sdk-version=26 --output=build/classes.dex build/classes libs/*.jar
(cd build && aapt add unsigned.apk classes.dex >/dev/null)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias probe -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=N6 Fold Duo" >/dev/null 2>&1
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android --out n6-fold-duo.apk build/aligned.apk
apksigner verify n6-fold-duo.apk && ls -la n6-fold-duo.apk
