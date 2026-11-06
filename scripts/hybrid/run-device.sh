#!/usr/bin/env bash
# Install the already compiled exact-head APKs; allocate no Gradle JVM beside the emulator.
set -euo pipefail
if [ "$#" -lt 3 ] || [ "$#" -gt 5 ] || [[ ! "$3" =~ ^(hybrid|native-fallback|provider-policy)$ ]]; then
  echo 'Usage: run-device.sh BUNDLE_DIRECTORY EVIDENCE_DIRECTORY hybrid|native-fallback|provider-policy [SIZE] [DENSITY]' >&2
  exit 2
fi
BUNDLE=$1
EVIDENCE=$2
MODE=$3
SIZE=${4:-1920x1080}
DENSITY=${5:-320}
[[ "$SIZE" =~ ^(1920x1080|3840x2160)$ ]] && [[ "$DENSITY" =~ ^(320|640)$ ]]
DEVICE_OUTPUT=/sdcard/Android/media/dev.cinematv/hybrid-output
python3 scripts/hybrid/artifacts.py verify-device-bundle --directory "$BUNDLE"
if [ -d "$EVIDENCE" ] && [ -n "$(find "$EVIDENCE" -mindepth 1 -print -quit)" ]; then
  echo 'Evidence directory is not empty; use a new directory for this run' >&2
  exit 1
fi
mkdir -p "$EVIDENCE"
HYBRID_ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
if [ ! -x "$HYBRID_ADB" ]; then HYBRID_ADB=$(command -v adb); fi
HYBRID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
adb_bounded() { timeout --signal=TERM --kill-after=5s 25s "$HYBRID_ADB" -s "$HYBRID_SERIAL" "$@"; }
LOG_PID=
finish() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if [ -n "$LOG_PID" ]; then kill "$LOG_PID" 2>/dev/null; wait "$LOG_PID" 2>/dev/null; fi
  {
    printf 'emulator_processes (pid ppid state name):\n'
    timeout --kill-after=5s 5s ps -eo pid=,ppid=,stat=,comm= | awk '$4 ~ /^(emulator|qemu-system)/'
    timeout --kill-after=5s 5s free -m
  } > "$EVIDENCE/host-state.txt" 2>&1
  if [ "$status" -ne 0 ]; then
    timeout --kill-after=5s 10s sudo -n dmesg -T | tail -n 120 > "$EVIDENCE/host-kernel.txt" 2>&1
  fi
  adb_bounded shell dumpsys webviewupdate > "$EVIDENCE/webview-provider.txt" 2>&1
  adb_bounded shell dumpsys meminfo dev.cinematv > "$EVIDENCE/final-memory.txt" 2>&1
  adb_bounded shell dumpsys activity top > "$EVIDENCE/activity.txt" 2>&1
  adb_bounded logcat -d -s AndroidRuntime:E > "$EVIDENCE/crashes.txt" 2>&1
  timeout --signal=TERM --kill-after=5s 30s "$HYBRID_ADB" -s "$HYBRID_SERIAL" pull "$DEVICE_OUTPUT/hybrid-evidence" "$EVIDENCE/" >/dev/null 2>&1
  timeout --signal=TERM --kill-after=5s 30s "$HYBRID_ADB" -s "$HYBRID_SERIAL" pull "$DEVICE_OUTPUT/audit-evidence" "$EVIDENCE/" >/dev/null 2>&1
  printf 'harness_exit_status=%s\n' "$status" > "$EVIDENCE/status.txt"
  exit "$status"
}
trap finish EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
bash scripts/hybrid/wait-device.sh "$HYBRID_ADB" "$HYBRID_SERIAL" "$EVIDENCE/startup.txt"
# These are dedicated fresh Android TV emulator instances, never a user's device.
adb_bounded shell svc power stayon true
adb_bounded shell wm dismiss-keyguard
adb_bounded shell wm size "$SIZE"
adb_bounded shell wm density "$DENSITY"
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
case "$MODE" in
  hybrid) test "$API" -ge 29 ;;
  native-fallback) test "$API" -eq 26 ;;
  provider-policy) test "$API" -eq 29 ;;
esac
timeout --signal=TERM --kill-after=5s 60s "$HYBRID_ADB" -s "$HYBRID_SERIAL" install -r "$BUNDLE/app-debug.apk"
timeout --signal=TERM --kill-after=5s 60s "$HYBRID_ADB" -s "$HYBRID_SERIAL" install -r "$BUNDLE/app-debug-androidTest.apk"
adb_bounded shell mkdir -p "$DEVICE_OUTPUT"
adb_bounded shell rm -rf "$DEVICE_OUTPUT/hybrid-evidence" "$DEVICE_OUTPUT/audit-evidence"
timeout --signal=TERM --kill-after=5s 18m "$HYBRID_ADB" -s "$HYBRID_SERIAL" logcat -v threadtime \
  'TestRunner:I' 'CinemaTVQa:I' 'CinemaTVRailVisibility:D' 'CinemaTVCardRelocation:D' \
  'AndroidRuntime:E' '*:S' > "$EVIDENCE/live-test.log" 2>&1 &
LOG_PID=$!
set +e
timeout --signal=TERM --kill-after=15s 8m "$HYBRID_ADB" -s "$HYBRID_SERIAL" shell am instrument -w -r \
  -e class dev.cinematv.HybridHostUiTest \
  -e additionalTestOutputDir "$DEVICE_OUTPUT" \
  -e hybridExpectedMode "$MODE" \
  dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner | tee "$EVIDENCE/instrumentation.txt"
INSTRUMENTATION_STATUS=${PIPESTATUS[0]}
set -e
test "$INSTRUMENTATION_STATUS" -eq 0
timeout --signal=TERM --kill-after=5s 30s "$HYBRID_ADB" -s "$HYBRID_SERIAL" pull "$DEVICE_OUTPUT/hybrid-evidence" "$EVIDENCE/" >/dev/null
python3 scripts/hybrid/artifacts.py device-result --bundle "$BUNDLE" --evidence "$EVIDENCE" --mode "$MODE"
# Keep the original per-case screenshot gate intact, then exercise the same APK's
# system session on every API and additional hybrid flows on capable providers.
CLASSES=$(python3 scripts/hybrid/tv_regressions.py --classes --evidence "$EVIDENCE")
set +e
timeout --signal=TERM --kill-after=15s 8m "$HYBRID_ADB" -s "$HYBRID_SERIAL" shell am instrument -w -r \
  -e class "$CLASSES" -e additionalTestOutputDir "$DEVICE_OUTPUT" \
  dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner | tee "$EVIDENCE/regressions.txt"
REGRESSION_STATUS=${PIPESTATUS[0]}
set -e
test "$REGRESSION_STATUS" -eq 0
timeout --signal=TERM --kill-after=5s 30s "$HYBRID_ADB" -s "$HYBRID_SERIAL" pull "$DEVICE_OUTPUT/audit-evidence" "$EVIDENCE/" >/dev/null 2>&1 || true
python3 scripts/hybrid/tv_regressions.py --bundle "$BUNDLE" --evidence "$EVIDENCE"
