#!/usr/bin/env bash
# Reuse the exact APKs built before emulator allocation; no Gradle beside the emulator.
set -euo pipefail
if [ "$#" -ne 2 ]; then
  echo 'Usage: run-phone.sh BUNDLE_DIRECTORY EVIDENCE_DIRECTORY' >&2
  exit 2
fi
BUNDLE=$1
EVIDENCE=$2
DEVICE_OUTPUT=/sdcard/Android/media/dev.cinematv/phone-output
python3 scripts/hybrid/artifacts.py verify-device-bundle --directory "$BUNDLE"
if [ -d "$EVIDENCE" ] && [ -n "$(find "$EVIDENCE" -mindepth 1 -print -quit)" ]; then
  echo 'Evidence directory is not empty; use a new directory for this run' >&2
  exit 1
fi
mkdir -p "$EVIDENCE"
PHONE_ADB="${ANDROID_HOME:?}/platform-tools/adb"
PHONE_SERIAL=${ANDROID_SERIAL:-emulator-5554}
phone_adb() { timeout --signal=TERM --kill-after=5s 30s "$PHONE_ADB" -s "$PHONE_SERIAL" "$@"; }
finish() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  phone_adb logcat -d -s AndroidRuntime:E > "$EVIDENCE/crashes.txt" 2>&1
  phone_adb shell dumpsys activity top > "$EVIDENCE/activity.txt" 2>&1
  phone_adb pull "$DEVICE_OUTPUT/ui-evidence" "$EVIDENCE/" >/dev/null 2>&1
  printf 'harness_exit_status=%s\n' "$status" > "$EVIDENCE/status.txt"
  exit "$status"
}
trap finish EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
bash scripts/hybrid/wait-device.sh "$PHONE_ADB" "$PHONE_SERIAL" "$EVIDENCE/startup.txt"
phone_adb shell svc power stayon true
phone_adb shell wm dismiss-keyguard
phone_adb shell wm size 1080x1920
phone_adb shell wm density 420
phone_adb shell settings put system screen_off_timeout 2147483647
phone_adb shell settings put secure immersive_mode_confirmations confirmed
{
  printf 'api='; phone_adb shell getprop ro.build.version.sdk
  printf 'fingerprint='; phone_adb shell getprop ro.build.fingerprint
  printf 'characteristics='; phone_adb shell getprop ro.build.characteristics
  phone_adb shell wm size
  phone_adb shell wm density
} > "$EVIDENCE/device.txt"
phone_adb install -r "$BUNDLE/app-debug.apk"
phone_adb install -r "$BUNDLE/app-debug-androidTest.apk"
phone_adb shell mkdir -p "$DEVICE_OUTPUT"
phone_adb shell rm -rf "$DEVICE_OUTPUT/ui-evidence"
CLASSES=$(python3 scripts/hybrid/phone.py --classes)
set +e
timeout --signal=TERM --kill-after=15s 12m "$PHONE_ADB" -s "$PHONE_SERIAL" shell am instrument -w -r \
  -e class "$CLASSES" -e additionalTestOutputDir "$DEVICE_OUTPUT" \
  dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner | tee "$EVIDENCE/instrumentation.txt"
STATUS=${PIPESTATUS[0]}
set -e
test "$STATUS" -eq 0
phone_adb pull "$DEVICE_OUTPUT/ui-evidence" "$EVIDENCE/" >/dev/null
python3 scripts/hybrid/phone.py --bundle "$BUNDLE" --evidence "$EVIDENCE"
