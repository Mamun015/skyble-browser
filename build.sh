#!/bin/bash
# Build the Skyble Browser APK without Gradle / Android Studio.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$PWD/sdk}"
AJ="$SDK/android.jar"
R8="$SDK/r8.jar"
# F-Droid's build server has no vendored jars: fall back to the SDK layout there.
if [ ! -f "$AJ" ] && [ -n "$ANDROID_HOME" ]; then
  AJ="$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
if [ ! -f "$R8" ] && [ -n "$ANDROID_HOME" ]; then
  R8="$(ls -d "$ANDROID_HOME"/build-tools/*/lib/d8.jar 2>/dev/null | sort -V | tail -1)"
fi
OUT="$PWD/build"
NAME="SkybleBrowser-1.3.1.apk"

# On the F-Droid build server the SDK tools are not on PATH by default.
if ! command -v aapt >/dev/null 2>&1 && [ -n "$ANDROID_HOME" ]; then
  BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  [ -n "$BT" ] && export PATH="$BT:$PATH"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 1/6 resources"
aapt package -f -m \
  -J "$OUT/gen" \
  -M AndroidManifest.xml \
  -S res \
  -A assets \
  -I "$AJ" \
  --min-sdk-version 21 --target-sdk-version 34 \
  -F "$OUT/base.apk"

echo "==> 2/6 compile java"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -source 11 -target 11 -classpath "$AJ:libs/*" -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
if [ $? -ne 0 ]; then
  grep -v "bootstrap class path" "$OUT/javac.log"
  echo "!! compile failed"
  exit 1
fi

echo "==> 3/6 dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$R8" com.android.tools.r8.D8 \
  --min-api 21 --lib "$AJ" --output "$OUT/dex" libs/*.jar @"$OUT/classes.txt" 2>&1 | grep -v "^Warning" || true

echo "==> 4/6 pack"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 5/6 sign"
if [ "${UNSIGNED:-0}" = "1" ]; then
  # F-Droid builds from source and signs with its own key.
  echo "unsigned build: $OUT/aligned.apk"
  exit 0
fi
bash sign.sh "$OUT/aligned.apk" "$NAME"

echo "==> 6/6 verify"
apksigner verify --print-certs "$NAME" | head -5
ls -lh "$NAME"
