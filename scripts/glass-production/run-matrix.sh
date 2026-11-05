#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 || ! "$1" =~ ^(26|31|35)$ || ! "$2" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Usage: run-matrix.sh SDK SOURCE_SHA' >&2
  exit 2
fi
# Both viewports reuse build-job APKs; no Gradle daemon or compilation competes
# with the software GPU. Each complete viewport, including setup, is bounded.
timeout --signal=TERM --kill-after=10s 10m python3 scripts/glass-production/run_device.py --sdk "$1" --sha "$2" --layouts tv
if [[ "$1" == 35 ]]; then
  timeout --signal=TERM --kill-after=10s 10m python3 scripts/glass-production/run_device.py --sdk "$1" --sha "$2" --layouts phone
fi
