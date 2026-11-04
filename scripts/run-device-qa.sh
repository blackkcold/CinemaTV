#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo 'Usage: run-device-qa.sh WIDTHxHEIGHT DENSITY TEST_CLASSES' >&2
  exit 2
fi
mkdir -p qa
QA_STARTED=$SECONDS
QA_LOG_PID=
QA_HEARTBEAT_PID=

qa_adb() { timeout --signal=TERM --kill-after=5s 20s adb "$@"; }

run_stage() {
  local name=$1 started=$SECONDS status=0
  shift
  "$@" || status=$?
  printf '%s\t%s\t%s\n' "$name" "$status" "$((SECONDS - started))" >> qa/stages.tsv
  return "$status"
}

finish() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if [ -n "$QA_HEARTBEAT_PID" ]; then
    kill "$QA_HEARTBEAT_PID" 2>/dev/null
    wait "$QA_HEARTBEAT_PID" 2>/dev/null
  fi
  if [ -n "$QA_LOG_PID" ]; then
    kill "$QA_LOG_PID" 2>/dev/null
    wait "$QA_LOG_PID" 2>/dev/null
  fi
  timeout --kill-after=5s 5s free -m > qa/host-memory-after.txt || true
  # Only bounded status metadata, never process arguments, environments or dumps.
  # This runs before emulator-runner tears the emulator down on a normal finish.
  {
    printf 'harness_exit_status=%s\n' "$status"
    printf 'adb_state='
    timeout --signal=TERM --kill-after=5s 5s adb get-state
    printf '\nemulator_processes (pid ppid state name):\n'
    timeout --kill-after=5s 5s ps -eo pid=,ppid=,stat=,comm= | awk '$4 ~ /^(emulator|qemu-system)/'
  } > qa/emulator-state.txt 2>&1
  if [ "$status" -ne 0 ]; then
    timeout --kill-after=5s 10s sudo -n dmesg -T | tail -n 200 > qa/host-kernel.log || true
    # UTP may not export device files when instrumentation stalls or is cancelled.
    if [ ! -d qa/ui-evidence ]; then
      local device_evidence
      device_evidence=$(qa_adb shell find /sdcard/Android/media /sdcard/Android/data -type d -name ui-evidence 2>/dev/null | tr -d '\r' | head -n 1)
      if [ -n "$device_evidence" ]; then
        timeout --signal=TERM --kill-after=5s 30s adb pull "$device_evidence" qa/ || true
      fi
    fi
    qa_adb logcat -d -v threadtime > qa/device.log || true
    qa_adb shell dumpsys activity top > qa/activity.txt || true
    qa_adb shell dumpsys window windows > qa/windows.txt || true
  fi
  qa_adb logcat -d -s AndroidRuntime:E > qa/android-crash.log || true
  printf 'device-total\t%s\t%s\n' "$status" "$((SECONDS - QA_STARTED))" >> qa/stages.tsv
  exit "$status"
}
trap finish EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

device_window_ready() {
  local boot=$1 windows=$2
  # API 33 omits mDisplayFrozen when normal. Require observable screen/draw
  # readiness instead; an explicit frozen display still fails this sample.
  [ "$boot" = 1 ] &&
    [[ "$windows" == *"mAwake=true"* &&
       "$windows" == *"mScreenOnFully=true"* &&
       "$windows" == *"mWindowManagerDrawComplete=true"* &&
       "$windows" != *"mDisplayFrozen=true"* ]] &&
    printf '%s\n' "$windows" | grep -E 'mCurrentFocus=Window\{[^}]+ (com.google.android.apps.nexuslauncher|com.android.launcher3)/' > /dev/null
}

