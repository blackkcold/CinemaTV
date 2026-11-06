#!/usr/bin/env bash
# Own the startup barrier: upstream v2 sends input immediately after boot_completed.
# https://github.com/ReactiveCircus/android-emulator-runner/issues/489
set -euo pipefail

tv_adb() {
  timeout --signal=TERM --kill-after=1s 5s "$TV_ADB" -s "$TV_SERIAL" "$@"
}

tv_probe() {
  local remaining=$((TV_READY_DEADLINE - SECONDS))
  [ "$remaining" -gt 0 ] || return 124
  if [ "$remaining" -gt 5 ]; then remaining=5; fi
  timeout --signal=TERM --kill-after=1s "${remaining}s" \
    "$TV_ADB" -s "$TV_SERIAL" "$@" 2>> "$TV_STARTUP_EVIDENCE/readiness.txt"
}

tv_ready_sample() {
  local state boot service response
  state=$(tv_probe get-state) || return 1
  printf 'transport=%s\n' "$state" >> "$TV_STARTUP_EVIDENCE/readiness.txt"
  [ "${state//$'\r'/}" = device ] || return 1
  boot=$(tv_probe shell getprop sys.boot_completed) || return 1
  printf 'boot_completed=%s\n' "$boot" >> "$TV_STARTUP_EVIDENCE/readiness.txt"
  [ "${boot//$'\r'/}" = 1 ] || return 1
  for service in input window activity; do
    response=$(tv_probe shell service check "$service") || return 1
    printf '%s\n' "$response" >> "$TV_STARTUP_EVIDENCE/readiness.txt"
    [ "${response//$'\r'/}" = "Service $service: found" ] || return 1
  done
}

tv_wait_ready() {
  local budget=$1 interval=${2:-1} stable=0 unlock_status
  TV_READY_DEADLINE=$((SECONDS + budget))
  while [ "$SECONDS" -lt "$TV_READY_DEADLINE" ]; do
    if ! kill -0 "$TV_EMULATOR_PID" 2>/dev/null; then
      echo 'Emulator exited before its framework became ready' >&2
      return 1
    fi
    if tv_ready_sample; then stable=$((stable + 1)); else stable=0; fi
    printf 'elapsed=%s consecutive_ready=%s\n' "$SECONDS" "$stable" >> "$TV_STARTUP_EVIDENCE/readiness.txt"
    if [ "$stable" -ge 2 ]; then
      # The service can disappear again during a framework restart. Retry only
      # this pre-install unlock, rechecking consecutive readiness each time.
      if tv_probe shell input keyevent 82 >> "$TV_STARTUP_EVIDENCE/readiness.txt"; then
        echo 'Framework services and unlock are ready' >> "$TV_STARTUP_EVIDENCE/readiness.txt"
        return 0
      else
        unlock_status=$?
        printf 'unlock_exit_status=%s\n' "$unlock_status" >> "$TV_STARTUP_EVIDENCE/readiness.txt"
      fi
      stable=0
    fi
    sleep "$interval"
  done
  echo 'Timed out waiting for stable Android services and a successful unlock' >&2
  return 1
}

tv_finish() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if [ -n "$TV_EMULATOR_PID" ]; then
    tv_adb shell getprop > "$TV_STARTUP_EVIDENCE/final-properties.txt" 2>&1
    tv_adb shell dumpsys window > "$TV_STARTUP_EVIDENCE/final-window.txt" 2>&1
    tv_adb shell dumpsys activity top > "$TV_STARTUP_EVIDENCE/final-activity.txt" 2>&1
    tv_adb logcat -d -t 1500 > "$TV_STARTUP_EVIDENCE/final-logcat.txt" 2>&1
    # Only the process group created by this invocation belongs to us. Never
    # kill a pre-existing device just because it occupies the default ADB port.
    kill -TERM -- "-$TV_EMULATOR_PID" 2>/dev/null
    for _ in {1..10}; do
      kill -0 -- "-$TV_EMULATOR_PID" 2>/dev/null || break
      sleep 0.5
    done
    kill -KILL -- "-$TV_EMULATOR_PID" 2>/dev/null
    wait "$TV_EMULATOR_PID" 2>/dev/null
  fi
  printf 'launcher_exit_status=%s\n' "$status" > "$TV_STARTUP_EVIDENCE/status.txt"
  if [ -n "$TV_WORKDIR" ]; then timeout --kill-after=1s 20s rm -rf -- "$TV_WORKDIR"; fi
  exit "$status"
}

