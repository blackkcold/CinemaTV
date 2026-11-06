#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ]; then echo 'Usage: run-native.sh LANE BUNDLE EVIDENCE' >&2; exit 2; fi
LANE=$1
BUNDLE=$2
EVIDENCE=$3
case "$LANE" in
  native-phone|native1080)
    exec python3 scripts/libretv-contracts/android_acceptance.py \
      --directory "${RUNNER_TEMP:?}/official-libretv-android" \
      --report qa/official-lifecycle.json -- \
      python3 scripts/acceptance/native.py run --lane "$LANE" --bundle "$BUNDLE" --evidence "$EVIDENCE"
    ;;
  native-compat26|native4k|account26|account33|recommendation33|updates-phone|updates-tv)
    exec python3 scripts/acceptance/native.py run --lane "$LANE" --bundle "$BUNDLE" --evidence "$EVIDENCE"
    ;;
  *) echo 'Unknown native acceptance lane' >&2; exit 2 ;;
esac
