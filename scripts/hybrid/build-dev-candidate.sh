#!/usr/bin/env bash
# This builds public unsigned bytes only. The fixed external signer is separate.
set -euo pipefail
if [ "$#" -ne 1 ]; then
  echo 'Usage: build-dev-candidate.sh NEW_OUTPUT_DIRECTORY' >&2
  exit 2
fi
: "${ANDROID_HOME:?Android SDK is required}"
for variable in ${!CINEMATV_KEYSTORE_@} CINEMATV_KEY_PASSWORD CINEMATV_KEY_ALIAS; do
  if declare -p "$variable" >/dev/null 2>&1; then
    echo 'Unsigned candidate refuses external signing credentials' >&2
    exit 1
  fi
done
OUTPUT=$1
if [ -e "$OUTPUT" ]; then
  echo 'Candidate output already exists; preserve its receipt' >&2
  exit 1
fi
SOURCE_COMMIT=$(python3 scripts/hybrid/artifacts.py check-source)
SOURCE_TREE=$(git rev-parse 'HEAD^{tree}')
VERSION_NAME=$(python3 -c 'import json; print(json.load(open("scripts/hybrid/release.json"))["versionName"])')
VERSION_CODE=$(python3 -c 'import json; print(json.load(open("scripts/hybrid/release.json"))["versionCode"])')
# No npm install fallback: the exact transitive dependency lock must be committed.
test -f frontend/tv-web/package-lock.json
npm --prefix frontend/tv-web run build
npm --prefix frontend/tv-web run verify
gradle --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  -PcinematvChannel=dev "-PcinematvVersionCode=$VERSION_CODE" "-PcinematvVersionName=$VERSION_NAME" \
  -PcinematvUpdatesEnabled=true -PcinematvUnsignedCandidate=true \
  :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease
test "$(python3 scripts/hybrid/artifacts.py check-source)" = "$SOURCE_COMMIT"
test "$(git rev-parse 'HEAD^{tree}')" = "$SOURCE_TREE"
APK=app/build/outputs/apk/release/app-release-unsigned.apk
test -f "$APK"
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
test -x "$APKSIGNER"
if "$APKSIGNER" verify "$APK" >/dev/null 2>&1; then
  echo 'Unsigned candidate unexpectedly contains a valid APK signature' >&2
  exit 1
fi
ANALYZER=$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer | head -1)
test -n "$ANALYZER"
MANIFEST=$(mktemp)
trap 'rm -f "$MANIFEST"' EXIT
"$ANALYZER" manifest print "$APK" > "$MANIFEST"
python3 scripts/hybrid/artifacts.py candidate --apk "$APK" --manifest "$MANIFEST" --output "$OUTPUT"
echo 'Prepared unsigned Dev candidate and provenance; NOT FOR INSTALLATION.'
