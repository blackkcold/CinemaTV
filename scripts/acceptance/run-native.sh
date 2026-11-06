#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ]; then echo 'Usage: run-native.sh LANE BUNDLE EVIDENCE' >&2; exit 2; fi
LANE=$1
BUNDLE=$2
EVIDENCE=$3
[ "${GITHUB_ACTIONS:-}" = true ] || { echo 'Native acceptance is restricted to disposable GitHub emulator guests' >&2; exit 2; }
QEMU=$(timeout --kill-after=2s 10s "${ANDROID_HOME:?}/platform-tools/adb" -s "${ANDROID_SERIAL:-emulator-5554}" shell getprop ro.kernel.qemu | tr -d '\r')
[ "$QEMU" = 1 ] || { echo 'Refusing a non-emulator target' >&2; exit 2; }
finish() {
  local original=$?
  trap - EXIT INT TERM
  set +e
  python3 scripts/acceptance/live_diagnostics.py --evidence "$EVIDENCE" --exit-code "$original"
  exit "$original"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
case "$LANE" in
  native-phone|native1080)
    python3 scripts/libretv-contracts/android_acceptance.py \
      --directory "${RUNNER_TEMP:?}/official-libretv-android" \
      --report qa/official-lifecycle.json -- \
      python3 scripts/acceptance/native.py run --lane "$LANE" --bundle "$BUNDLE" --evidence "$EVIDENCE"
    ;;
  native-compat26|native4k|account26|account33|recommendation33|updates-phone|updates-tv)
    python3 scripts/acceptance/native.py run --lane "$LANE" --bundle "$BUNDLE" --evidence "$EVIDENCE"
    ;;
  *) echo 'Unknown native acceptance lane' >&2; exit 2 ;;
esac
