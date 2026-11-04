#!/usr/bin/env bash
set -euo pipefail
adb shell wm size "$1"
adb shell wm density "$2"
set +e
gradle --no-daemon :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$3"
CINEMA_TEST_STATUS=$?
set -e
mkdir -p qa
CINEMA_EVIDENCE_DIR=$(find app/build/outputs/connected_android_test_additional_output -type d -name ui-evidence -print -quit 2>/dev/null || true)
if [ -n "$CINEMA_EVIDENCE_DIR" ]; then cp -R "$CINEMA_EVIDENCE_DIR" qa/; fi
adb logcat -d -s AndroidRuntime:E > qa/android-crash.log
exit "$CINEMA_TEST_STATUS"
