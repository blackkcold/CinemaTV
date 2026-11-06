#!/usr/bin/env bash
# Boot-complete can precede the emulator's final ADB transport reconnect.
set -euo pipefail
if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
  echo 'Usage: wait-device.sh ADB_BINARY SERIAL DIAGNOSTIC_FILE [TIMEOUT_SECONDS]' >&2
  exit 2
fi
HYBRID_ADB=$1
HYBRID_SERIAL=$2
HYBRID_DIAGNOSTIC=$3
HYBRID_TIMEOUT=${4:-60}
[[ "$HYBRID_TIMEOUT" =~ ^[0-9]+$ ]] && [ "$HYBRID_TIMEOUT" -gt 0 ] && [ "$HYBRID_TIMEOUT" -le 120 ]
HYBRID_DEADLINE=$((SECONDS + HYBRID_TIMEOUT))
HYBRID_STABLE=0
"$HYBRID_ADB" version > "$HYBRID_DIAGNOSTIC" 2>&1
while [ "$SECONDS" -lt "$HYBRID_DEADLINE" ]; do
  HYBRID_STATE=
  HYBRID_BOOT=
  if HYBRID_STATE=$(timeout --signal=TERM --kill-after=2s 5s "$HYBRID_ADB" -s "$HYBRID_SERIAL" get-state 2>> "$HYBRID_DIAGNOSTIC") &&
      [ "$HYBRID_STATE" = device ] &&
      HYBRID_BOOT=$(timeout --signal=TERM --kill-after=2s 5s "$HYBRID_ADB" -s "$HYBRID_SERIAL" shell getprop sys.boot_completed 2>> "$HYBRID_DIAGNOSTIC") &&
      [ "${HYBRID_BOOT//$'\r'/}" = 1 ]; then
    HYBRID_STABLE=$((HYBRID_STABLE + 1))
  else
    HYBRID_STABLE=0
  fi
  printf 'elapsed=%s state=%s boot=%s consecutive_ready=%s\n' "$SECONDS" "$HYBRID_STATE" "${HYBRID_BOOT//$'\r'/}" "$HYBRID_STABLE" >> "$HYBRID_DIAGNOSTIC"
  if [ "$HYBRID_STABLE" -ge 2 ]; then exit 0; fi
  sleep 1
done
echo 'Emulator did not maintain a booted ADB connection before the deadline' >&2
exit 1
