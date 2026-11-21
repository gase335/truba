#!/usr/bin/env bash
# Сборка APK без Gradle: aapt + javac + dx + zipalign + apksigner.
# Нужны: JDK, aapt, dalvik-exchange (dx), zipalign, apksigner и android.jar (API 34).
#   Ubuntu/Debian: sudo apt install aapt dalvik-exchange zipalign apksigner openjdk-17-jdk
#   ANDROID_JAR=/путь/к/android-34/android.jar ./build.sh
set -euo pipefail

PKG=ru.gase.truba
VERSION_CODE=1
VERSION_NAME=1.0
MIN_SDK=26
TARGET_SDK=34

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build"
ANDROID_JAR="${ANDROID_JAR:-$ROOT/../sdk/android-34.jar}"
DX="$(command -v dx || command -v dalvik-exchange)"

rm -rf "$OUT" && mkdir -p "$OUT"/{gen,classes}

# Манифест без package (как требует AGP 8) — вставляем package для aapt
sed "s|<manifest |<manifest package=\"$PKG\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\" |" \
    "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "== aapt: ресурсы"
aapt package -f -m \
    -M "$OUT/AndroidManifest.xml" -S "$SRC/res" -A "$SRC/assets" \
    -I "$ANDROID_JAR" -J "$OUT/gen" \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    -0 arsc \
    -F "$OUT/app.unsigned.apk"

echo "== javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== dx"
"$DX" --dex --min-sdk-version=$MIN_SDK --output="$OUT/classes.dex" "$OUT/classes"

(cd "$OUT" && aapt add app.unsigned.apk classes.dex > /dev/null)

echo "== zipalign"
zipalign -f -p 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"

echo "== apksigner"
KS="${KEYSTORE:-$ROOT/release.keystore}"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass truba123 -keypass truba123 -alias truba \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Truba" > /dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:truba123 --key-pass pass:truba123 --ks-key-alias truba \
    --min-sdk-version $MIN_SDK --out "$OUT/truba.apk" "$OUT/app.aligned.apk"
apksigner verify "$OUT/truba.apk"
echo "Готово: $OUT/truba.apk"