wait_for_device_window() {
  local deadline=$((SECONDS + 60)) boot windows stable=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    boot=$(qa_adb shell getprop sys.boot_completed | tr -d '\r') || return $?
    windows=$(qa_adb shell dumpsys window) || return $?
    printf '%s\n' "$windows" > qa/setup-windows.txt
    # Never dismiss an ANR, crash, permission prompt or arbitrary system dialog.
    # Preserve the exact blocker and fail before starting instrumentation.
    if [[ "$windows" == *"Application Not Responding:"* || "$windows" == *"Application Error:"* ]]; then
      echo 'Device setup blocked by an ANR/crash window; see setup-windows.txt' >&2
      return 1
    fi
    if device_window_ready "$boot" "$windows"; then
      stable=$((stable + 1))
      if [ "$stable" -ge 2 ]; then return 0; fi
    else
      stable=0
    fi
    sleep 1
  done
  echo 'Timed out waiting for boot and a focused emulator launcher' >&2
  return 1
}

configure_device() {
  # Wake through PowerManager, not an input event queued to a cold launcher.
  # Wait both before and after configuration changes: boot_completed alone
  # does not imply the launcher has a window capable of accepting input.
  qa_adb shell svc power stayon true &&
    qa_adb shell wm dismiss-keyguard &&
    wait_for_device_window &&
    qa_adb shell wm size "$1" &&
    qa_adb shell wm density "$2" &&
    # Hide the phone emulator's first-use tutorial over the TV test host.
    qa_adb shell settings put secure immersive_mode_confirmations confirmed &&
    qa_adb shell settings put system screen_off_timeout 2147483647 &&
    wait_for_device_window
}

# Every setup operation is bounded too, not only Gradle instrumentation.
run_stage device-setup configure_device "$1" "$2"
timeout --kill-after=5s 5s free -m > qa/host-memory-before.txt || true

# Keep main's continuously captured trace, with a finite lifetime and cleanup.
timeout --signal=TERM --kill-after=5s 14m adb logcat -v threadtime \
  'CinemaTVQa:I' 'TestRunner:I' 'AndroidRuntime:E' '*:S' > qa/live-test.log 2>&1 &
QA_LOG_PID=$!
timeout --signal=TERM --kill-after=5s 18m python3 -u - qa/live-test.log <<'PY' &
from collections import deque
from pathlib import Path
import sys
import time
trace = Path(sys.argv[1])
started = time.monotonic()
while True:
    time.sleep(30)
    print(f"Device QA is running ({time.monotonic() - started:.0f}s); latest test trace:")
    if trace.exists():
        with trace.open(errors="replace") as log:
            print("".join(deque(log, maxlen=3)), end="")
PY
QA_HEARTBEAT_PID=$!

CINEMA_TEST_STATUS=0
run_stage instrumentation timeout --signal=TERM --kill-after=30s 12m \
  gradle --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$3" || CINEMA_TEST_STATUS=$?
CINEMA_EXPORT_STATUS=0
run_stage evidence-export timeout --kill-after=5s 30s python3 scripts/merge-ui-evidence.py \
  --source app/build/outputs/connected_android_test_additional_output \
  --destination qa/ui-evidence || CINEMA_EXPORT_STATUS=$?
if [ "$CINEMA_TEST_STATUS" -ne 0 ]; then exit "$CINEMA_TEST_STATUS"; fi
if [ "$CINEMA_EXPORT_STATUS" -ne 0 ]; then exit "$CINEMA_EXPORT_STATUS"; fi

if [[ "$3" == *TvUiRegressionTest* ]]; then
  run_stage evidence timeout --kill-after=5s 2m python3 scripts/validate-ui-evidence.py \
    --directory qa/ui-evidence --size "$1" --density "$2"
  CINEMA_VIEWPORT=tv1080
  if [ "$1" = 3840x2160 ]; then CINEMA_VIEWPORT=tv4k; fi
  run_stage goldens timeout --kill-after=5s 2m python3 scripts/compare-ui-goldens.py \
    --baseline "design/ui/goldens/$CINEMA_VIEWPORT" --candidate qa/ui-evidence --output qa/golden
fi
