#!/usr/bin/env bash
set -uo pipefail
mkdir -p probe-evidence
adb shell getprop > probe-evidence/device-properties.txt
set +e
gradle --no-daemon --max-workers=2 -p experiments/ranksys-android :app:connectedDebugAndroidTest
STATUS=$?
adb logcat -d -s RankSysProbe > probe-evidence/runtime.txt
exit "$STATUS"
