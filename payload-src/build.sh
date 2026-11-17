#!/bin/sh
# 重新生成 app/assets/payload/inject-enable.py：
#   EnableProbe.java -> javac -> d8 -> 换进 inject.py 的副本（骨架不动，只换 dex/类名）。
# 依赖：JDK 17（javac）、Android SDK 的 android-36 与 d8。
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$HERE")
JAVA_HOME=${JAVA_HOME:-$HOME/android-tools/jdk/Contents/Home}
SDK=${ANDROID_HOME:-$HOME/android-tools}
ANDROID_JAR=$SDK/platforms/android-36/android.jar
BUILD=$ROOT/out/probe

[ -f "$ANDROID_JAR" ] || { echo "缺 $ANDROID_JAR" >&2; exit 1; }
D8=$(ls -d "$SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -1 || true)
[ -n "${D8:-}" ] || D8=$SDK/android-14/d8
[ -x "$D8" ] || { echo "找不到 d8（试过 $SDK/build-tools/* 和 $SDK/android-14）" >&2; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -cp "$ANDROID_JAR" \
    -d "$BUILD/classes" "$HERE/stepx/EnableProbe.java"
PATH="$JAVA_HOME/bin:$PATH" "$D8" --min-api 34 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" "$BUILD/classes/stepx/EnableProbe.class"

cd "$ROOT"
python3 "$HERE/build-payload.py" "$BUILD/dex/classes.dex" stepx.EnableProbe \
    app/assets/payload/inject-enable.py
