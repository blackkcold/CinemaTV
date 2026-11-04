#!/usr/bin/env bash
set -euo pipefail
adb shell wm size "$1"
adb shell wm density "$2"
# The phone-emulator TV host must not be covered by Android's first-use
# immersive-mode tutorial; physical Android TV does not use this swipe prompt.
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put system screen_off_timeout 2147483647
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
set +e
gradle --no-daemon :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$3"
CINEMA_TEST_STATUS=$?
set -e
mkdir -p qa
CINEMA_EVIDENCE_DIR=$(find app/build/outputs/connected_android_test_additional_output -type d -name ui-evidence -print -quit 2>/dev/null || true)
if [ -n "$CINEMA_EVIDENCE_DIR" ]; then cp -R "$CINEMA_EVIDENCE_DIR" qa/; fi
adb logcat -d -s AndroidRuntime:E > qa/android-crash.log
exit "$CINEMA_TEST_STATUS"