tv_main() {
  if [ "$#" -ne 7 ]; then
    echo 'Usage: run-tv-emulator.sh API ARCH BUNDLE EVIDENCE MODE SIZE DENSITY' >&2
    return 2
  fi
  local api=$1 arch=$2 bundle=$3 evidence=${4%/} mode=$5 size=$6 density=$7
  case "$api:$arch:$mode" in
    26:x86:native-fallback|29:x86:provider-policy|36:x86_64:hybrid) ;;
    *) echo 'Unsupported Android TV image or expected presentation' >&2; return 2 ;;
  esac
  case "$size:$density" in
    1920x1080:320|3840x2160:640) ;;
    *) echo 'Unsupported display size/density pair' >&2; return 2 ;;
  esac
  [ -n "$evidence" ] || return 2
  TV_STARTUP_EVIDENCE="${evidence}-emulator"
  if [ -d "$TV_STARTUP_EVIDENCE" ] && [ -n "$(find "$TV_STARTUP_EVIDENCE" -mindepth 1 -print -quit)" ]; then
    echo 'Emulator evidence directory must be empty for this invocation' >&2
    return 1
  fi
  mkdir -p "$TV_STARTUP_EVIDENCE"
  TV_WORKDIR=
  TV_EMULATOR_PID=
  TV_SERIAL=emulator-5554
  trap tv_finish EXIT
  trap 'exit 143' TERM
  trap 'exit 130' INT
  local sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  [ -n "$sdk" ] || { echo 'ANDROID_HOME must identify the installed SDK' >&2; return 2; }
  TV_ADB="$sdk/platform-tools/adb"
  local sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sdkmanager" ]; then sdkmanager=$(command -v sdkmanager); fi
  local avdmanager="${sdkmanager%/*}/avdmanager"
  if [ ! -x "$sdkmanager" ] || [ ! -x "$avdmanager" ]; then
    echo 'The runner must provide Android SDK command-line tools' >&2
    return 2
  fi
  TV_WORKDIR=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cinema-tv-emulator.XXXXXX")
  export ANDROID_HOME="$sdk" ANDROID_AVD_HOME="$TV_WORKDIR/avds"
  mkdir -p "$ANDROID_AVD_HOME"
  printf 'api=%s\narch=%s\ntarget=android-tv\nprofile=tv_1080p\nemulator_build=15004761\nrequested_size=%s\nrequested_density=%s\n' \
    "$api" "$arch" "$size" "$density" > "$TV_STARTUP_EVIDENCE/config.txt"

  # Use the runner's SDK tools and the same official, exact-build archive as
  # the previous action. The emulator and AVD live only in our temporary root.
  timeout --kill-after=5s 180s bash -c 'yes | "$1" --licenses' _ "$sdkmanager" \
    > "$TV_STARTUP_EVIDENCE/sdk-licenses.txt" 2>&1
  timeout --kill-after=5s 300s "$sdkmanager" --install platform-tools \
    "system-images;android-$api;android-tv;$arch" > "$TV_STARTUP_EVIDENCE/sdk-install.txt" 2>&1
  timeout --kill-after=5s 240s curl --fail --location --connect-timeout 10 --max-time 180 \
    --retry 2 --retry-delay 2 --output "$TV_WORKDIR/emulator.zip" \
    'https://dl.google.com/android/repository/emulator-linux_x64-15004761.zip' \
    > "$TV_STARTUP_EVIDENCE/download.txt" 2>&1
  timeout --kill-after=5s 60s unzip -q "$TV_WORKDIR/emulator.zip" -d "$TV_WORKDIR"
  local emulator="$TV_WORKDIR/emulator/emulator"
  timeout --kill-after=1s 5s "$emulator" -version > "$TV_STARTUP_EVIDENCE/version.txt" 2>&1
  grep -Eq 'build_id 15004761([^0-9]|$)' "$TV_STARTUP_EVIDENCE/version.txt"
  timeout --kill-after=5s 120s "$avdmanager" create avd --name cinematv-ci \
    --package "system-images;android-$api;android-tv;$arch" --device tv_1080p \
    > "$TV_STARTUP_EVIDENCE/avd-create.txt" 2>&1 <<< no
  printf '\nhw.cpu.ncore=2\nhw.ramSize=2048\n' >> "$ANDROID_AVD_HOME/cinematv-ci.avd/config.ini"
  cp "$ANDROID_AVD_HOME/cinematv-ci.avd/config.ini" "$TV_STARTUP_EVIDENCE/avd-config.ini"
  # Refuse to use another invocation's device. This port is reserved for this
  # fresh CI guest; all shutdown operations remain scoped to our process group.
  local devices
  devices=$(timeout --kill-after=1s 5s "$TV_ADB" devices)
  if printf '%s\n' "$devices" | grep -qE '^emulator-5554[[:space:]]'; then
    echo 'The dedicated emulator port is already occupied' >&2
    return 1
  fi
  setsid "$emulator" -port 5554 -avd cinematv-ci -no-window -no-audio \
    -gpu lavapipe -feature -Vulkan -no-snapshot -no-boot-anim -memory 2048 \
    -skin 1920x1080 > "$TV_STARTUP_EVIDENCE/emulator.log" 2>&1 &
  TV_EMULATOR_PID=$!
  tv_wait_ready 240
  export ANDROID_SERIAL="$TV_SERIAL" EMULATOR_PORT=5554
  # run-device.sh owns this separate, still-empty evidence directory. It
  # configures/validates the requested 4K size before installing either APK.
  bash scripts/hybrid/run-device.sh "$bundle" "$evidence" "$mode" "$size" "$density"
}

if [[ "${BASH_SOURCE[0]}" = "$0" ]]; then tv_main "$@"; fi
