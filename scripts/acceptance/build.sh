#!/usr/bin/env bash
# Compile each configuration once, before allocating any emulator.
set -euo pipefail
if [ "$#" -ne 1 ]; then echo 'Usage: build.sh OUTPUT_ROOT' >&2; exit 2; fi
OUTPUT=$1
mkdir -p "$OUTPUT/acceptance-build-identities" "$OUTPUT/acceptance-build-reports"
python3 scripts/hybrid/artifacts.py check-source
bash scripts/generate-test-media.sh
npm --prefix frontend/tv-web ci --ignore-scripts
npm --prefix backend/tv-bff test
GRADLE=(gradle --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process)
for CONFIGURATION in preview-debug dev-debug; do
  PROPERTIES=()
  if [ "$CONFIGURATION" = dev-debug ]; then
    PROPERTIES=(-PcinematvChannel=dev
      "-PcinematvVersionName=$(python3 -c 'import json; print(json.load(open("scripts/hybrid/release.json"))["versionName"])')"
      "-PcinematvVersionCode=$(python3 -c 'import json; print(json.load(open("scripts/hybrid/release.json"))["versionCode"])')")
  fi
  "${GRADLE[@]}" "${PROPERTIES[@]}" :backdrop:testDebugUnitTest :app:testDebugUnitTest \
    :app:assembleDebug :app:assembleDebugAndroidTest :backdrop:lintDebug :app:lintDebug
  python3 scripts/fonts/verify-noto-fonts.py
  python3 scripts/hybrid/artifacts.py check-source
  "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/debug/app-debug.apk
  python3 - <<'PY'
  from pathlib import Path
  import xml.etree.ElementTree as ET
  for module in ('app', 'backdrop'):
      reports = list(Path(module, 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
      cases = [c for path in reports for c in ET.parse(path).getroot().iter('testcase')]
      assert cases, f'{module}: no executed JVM cases'
      assert all(c.find(tag) is None for c in cases for tag in ('failure', 'error', 'skipped'))
  PY
  BUNDLE="$OUTPUT/acceptance-$CONFIGURATION-apks"
  python3 scripts/hybrid/artifacts.py device-bundle \
    --apk app/build/outputs/apk/debug/app-debug.apk \
    --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk --output "$BUNDLE"
  ANALYZER=$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer | head -1)
  "$ANALYZER" manifest print "$BUNDLE/app-debug.apk" > "$BUNDLE/AndroidManifest.xml"
  python3 - "$BUNDLE/AndroidManifest.xml" "$CONFIGURATION" <<'PY'
  import json, pathlib, sys, xml.etree.ElementTree as ET
  root = ET.parse(sys.argv[1]).getroot(); a = '{http://schemas.android.com/apk/res/android}'
  app = root.find('application'); sdk = root.find('uses-sdk')
  assert root.get('package') == 'dev.cinematv' and app.get(a + 'debuggable') == 'true'
  assert sdk.get(a + 'minSdkVersion') == '26'
  assert any(x.get(a + 'name') == 'dev.cinematv.UPDATE_CHANNEL' and x.get(a + 'value') == 'dev' for x in app.findall('meta-data'))
  if sys.argv[2] == 'dev-debug':
      target = json.loads(pathlib.Path('scripts/hybrid/release.json').read_text())
      assert root.get(a + 'versionName') == target['versionName'] and root.get(a + 'versionCode') == str(target['versionCode'])
  else:
      assert root.get(a + 'versionName') == '0.8.0-preview' and root.get(a + 'versionCode') == '9'
  PY
  python3 scripts/acceptance/manifest.py build --bundle "$BUNDLE" --configuration "$CONFIGURATION" --output "$BUNDLE/acceptance-build.json"
  mkdir -p "$OUTPUT/acceptance-build-identities/$CONFIGURATION" "$OUTPUT/acceptance-build-reports/$CONFIGURATION"
  cp "$BUNDLE/acceptance-build.json" "$OUTPUT/acceptance-build-identities/$CONFIGURATION/"
  cp -R app/build/test-results "$OUTPUT/acceptance-build-reports/$CONFIGURATION/app-tests"
  cp -R backdrop/build/test-results "$OUTPUT/acceptance-build-reports/$CONFIGURATION/backdrop-tests"
  cp app/build/reports/lint-results-debug.* "$OUTPUT/acceptance-build-reports/$CONFIGURATION/"
done
# Different bytes and scope: this is handed to the original external signer only.
bash scripts/hybrid/build-dev-candidate.sh "$OUTPUT/acceptance-unsigned-dev"
cp -R app/build/test-results/testReleaseUnitTest "$OUTPUT/acceptance-build-reports/release-tests"
cp app/build/reports/lint-results-release.* "$OUTPUT/acceptance-build-reports/"
