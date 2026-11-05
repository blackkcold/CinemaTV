#!/usr/bin/env bash
# Public unsigned artifact only. Stable publication is a separate, evidence-gated action.
set -euo pipefail
: "${ANDROID_HOME:?}"
if env | grep -q '^CINEMATV_KEYSTORE_\|^CINEMATV_KEY_PASSWORD=\|^CINEMATV_KEY_ALIAS='; then
  echo 'Unsigned candidate preparation refuses all external signing credentials'; exit 1
fi
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo 'Candidate requires a clean tracked source tree'; exit 1
fi
gradle --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  -PcinematvChannel=stable -PcinematvVersionCode=10 -PcinematvVersionName=0.8.0 \
  -PcinematvUpdatesEnabled=true -PcinematvUnsignedCandidate=true \
  :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease
APK=app/build/outputs/apk/release/app-release-unsigned.apk
test -f "$APK"
if "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$APK" >/dev/null 2>&1; then
  echo 'Unsigned candidate unexpectedly has a valid signature'; exit 1
fi
unzip -t "$APK" > /dev/null
mkdir -p candidate
cp "$APK" candidate/CinemaTV-0.8.0-code10-stable-UNSIGNED.apk
ANALYZER=$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer | head -1)
"$ANALYZER" manifest print "$APK" > candidate/AndroidManifest.xml
python3 - <<'PY'
import hashlib, json, pathlib, subprocess, xml.etree.ElementTree as ET
root = pathlib.Path('candidate')
manifest = ET.parse(root / 'AndroidManifest.xml').getroot()
a = '{http://schemas.android.com/apk/res/android}'
app = manifest.find('application')
assert manifest.get('package') == 'dev.cinematv'
assert manifest.get(a + 'versionCode') == '10'
assert manifest.get(a + 'versionName') == '0.8.0'
assert app.get(a + 'debuggable', 'false') == 'false'
assert any(x.get(a+'name') == 'dev.cinematv.UPDATE_CHANNEL' and x.get(a+'value') == 'stable' for x in app.findall('meta-data'))
apk = root / 'CinemaTV-0.8.0-code10-stable-UNSIGNED.apk'
receipt = dict(schema=1, status='unsigned-not-installable-not-certified', channel='stable',
    versionCode=10, versionName='0.8.0', updatesEnabled=True,
    sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'], text=True).strip(),
    sourceTree=subprocess.check_output(['git','rev-parse','HEAD^{tree}'], text=True).strip(),
    apkBytes=apk.stat().st_size, unsignedApkSha256=hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest())
(root / 'unsigned-receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
PY
