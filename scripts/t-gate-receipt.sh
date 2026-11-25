#!/usr/bin/env bash
# Collects the facts a T-gate receipt needs from one physical Android TV.
# Read-only on the device: it installs nothing, changes no setting and prints no secret.
# The owner still runs the acceptance steps by hand; this only records the surrounding facts.
#
# usage: scripts/t-gate-receipt.sh <local-apk-path>
set -euo pipefail

ADB="${ADB:-adb}"
APK="${1:-}"
if [ -z "$APK" ]; then
  echo "usage: $0 <local-apk-path>" >&2
  exit 2
fi
if [ ! -f "$APK" ]; then
  echo "apk not found: $APK" >&2
  exit 2
fi
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "no device attached: $ADB get-state failed" >&2
  exit 2
fi

prop() { "$ADB" shell getprop "$1" 2>/dev/null | tr -d '\r'; }

MODEL=$(prop ro.product.model)
VENDOR=$(prop ro.product.manufacturer)
RELEASE=$(prop ro.build.version.release)
SDK=$(prop ro.build.version.sdk)
CHARACTERISTICS=$(prop ro.build.characteristics)
FINGERPRINT=$(prop ro.build.fingerprint)
UI_CONFIG=$("$ADB" shell am get-config 2>/dev/null | grep -m1 "uiMode" | tr -d '\r' || echo "uiMode=unavailable")
FOCUS=$("$ADB" shell dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus" | tr -d '\r' || echo "focus=unavailable")
SOURCE_SHA=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
STAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

if command -v shasum >/dev/null 2>&1; then
  APK_SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
else
  APK_SHA=$(sha256sum "$APK" | cut -d' ' -f1)
fi

APKSIGNER="${APKSIGNER:-}"
if [ -z "$APKSIGNER" ]; then
  if command -v apksigner >/dev/null 2>&1; then
    APKSIGNER=$(command -v apksigner)
  elif [ -n "${ANDROID_HOME:-}" ] && compgen -G "$ANDROID_HOME/build-tools/*/apksigner" >/dev/null; then
    APKSIGNER=$(ls -1 "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)
  fi
fi
if [ -n "$APKSIGNER" ]; then
  SIGNER=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -m1 "SHA-256 digest" | sed 's/.*digest: //' || echo "unreadable")
else
  SIGNER="apksigner not found"
fi

if echo "$CHARACTERISTICS" | grep -q "tv"; then
  TV_IDENTITY="yes (ro.build.characteristics contains tv)"
else
  TV_IDENTITY="NO — this is not a television image"
fi

cat <<EOF
# T-gate receipt

| Field | Value |
|---|---|
| Collected (UTC) | $STAMP |
| Device | $VENDOR $MODEL |
| Android | $RELEASE (API $SDK) |
| Build fingerprint | $FINGERPRINT |
| ro.build.characteristics | $CHARACTERISTICS |
| TV identity | $TV_IDENTITY |
| Configuration | $UI_CONFIG |
| Foreground window | $FOCUS |
| Source commit | $SOURCE_SHA |
| APK | $APK |
| APK SHA-256 | $APK_SHA |
| APK signer SHA-256 | $SIGNER |

Owner checklist — record pass/fail per line, do not infer:

- [ ] Cold start reaches HOME through MainActivity
- [ ] D-pad crosses every rail; no focus trap and no lost focus
- [ ] DETAIL returns to the section that opened it
- [ ] Dialog: Cancel focused by default, one Back closes it, focus returns to its trigger
- [ ] SEARCH: first Back hides the keyboard and keeps the page, second Back follows the page rule
- [ ] Playback: panel -> controls -> previous route, in that order
- [ ] Every browsing route returns HOME; HOME Back leaves the app
- [ ] No crash, ANR or visible stall during the run

Stop rules: a signature mismatch stops the run — never uninstall or clear data to work around
it; a crash stops the run; a phone or emulator result is never relabelled as TV evidence.
EOF
