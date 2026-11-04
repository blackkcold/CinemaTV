#!/usr/bin/env bash
set -euo pipefail
adb shell wm size "$1"
adb shell wm density "$2"
set +e
gradle --no-daemon :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$3"
CINEMA_TEST_STATUS=$?
set -e
mkdir -p qa
adb pull /sdcard/Android/data/dev.cinematv/files/ui-evidence qa/ || true
adb logcat -d -s AndroidRuntime:E > qa/android-crash.log
exit "$CINEMA_TEST_STATUS"
