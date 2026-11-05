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
  qa_adb install -r experiments/ranksys-android/app/build/outputs/apk/release/app-release.apk &&
    qa_adb logcat -c &&
    qa_adb shell am start -W -n dev.cinematv.ranksysprobe/.ProbeActivity > probe-evidence/minified-launch.txt || RELEASE_STATUS=$?
  if [ "$RELEASE_STATUS" -eq 0 ]; then
    sleep 2
    qa_adb logcat -d -s RankSysProbe AndroidRuntime > probe-evidence/minified-runtime.txt || RELEASE_STATUS=$?
    grep -q 'MINIFIED_GUARDED_PASS' probe-evidence/minified-runtime.txt || RELEASE_STATUS=1
  fi
fi
if [ "$STATUS" -ne 0 ]; then exit "$STATUS"; fi
exit "$RELEASE_STATUS"
