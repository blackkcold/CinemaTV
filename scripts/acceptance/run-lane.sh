#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ]; then echo 'Usage: run-lane.sh LANE BUNDLE EVIDENCE' >&2; exit 2; fi
case "$1" in
  phone35) exec bash scripts/hybrid/run-phone.sh "$2" "$3" ;;
  native-*|native1080|native4k|account26|account33|recommendation33|updates-phone|updates-tv)
    exec bash scripts/acceptance/run-native.sh "$1" "$2" "$3" ;;
  *) echo 'TV lanes must use the actual Android TV launcher' >&2; exit 2 ;;
esac
