#!/usr/bin/env bash
# Disposable emulator fixtures only. No key leaves RUNNER_TEMP, no production credentials.
set -euo pipefail
: "${ANDROID_HOME:?}" "${RUNNER_TEMP:?}"
# Inspect only variable names; never pipe/print environment values (or depend on SIGPIPE).
for variable in ${!CINEMATV_KEYSTORE_@} CINEMATV_KEY_PASSWORD CINEMATV_KEY_ALIAS; do
  if declare -p "$variable" >/dev/null 2>&1; then
    echo 'Refusing fixture build with external signing credentials configured'; exit 1
  fi
done
OUT="$RUNNER_TEMP/update-fixtures"
KEYDIR=$(mktemp -d "$RUNNER_TEMP/update-test-keys.XXXXXX")
mkdir -p "$OUT"
chmod 700 "$KEYDIR"
trap 'rm -rf "$KEYDIR"' EXIT
# Never generate even disposable test keys under a checkout or uploaded artifact folder.
python3 - "$KEYDIR" "$OUT" <<'PYKEY'
from pathlib import Path
import sys
key, out = [Path(p).resolve() for p in sys.argv[1:]]
assert not key.is_relative_to(out)
assert not any((p / '.git').is_file() or (p / '.git/HEAD').is_file() for p in [key, *key.parents])
PYKEY
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
  tasks=(:app:assembleRelease)
  acceptance=()
  if [ "$name" = base ]; then
    tasks+=(:app:assembleReleaseAndroidTest)
    acceptance+=(-PcinematvUpdateAcceptance=true)
  fi
  gradle --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
    -PcinematvChannel="$channel" -PcinematvVersionCode="$code" \
    -PcinematvVersionName="0.0.0-$name.TEST-ONLY" \
    -PcinematvUnsignedCandidate=true -PcinematvUpdatesEnabled=true "${acceptance[@]}" "${tasks[@]}"
  APK=app/build/outputs/apk/release/app-release-unsigned.apk
  test -f "$APK"
  sign same "$APK" "$OUT/$name.apk"
  if [ "$name" = base ]; then
    # Android-test packaging can be unsigned or debug-signed by AGP; explicitly re-sign it
    # with the same ephemeral certificate as the non-debug target, outside Gradle.
    mapfile -t test_apks < <(find app/build/outputs/apk/androidTest/release -maxdepth 1 -name '*.apk')
    test "${#test_apks[@]}" -eq 1
    sign same "${test_apks[0]}" "$OUT/repository-test.apk"
  fi
  if [ "$name" = dev ]; then sign wrong "$APK" "$OUT/wrong-key.apk"; fi
done
mkdir -p "$OUT/classes" "$OUT/dex"
javac -source 17 -target 17 -classpath "$ANDROID_JAR" -d "$OUT/classes" scripts/updates/installer-probe/UpgradeProbe.java
"$SDK/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" "$OUT/classes/dev/cinematv/updateprobe/UpgradeProbe.class"
"$SDK/aapt2" link -I "$ANDROID_JAR" --manifest scripts/updates/installer-probe/AndroidManifest.xml -o "$OUT/probe-unaligned.apk"
(cd "$OUT/dex" && zip -q "$OUT/probe-unaligned.apk" classes.dex)
"$SDK/zipalign" -p 4 "$OUT/probe-unaligned.apk" "$OUT/probe-unsigned.apk"
sign same "$OUT/probe-unsigned.apk" "$OUT/probe.apk"
# Tiny manifest-only negatives isolate package/minSdk mismatch from other identity fields.
for name in wrong-package wrong-min-sdk; do
  "$SDK/aapt2" link -I "$ANDROID_JAR" --manifest "scripts/updates/repository-fixtures/$name.xml" -o "$KEYDIR/$name-unaligned.apk"
  "$SDK/zipalign" -p 4 "$KEYDIR/$name-unaligned.apk" "$KEYDIR/$name-unsigned.apk"
  sign same "$KEYDIR/$name-unsigned.apk" "$OUT/$name.apk"
done
ANALYZER=$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer | head -1)
python3 scripts/updates/inspect-installer-fixtures.py "$OUT" "$SDK/apksigner" "$ANALYZER"
python3 scripts/updates/inspect-repository-fixtures.py "$OUT" "$SDK/apksigner" "$ANALYZER"
# Public payloads and signatures only. The real production signing helper remains untouched
# and forbidden on GitHub; this helper accepts only the short-lived TEST ONLY certificate.
python3 scripts/updates/prepare-test-envelopes.py "$OUT"
javac -d "$KEYDIR/classes" scripts/updates/TestOnlySignUpdateManifest.java
mapfile -t payloads < <(find "$OUT/manifests" -name '*.payload.json' ! -name 'wrong-envelope-key.payload.json' | sort)
java -cp "$KEYDIR/classes" TestOnlySignUpdateManifest "$KEYDIR/same.p12" "${payloads[@]}"
java -cp "$KEYDIR/classes" TestOnlySignUpdateManifest "$KEYDIR/wrong.p12" "$OUT/manifests/wrong-envelope-key.payload.json"
python3 - "$OUT/manifests" <<'PYTAMPER'
import base64, json, sys
from pathlib import Path
root = Path(sys.argv[1])
envelope = json.loads((root / 'tamper-source.json').read_text())
payload = json.loads(base64.b64decode(envelope['payload']))
payload['versionCode'] = 199  # do not re-sign the changed payload
envelope['payload'] = base64.b64encode(json.dumps(payload).encode()).decode()
(root / 'tampered.json').write_text(json.dumps(envelope))
(root / 'tamper-source.json').unlink()
for payload in root.glob('*.payload.json'):
    payload.unlink()
PYTAMPER
# Confirm instrumentation and debug-only classes are not shipped in the release target.
"$ANALYZER" dex packages --defined-only "$OUT/base.apk" > "$OUT/base-dex-packages.txt"
if grep -Eq 'UpdateRepositoryAcceptanceTest|MockWebServer|TvTestActivity|ProductionGlassTestActivity|NotoTestActivity|UpdateSettingsUiTest' "$OUT/base-dex-packages.txt"; then
  echo 'ERROR: instrumentation/debug classes leaked into release target'; exit 1
fi
"$ANALYZER" dex packages --defined-only "$OUT/repository-test.apk" > "$OUT/test-dex-packages.txt"
grep -q UpdateRepositoryAcceptanceTest "$OUT/test-dex-packages.txt"
if grep -Eq 'TvTestActivity|ProductionGlassTestActivity|NotoTestActivity|MobileUpdateSettingsTest|TvUpdateSettingsTest' "$OUT/test-dex-packages.txt"; then
  echo 'ERROR: existing debug UI tests leaked into isolated release instrumentation'; exit 1
fi
(cd "$OUT" && sha256sum base.apk stable.apk dev.apk wrong-key.apk probe.apk repository-test.apk wrong-package.apk wrong-min-sdk.apk manifests/*.json > SHA256SUMS.txt)
rm -rf "$OUT/classes" "$OUT/dex" "$OUT/probe-unaligned.apk" "$OUT/probe-unsigned.apk"
