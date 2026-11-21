#!/usr/bin/env bash
# Сборка APK без Gradle: aapt + javac + dx + zipalign + apksigner.
#
#   ./build.sh          — обе версии
#   ./build.sh full     — «Труба», Android 8.0+
#   ./build.sh lite     — «Трубочка», Android 7.0+
#
# Нужны: JDK, python3, aapt, dalvik-exchange (dx), zipalign, apksigner и android.jar (API 34).
#   Ubuntu/Debian: sudo apt install aapt dalvik-exchange zipalign apksigner openjdk-17-jdk
#   ANDROID_JAR=/путь/к/android-34/android.jar ./build.sh
set -euo pipefail

VERSION_CODE=5
VERSION_NAME=2.3
TARGET_SDK=34
BASE_PKG=ru.gase.truba   # пакет Java-кода (R-класс)

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src"
ANDROID_JAR="${ANDROID_JAR:-$ROOT/../sdk/android-34.jar}"
DX="$(command -v dx || command -v dalvik-exchange)"
KS="${KEYSTORE:-$ROOT/release.keystore}"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass truba123 -keypass truba123 -alias truba \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Truba" > /dev/null 2>&1
fi

build() {
  local flavor=$1 app_id min_sdk apk_name
  case $flavor in
    full) app_id=$BASE_PKG;      min_sdk=26; apk_name=truba.apk ;;
    lite) app_id=$BASE_PKG.lite; min_sdk=24; apk_name=trubochka.apk ;;
    *) echo "неизвестная версия: $flavor"; exit 1 ;;
  esac
  local OUT="$ROOT/build/$flavor"
  rm -rf "$OUT" && mkdir -p "$OUT"/{gen,classes}
  echo "===== $flavor ($app_id, minSdk $min_sdk)"

  local overlay=()
  [ -f "$SRC/$flavor/AndroidManifest.xml" ] && overlay=("$SRC/$flavor/AndroidManifest.xml")
  python3 "$ROOT/tools/merge_manifest.py" "$SRC/main/AndroidManifest.xml" "${overlay[@]}" \
      "$OUT/AndroidManifest.xml" "$BASE_PKG" "$VERSION_CODE" "$VERSION_NAME"

  local res=()
  [ -d "$SRC/$flavor/res" ] && res+=(-S "$SRC/$flavor/res")   # ресурсы флейвора важнее
  res+=(-S "$SRC/main/res")

  aapt package -f -m --auto-add-overlay \
      -M "$OUT/AndroidManifest.xml" "${res[@]}" -A "$SRC/main/assets" \
      -I "$ANDROID_JAR" -J "$OUT/gen" \
      --min-sdk-version $min_sdk --target-sdk-version $TARGET_SDK \
      --rename-manifest-package "$app_id" \
      -0 arsc \
      -F "$OUT/app.unsigned.apk"

  find "$SRC/main/java" "$SRC/$flavor/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
  javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
      -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
      | grep -v '^Note:' || true
  [ -f "$OUT/classes/ru/gase/truba/MainActivity.class" ] || { echo "javac: ошибка"; exit 1; }

  "$DX" --dex --min-sdk-version=$min_sdk --output="$OUT/classes.dex" "$OUT/classes"
  (cd "$OUT" && aapt add app.unsigned.apk classes.dex > /dev/null)

  zipalign -f -p 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"
  apksigner sign --ks "$KS" --ks-pass pass:truba123 --key-pass pass:truba123 --ks-key-alias truba \
      --min-sdk-version $min_sdk --out "$ROOT/build/$apk_name" "$OUT/app.aligned.apk"
  apksigner verify "$ROOT/build/$apk_name"
  echo "Готово: build/$apk_name"
}

if [ $# -eq 0 ]; then set -- full lite; fi
for f in "$@"; do build "$f"; done
