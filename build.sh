#!/usr/bin/env bash
# Builds StarkTV.apk with the plain Android SDK tools: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# No Gradle and no libraries: the APK stays tiny and the home screen starts fast on old TV chips.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_NAME=$(tr -d ' \n' < VERSION)
IFS=. read -r V_MAJOR V_MINOR V_PATCH <<< "$VERSION_NAME"
VERSION_CODE=$((V_MAJOR * 10000 + V_MINOR * 100 + V_PATCH))

find_sdk() {
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$d" ] && [ -d "$d/build-tools" ]; then echo "$d"; return 0; fi
  done
  return 1
}

find_java() {
  if java -version >/dev/null 2>&1; then return 0; fi
  for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /opt/homebrew/opt/openjdk; do
    if [ -x "$j/bin/java" ]; then export JAVA_HOME="$j" PATH="$j/bin:$PATH"; return 0; fi
  done
  if [ -x /usr/libexec/java_home ] && JH=$(/usr/libexec/java_home -v 17+ 2>/dev/null); then
    export JAVA_HOME="$JH" PATH="$JH/bin:$PATH"; return 0
  fi
  return 1
}

SDK=$(find_sdk) || { echo "Android SDK not found. Run: ./stark doctor"; exit 1; }
find_java || { echo "JDK 17+ not found. Run: ./stark doctor"; exit 1; }
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM="$SDK/platforms/android-34/android.jar"
[ -f "$PLATFORM" ] || PLATFORM=$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)

OUT=out
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$PLATFORM" --manifest app/AndroidManifest.xml --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A app/assets -0 mp4 -0 ttf \
  -o "$OUT/base.apk" "$OUT/res.zip"

find app/src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options -Xlint:-deprecation -nowarn \
  -bootclasspath "$PLATFORM" -classpath "$BT/core-lambda-stubs.jar" -d "$OUT/classes" @"$OUT/sources.txt"
(cd "$OUT/classes" && jar cf ../classes.jar .)
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar"

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Signing key: ECDSA P-256 / SHA-256. The password is random, generated on first build and kept out of git.
KEY_DIR=.stark
KEYSTORE="${STARK_KEYSTORE:-$KEY_DIR/release.keystore}"
PASS_FILE="${STARK_KEYSTORE_PASS_FILE:-$KEY_DIR/keystore.pass}"
mkdir -p "$KEY_DIR"
if [ ! -f "$KEYSTORE" ]; then
  umask 077
  openssl rand -base64 33 | tr -d '\n' > "$PASS_FILE"
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias stark \
    -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 10000 \
    -dname "CN=Stark-TV-OS" -storepass:file "$PASS_FILE" >/dev/null 2>&1
  echo "Created signing key $KEYSTORE (keep it: updates must be signed with the same key)"
fi

"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias stark --ks-pass "file:$PASS_FILE" \
  --out StarkTV.apk "$OUT/aligned.apk"
"$BT/apksigner" verify StarkTV.apk

echo "Built StarkTV.apk $VERSION_NAME ($(du -k StarkTV.apk | cut -f1) KB)"
