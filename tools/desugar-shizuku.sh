#!/bin/sh
# Rebuilds libs/api-desugared.jar from tools/original-libs/api.jar.
#
# Shizuku's api.jar uses Java lambdas. The Debian dx tool used by build.sh can't desugar them, and
# Android crashes on the leftover invokedynamic (NoSuchMethodError: LambdaMetafactory.metafactory).
# Retrolambda rewrites them as ordinary classes. It needs a Java 8 runtime, and its classpath must
# be class files Java 8 can read, so it uses the API 23 android.jar rather than the Android 16 one.
#
# Needs: apt install openjdk-8-jdk-headless android-sdk-platform-23
set -e
cd "$(dirname "$0")/.."
JAVA8=/usr/lib/jvm/java-8-openjdk-amd64/bin/java
[ -f tools/retrolambda.jar ] || curl -sSL -o tools/retrolambda.jar \
  https://repo1.maven.org/maven2/net/orfjackal/retrolambda/retrolambda/2.5.7/retrolambda-2.5.7.jar
WORK=$(mktemp -d)
mkdir -p "$WORK/in" "$WORK/out"
(cd "$WORK/in" && unzip -q "$OLDPWD/tools/original-libs/api.jar")
"$JAVA8" -Dretrolambda.inputDir="$WORK/in" -Dretrolambda.outputDir="$WORK/out" \
  -Dretrolambda.classpath="$WORK/in:libs/aidl.jar:libs/shared.jar:libs/provider.jar:/usr/lib/android-sdk/platforms/android-23/android.jar" \
  -javaagent:tools/retrolambda.jar -jar tools/retrolambda.jar
rm -f libs/api-desugared.jar
(cd "$WORK/out" && zip -q -r "$OLDPWD/libs/api-desugared.jar" .)
rm -rf "$WORK"
echo "Wrote libs/api-desugared.jar"
