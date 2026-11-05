#!/usr/bin/env bash
# adb is used only on an isolated emulator. This tests PackageManager, not installer UI.
set -euo pipefail
FIXTURES=${1:?Fixture directory}
EVIDENCE=${2:?Evidence directory}
mkdir -p "$EVIDENCE"
exec > >(tee "$EVIDENCE/install-upgrades.log") 2>&1
cp "$FIXTURES/SHA256SUMS.txt" "$FIXTURES/fixture-receipt.json" "$EVIDENCE/"
(cd "$FIXTURES" && sha256sum -c SHA256SUMS.txt)
CERTIFICATE=$(python3 - "$FIXTURES/fixture-receipt.json" <<'PYMETA'
import json, sys
r = json.load(open(sys.argv[1]))
assert r['schema'] == 1
assert r['fixtures']['base']['certificateSha256'] == r['fixtures']['stable']['certificateSha256'] == r['fixtures']['dev']['certificateSha256']
assert r['fixtures']['wrong-key']['certificateSha256'] != r['fixtures']['base']['certificateSha256']
print(r['fixtures']['base']['certificateSha256'])
PYMETA
)
probe() {
  adb shell am instrument -w -r -e mode "$1" -e channel "$3" -e version "$4" -e certificate "$CERTIFICATE" dev.cinematv.updateprobe/dev.cinematv.updateprobe.UpgradeProbe | tee "$EVIDENCE/probe-$2.txt"
  grep -q "CINEMATV_UPDATE_PROBE_OK $1" "$EVIDENCE/probe-$2.txt"
  ! grep -q CINEMATV_UPDATE_PROBE_FAILED "$EVIDENCE/probe-$2.txt"
}
version() { adb shell dumpsys package dev.cinematv | grep -q "versionCode=$1 "; }
reject() {
  local apk=$1 expected=$2 label=$3
  if adb install -r "$FIXTURES/$apk.apk" > "$EVIDENCE/rejected-$label.txt" 2>&1; then
    echo "ERROR: $label installation unexpectedly accepted"; exit 1
  fi
  grep -q "$expected" "$EVIDENCE/rejected-$label.txt"
  version 101
  probe verify "$label" stable 101
}
adb install "$FIXTURES/base.apk"
adb install "$FIXTURES/probe.apk"
version 100
probe seed initial dev 100
adb install -r "$FIXTURES/stable.apk"
version 101
probe verify dev-to-stable stable 101
reject wrong-key INSTALL_FAILED_UPDATE_INCOMPATIBLE wrong-signer
reject base INSTALL_FAILED_VERSION_DOWNGRADE downgrade
adb install -r "$FIXTURES/dev.apk"
version 102
probe verify stable-to-dev dev 102
printf 'PASS: same-key forward dev/stable upgrades preserve preferences and private SQLite sentinel; wrong signer and downgrade rejected.\n' > "$EVIDENCE/result.txt"
