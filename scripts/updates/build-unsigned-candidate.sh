#!/usr/bin/env bash
# Public unsigned artifacts only. Publication is a separate, evidence-gated action.
set -euo pipefail
: "${ANDROID_HOME:?}"
# Inspect only variable names; never pipe/print environment values (or depend on SIGPIPE).
for variable in ${!CINEMATV_KEYSTORE_@} CINEMATV_KEY_PASSWORD CINEMATV_KEY_ALIAS \
    CINEMATV_LIBRETV_SERVER CINEMATV_LIBRETV_PASSWORD; do
  if declare -p "$variable" >/dev/null 2>&1; then
    echo 'Unsigned candidate preparation refuses external signing credentials and personal build defaults'; exit 1
  fi
done
if [ "$#" -gt 1 ]; then
  echo 'Usage: build-unsigned-candidate.sh [stable|dev]'; exit 2
fi
# One reserved global versionCode sequence. The no-argument stable path is unchanged.
CHANNEL=${1:-stable}
case "$CHANNEL" in
  stable) VERSION_NAME=0.9.0; VERSION_CODE=16; OUTPUT=candidate ;;
  # Codes 12 (v0.8.0-dev.2), 13 (archived TV Web build), 14 (v0.9.0-dev.2)
  # and 15 (v0.9.0-dev.3) are already public; 16 (v0.9.0) is the highest.
  dev) VERSION_NAME=0.9.0-dev.4; VERSION_CODE=17; OUTPUT=candidate-dev ;;
  *) echo 'Usage: build-unsigned-candidate.sh [stable|dev]'; exit 2 ;;
esac
if [ -e "$OUTPUT" ]; then
  echo 'Candidate output already exists; preserve its receipt and use a fresh checkout'; exit 1
fi
if [ -n "$(git status --porcelain --untracked-files=all)" ]; then
  echo 'Candidate requires a clean tracked source tree'; exit 1
fi
# Ignored files under real source roots are still compiler/resource inputs.
if [ -n "$(git ls-files --others --ignored --exclude-standard app/src backdrop/src)" ]; then
  echo 'Candidate refuses untracked ignored files in source/resource inputs'; exit 1
fi
# Stable candidates must never carry the unaccepted account experiment. Gradle enforces
# the same channel split and AGENTS keeps that code dev-only; a dev candidate may carry it.
if [ "$CHANNEL" = stable ]; then
  for source_root in app/src/main/java/dev/cinematv/data/douban app/src/main/java/dev/cinematv/ui/douban; do
    if [ -d "$source_root" ] && [ -n "$(find "$source_root" -type f -name '*.kt' -print -quit)" ]; then
      echo 'Candidate refuses unaccepted Douban personal-account sources'; exit 1
    fi
  done
fi
SOURCE_COMMIT=$(git rev-parse HEAD)
SOURCE_TREE=$(git rev-parse 'HEAD^{tree}')
export SOURCE_COMMIT SOURCE_TREE CHANNEL VERSION_NAME VERSION_CODE OUTPUT
gradle --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  "-PcinematvChannel=$CHANNEL" "-PcinematvVersionCode=$VERSION_CODE" "-PcinematvVersionName=$VERSION_NAME" \
  -PcinematvUpdatesEnabled=true -PcinematvUnsignedCandidate=true \
  :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease
test "$(git rev-parse HEAD)" = "$SOURCE_COMMIT"
test "$(git rev-parse 'HEAD^{tree}')" = "$SOURCE_TREE"
git diff --exit-code
git diff --cached --exit-code
if [ -n "$(git status --porcelain --untracked-files=all)" ] ||
   [ -n "$(git ls-files --others --ignored --exclude-standard app/src backdrop/src)" ]; then
  echo 'Source inputs changed while preparing candidate'; exit 1
fi
APK=app/build/outputs/apk/release/app-release-unsigned.apk
test -f "$APK"
if "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$APK" >/dev/null 2>&1; then
  echo 'Unsigned candidate unexpectedly has a valid signature'; exit 1
fi
unzip -t "$APK" > /dev/null
if unzip -l "$APK" | grep -q 'assets/tv-web/'; then
  echo 'Candidate must not contain retired TV Web assets' >&2
  exit 1
