#!/usr/bin/env bash
# Called only inside the owned ephemeral actual-TV launcher, after functional suites.
set -euo pipefail
if [ "$#" -ne 2 ]; then echo 'Usage: run-android-visual.sh BUNDLE EVIDENCE' >&2; exit 2; fi
case "${CINEMA_ACCEPTANCE_LANE:-}" in hybrid1080|hybrid4k) ;; *) echo 'Not a declared disposable visual lane' >&2; exit 2 ;; esac
[ "${GITHUB_ACTIONS:-}" = true ] || { echo 'Automatic data reset is restricted to the disposable CI guest' >&2; exit 2; }
BUNDLE=$1
EVIDENCE=$2
ADB="${ANDROID_HOME:?}/platform-tools/adb"
SERIAL=${ANDROID_SERIAL:-emulator-5554}
DEVICE_OUTPUT=/sdcard/Android/media/dev.cinematv/acceptance-visual-output
# The previous functional suites already exported their preservation evidence.
# This reset creates deterministic visual fixtures, not a workaround in those suites.
timeout --kill-after=5s 30s "$ADB" -s "$SERIAL" shell pm clear dev.cinematv | grep -Fx Success
timeout --kill-after=5s 20s "$ADB" -s "$SERIAL" shell mkdir -p "$DEVICE_OUTPUT"
set +e
timeout --signal=TERM --kill-after=10s 6m "$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class dev.cinematv.HybridVisualAcceptanceTest -e additionalTestOutputDir "$DEVICE_OUTPUT" \
  dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner | tee "$EVIDENCE/visual-instrumentation.txt"
STATUS=${PIPESTATUS[0]}
timeout --kill-after=5s 45s "$ADB" -s "$SERIAL" pull "$DEVICE_OUTPUT/visual-evidence" "$EVIDENCE/" >/dev/null
PULL_STATUS=$?
set -e
[ "$STATUS" -eq 0 ] && [ "$PULL_STATUS" -eq 0 ]
python3 scripts/acceptance/android_visual.py --bundle "$BUNDLE" --evidence "$EVIDENCE" --lane "$CINEMA_ACCEPTANCE_LANE"
