#!/usr/bin/env bash
# android-emulator-runner@v2 splits script lines and executes each via sh -c.
# Keep the workflow invocation on one line; compound Bash syntax belongs here.
set -euo pipefail
if [ "$#" -ne 4 ]; then
  echo 'Usage: run-device-qa-target.sh TARGET WIDTHxHEIGHT DENSITY TEST_CLASSES' >&2
  exit 2
fi
TARGET=$1
shift
case "$TARGET" in
  phone|tv1080)
    : "${RUNNER_TEMP:?RUNNER_TEMP is required for the isolated official server}"
    exec python3 scripts/libretv-contracts/android_acceptance.py \
      --directory "$RUNNER_TEMP/official-libretv-android" \
      --report qa/official-lifecycle.json -- bash scripts/run-device-qa.sh "$@"
    ;;
  tv4k|compat26|tvNative|tv29|tv31|tv36)
    exec bash scripts/run-device-qa.sh "$@"
    ;;
  *)
    echo 'Unknown device QA target' >&2
    exit 2
    ;;
esac
