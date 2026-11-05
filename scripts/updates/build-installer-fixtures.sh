#!/usr/bin/env bash
# Disposable emulator fixtures only. No key leaves RUNNER_TEMP, no production credentials.
set -euo pipefail
: "${ANDROID_HOME:?}" "${RUNNER_TEMP:?}"
if env | grep -q '^CINEMATV_KEYSTORE_\|^CINEMATV_KEY_PASSWORD=\|^CINEMATV_KEY_ALIAS='; then
  echo 'Refusing fixture build with external signing credentials configured'; exit 1
fi
OUT="$RUNNER_TEMP/update-fixtures"
KEYDIR="$RUNNER_TEMP/update-test-keys"
mkdir -p "$OUT" "$KEYDIR"
chmod 700 "$KEYDIR"
trap 'rm -rf "$KEYDIR"' EXIT
SDK="$ANDROID_HOME/build-tools/36.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
for key in same wrong; do
  keytool -genkeypair -keystore "$KEYDIR/$key.p12" -storetype PKCS12 -storepass test-only-password \
    -alias test-only -keypass test-only-password -keyalg RSA -keysize 2048 -validity 2 \
    -dname 'CN=TEST ONLY disposable CI fixture,OU=Not for distribution,O=CinemaTV Tests,C=ZZ'
done
sign() {
  "$SDK/apksigner" sign --ks "$KEYDIR/$1.p12" --ks-pass pass:test-only-password \
    --key-pass pass:test-only-password --ks-key-alias test-only --out "$3" "$2"
  "$SDK/apksigner" verify --verbose "$3"
}
for spec in dev:100:base stable:101:stable dev:102:dev; do
  IFS=: read -r channel code name <<< "$spec"
  gradle --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
    -PcinematvChannel="$channel" -PcinematvVersionCode="$code" \
    -PcinematvVersionName="0.0.0-$name.TEST-ONLY" :app:assembleRelease
  APK=app/build/outputs/apk/release/app-release-unsigned.apk
  test -f "$APK"
  sign same "$APK" "$OUT/$name.apk"
  if [ "$name" = dev ]; then sign wrong "$APK" "$OUT/wrong-key.apk"; fi
done
mkdir -p "$OUT/classes" "$OUT/dex"
javac -source 17 -target 17 -classpath "$ANDROID_JAR" -d "$OUT/classes" scripts/updates/installer-probe/UpgradeProbe.java
"$SDK/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" "$OUT/classes/dev/cinematv/updateprobe/UpgradeProbe.class"
"$SDK/aapt2" link -I "$ANDROID_JAR" --manifest scripts/updates/installer-probe/AndroidManifest.xml -o "$OUT/probe-unaligned.apk"
(cd "$OUT/dex" && zip -q "$OUT/probe-unaligned.apk" classes.dex)
"$SDK/zipalign" -p 4 "$OUT/probe-unaligned.apk" "$OUT/probe-unsigned.apk"
sign same "$OUT/probe-unsigned.apk" "$OUT/probe.apk"
(cd "$OUT" && sha256sum base.apk stable.apk dev.apk wrong-key.apk probe.apk > SHA256SUMS.txt)
rm -rf "$OUT/classes" "$OUT/dex" "$OUT/probe-unaligned.apk" "$OUT/probe-unsigned.apk"