fi
mkdir "$OUTPUT"
cp "$APK" "$OUTPUT/CinemaTV-$VERSION_NAME-code$VERSION_CODE-$CHANNEL-UNSIGNED.apk"
ANALYZER=$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer | head -1)
test -n "$ANALYZER"
"$ANALYZER" manifest print "$APK" > "$OUTPUT/AndroidManifest.xml"
python3 - <<'PY'
import hashlib, json, os, pathlib, re, subprocess, xml.etree.ElementTree as ET, zipfile
root = pathlib.Path(os.environ['OUTPUT'])
channel, name, code = os.environ['CHANNEL'], os.environ['VERSION_NAME'], os.environ['VERSION_CODE']
manifest = ET.parse(root / 'AndroidManifest.xml').getroot()
a = '{http://schemas.android.com/apk/res/android}'
app = manifest.find('application')
sdk = manifest.find('uses-sdk')
assert app is not None and sdk is not None
assert manifest.get('package') == 'dev.cinematv'
assert manifest.get(a + 'versionCode') == code
assert manifest.get(a + 'versionName') == name
assert sdk.get(a + 'minSdkVersion') == '26'
assert app.get(a + 'debuggable', 'false') == 'false'
assert app.get(a + 'label') == ('CinemaTV Dev' if channel == 'dev' else 'CinemaTV')
assert [x.get(a+'value') for x in app.findall('meta-data') if x.get(a+'name') == 'dev.cinematv.UPDATE_CHANNEL'] == [channel]
assert [x.get(a+'value') for x in app.findall('meta-data') if x.get(a+'name') == 'dev.cinematv.PRIVATE_BUILD'] == ['false']
apk = root / f'CinemaTV-{name}-code{code}-{channel}-UNSIGNED.apk'
with zipfile.ZipFile(apk) as archive:
    assert not any(entry.startswith('assets/tv-web/') for entry in archive.namelist()), 'Candidate must not contain retired TV Web assets'
head = os.environ.get('CANDIDATE_SOURCE_HEAD', os.environ['SOURCE_COMMIT'])
assert re.fullmatch(r'[0-9a-f]{40}', head), 'Invalid source head SHA'
def git_object(ref):
    return subprocess.check_output(['git', 'rev-parse', ref], text=True).strip()
receipt = dict(schema=1, status='unsigned-not-installable-not-certified', channel=channel,
    artifactLabel='NOT FOR INSTALLATION', applicationId='dev.cinematv', minSdk=26,
    versionCode=int(code), versionName=name, updatesEnabled=True,
    sourceCommit=os.environ['SOURCE_COMMIT'], sourceTree=os.environ['SOURCE_TREE'],
    sourceHead=head,
    applicationSourceTree=git_object('HEAD:app/src'),
    backdropSourceTree=git_object('HEAD:backdrop/src'),
    applicationBuildBlob=git_object('HEAD:app/build.gradle.kts'),
    apkBytes=apk.stat().st_size, unsignedApkSha256=hashlib.sha256(apk.read_bytes()).hexdigest())
identity = json.loads(pathlib.Path('release/signing-identity.json').read_text())
assert identity.get('applicationId') == 'dev.cinematv' and re.fullmatch(r'[0-9a-f]{64}', identity.get('certificateSha256', ''))
receipt['expectedCertificateSha256'] = identity['certificateSha256']
if os.environ.get('GITHUB_ACTIONS') == 'true':
    receipt['workflow'] = {key: os.environ.get(variable, '') for key, variable in (
        ('repository', 'GITHUB_REPOSITORY'), ('runId', 'GITHUB_RUN_ID'),
        ('runAttempt', 'GITHUB_RUN_ATTEMPT'), ('event', 'GITHUB_EVENT_NAME'), ('ref', 'GITHUB_REF'))}
(root / 'unsigned-receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
(root / 'NOT-FOR-INSTALLATION.txt').write_text(
    'NOT FOR INSTALLATION. Unsigned public build for external signing only.\n'
    'No production signature, final signed-APK acceptance or publication is established.\n'
    'Preserve this receipt and APK; signing creates different bytes and requires a new receipt.\n')
PY
