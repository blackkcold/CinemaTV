#!/usr/bin/env bash
# adb is used only on an isolated emulator. This tests PackageManager, not installer UI.
set -euo pipefail
FIXTURES=${1:?Fixture directory}
EVIDENCE=${2:?Evidence directory}
mkdir -p "$EVIDENCE"
exec > >(tee "$EVIDENCE/install-upgrades.log") 2>&1
cp "$FIXTURES/SHA256SUMS.txt" "$EVIDENCE/"
(cd "$FIXTURES" && sha256sum -c SHA256SUMS.txt)
probe() {
  adb shell am instrument -w -r -e mode "$1" dev.cinematv.updateprobe/dev.cinematv.updateprobe.UpgradeProbe | tee "$EVIDENCE/probe-$2.txt"
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
  probe verify "$label"
}
adb install "$FIXTURES/base.apk"
adb install "$FIXTURES/probe.apk"
version 100
probe seed initial
adb install -r "$FIXTURES/stable.apk"
version 101
probe verify dev-to-stable
reject wrong-key INSTALL_FAILED_UPDATE_INCOMPATIBLE wrong-signer
reject base INSTALL_FAILED_VERSION_DOWNGRADE downgrade
adb install -r "$FIXTURES/dev.apk"
version 102
probe verify stable-to-dev
printf 'PASS: same-key forward dev/stable upgrades preserve preferences and private SQLite sentinel; wrong signer and downgrade rejected.\n' > "$EVIDENCE/result.txt"
