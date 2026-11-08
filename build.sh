#!/usr/bin/env bash
# Builds a signed APK without Gradle, using only the Android platform jar and build tools.
#   Needs: aapt2, javac (JDK 11+), dx/d8, zipalign, apksigner
#   Usage: ANDROID_JAR=/path/to/android-35/android.jar ./build.sh
#   With an old aapt2 (e.g. Ubuntu's), also set RES_JAR=/path/to/android-34/android.jar
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/opt/asdk/android.jar}          # compile Java against API 35
RES_JAR=${RES_JAR:-$ANDROID_JAR}                          # link resources (older aapt2 needs API <= 34)
DX=${DX:-$(command -v dalvik-exchange || command -v dx)}
VERSION_CODE=20
VERSION_NAME=2.9
OUT=build
SRC=app/src/main

rm -rf "$OUT" && mkdir -p "$OUT"/{gen,classes,dex}

echo "▸ Compiling resources"
# Gradle supplies the package via `namespace`; aapt2 needs it in the manifest.
sed '0,/<manifest /s//<manifest package="com.statusvault.app" /' "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt2 compile --dir "$SRC/res" -o "$OUT/res.zip"
aapt2 link -o "$OUT/base.apk" -I "$RES_JAR" \
  --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  "$OUT/res.zip"

echo "▸ Compiling Java"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find "$SRC/java" "$OUT/gen" -name '*.java')

echo "▸ Dexing"
"$DX" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes"

echo "▸ Packaging"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "▸ Signing"
KS=signing/statusvault.jks
if [ ! -f "$KS" ]; then
  mkdir -p signing
  keytool -genkeypair -keystore "$KS" -storepass statusvault -keypass statusvault \
    -alias statusvault -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Status Vault" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:statusvault --key-pass pass:statusvault \
  --out "StatusVault-v$VERSION_NAME.apk" "$OUT/aligned.apk"
apksigner verify "StatusVault-v$VERSION_NAME.apk"
echo "✓ StatusVault-v$VERSION_NAME.apk ($(du -h "StatusVault-v$VERSION_NAME.apk" | cut -f1))"
