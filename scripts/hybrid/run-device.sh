#!/usr/bin/env bash
# Install the already compiled exact-head APKs; allocate no Gradle JVM beside the emulator.
set -euo pipefail
if [ "$#" -ne 3 ] || [[ ! "$3" =~ ^(hybrid|native-fallback)$ ]]; then
  echo 'Usage: run-device.sh BUNDLE_DIRECTORY EVIDENCE_DIRECTORY hybrid|native-fallback' >&2
  exit 2
fi
BUNDLE=$1
EVIDENCE=$2
MODE=$3
DEVICE_OUTPUT=/sdcard/Android/media/dev.cinematv/hybrid-output
python3 scripts/hybrid/artifacts.py verify-device-bundle --directory "$BUNDLE"
mkdir -p "$EVIDENCE"
adb_bounded() { timeout --signal=TERM --kill-after=5s 25s adb "$@"; }
LOG_PID=
finish() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if [ -n "$LOG_PID" ]; then kill "$LOG_PID" 2>/dev/null; wait "$LOG_PID" 2>/dev/null; fi
  adb_bounded shell dumpsys webviewupdate > "$EVIDENCE/webview-provider.txt" 2>&1
  adb_bounded shell dumpsys meminfo dev.cinematv > "$EVIDENCE/final-memory.txt" 2>&1
  adb_bounded shell dumpsys activity top > "$EVIDENCE/activity.txt" 2>&1
  adb_bounded logcat -d -s AndroidRuntime:E > "$EVIDENCE/crashes.txt" 2>&1
  timeout --signal=TERM --kill-after=5s 30s adb pull "$DEVICE_OUTPUT/hybrid-evidence" "$EVIDENCE/" >/dev/null 2>&1
  printf 'harness_exit_status=%s\n' "$status" > "$EVIDENCE/status.txt"
  exit "$status"
}
trap finish EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
test "$(adb_bounded shell getprop sys.boot_completed | tr -d '\r')" = 1
# These are dedicated fresh Android TV emulator instances, never a user's device.
adb_bounded shell svc power stayon true
adb_bounded shell wm dismiss-keyguard
adb_bounded shell wm size 1920x1080
adb_bounded shell wm density 320
adb_bounded shell settings put system screen_off_timeout 2147483647
adb_bounded shell settings put secure immersive_mode_confirmations confirmed
{
  printf 'expected_presentation=%s\n' "$MODE"
  printf 'api='; adb_bounded shell getprop ro.build.version.sdk
  printf 'fingerprint='; adb_bounded shell getprop ro.build.fingerprint
  printf 'characteristics='; adb_bounded shell getprop ro.build.characteristics
  adb_bounded shell dumpsys uimode
  adb_bounded shell wm size
  adb_bounded shell wm density
  adb_bounded shell dumpsys webviewupdate
} > "$EVIDENCE/device.txt"
API=$(adb_bounded shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$MODE" = hybrid ]; then test "$API" -ge 29; else test "$API" -eq 26; fi
timeout --signal=TERM --kill-after=5s 60s adb install -r "$BUNDLE/app-debug.apk"
timeout --signal=TERM --kill-after=5s 60s adb install -r "$BUNDLE/app-debug-androidTest.apk"
adb_bounded shell mkdir -p "$DEVICE_OUTPUT"
timeout --signal=TERM --kill-after=5s 10m adb logcat -v threadtime \
  'TestRunner:I' 'CinemaTVQa:I' 'AndroidRuntime:E' '*:S' > "$EVIDENCE/live-test.log" 2>&1 &
LOG_PID=$!
set +e
timeout --signal=TERM --kill-after=15s 8m adb shell am instrument -w -r \
  -e class dev.cinematv.HybridHostUiTest \
  -e additionalTestOutputDir "$DEVICE_OUTPUT" \
  -e hybridExpectedMode "$MODE" \
  dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner | tee "$EVIDENCE/instrumentation.txt"
INSTRUMENTATION_STATUS=${PIPESTATUS[0]}
set -e
test "$INSTRUMENTATION_STATUS" -eq 0
timeout --signal=TERM --kill-after=5s 30s adb pull "$DEVICE_OUTPUT/hybrid-evidence" "$EVIDENCE/" >/dev/null
python3 scripts/hybrid/artifacts.py device-result --bundle "$BUNDLE" --evidence "$EVIDENCE" --mode "$MODE"
