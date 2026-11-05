#!/usr/bin/env bash
set -uo pipefail
mkdir -p probe-evidence
qa_adb() { timeout --signal=TERM --kill-after=5s 20s adb "$@"; }
qa_adb shell getprop > probe-evidence/device-properties.txt
set +e
timeout --signal=TERM --kill-after=30s 10m gradle --no-daemon --max-workers=2 -p experiments/ranksys-android :app:connectedDebugAndroidTest
STATUS=$?
qa_adb logcat -d -s RankSysProbe > probe-evidence/runtime.txt
# Exercise the actual R8-minified app through its manifest entry point, not just D8.
RELEASE_STATUS=0
if [ "$STATUS" -eq 0 ]; then
  PROBE_NONCE=$(date +%s%N)
  qa_adb install -r experiments/ranksys-android/app/build/outputs/apk/release/app-release.apk &&
    qa_adb shell am start -W -n dev.cinematv.ranksysprobe/.ProbeActivity --es probe_nonce "$PROBE_NONCE" > probe-evidence/minified-launch.txt || RELEASE_STATUS=$?
  if [ "$RELEASE_STATUS" -eq 0 ]; then
    RELEASE_STATUS=1
    for attempt in $(seq 1 20); do
      qa_adb logcat -d -s RankSysProbe AndroidRuntime > probe-evidence/minified-runtime.txt || break
      if grep -q "MINIFIED_GUARDED_PASS.*nonce=$PROBE_NONCE" probe-evidence/minified-runtime.txt &&
          grep -q "BUNDLED_LICENSE_AND_SOURCE_PASS.*nonce=$PROBE_NONCE" probe-evidence/minified-runtime.txt; then
        RELEASE_STATUS=0
        break
      fi
      sleep 1
    done
  fi
fi
if [ "$STATUS" -ne 0 ]; then exit "$STATUS"; fi
exit "$RELEASE_STATUS"
