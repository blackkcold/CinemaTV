#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

fixture=app/src/androidTest/assets/playback-fixture.mp4
# Reviewed original synthetic bytes. Update only with the fixture and its README.
expected_sha256=952ce514387abebceaca2a9071559f1242caefbfd421ba3cdd5c18ac36187d21

if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--regenerate" ) ]]; then
  echo "Usage: bash scripts/generate-test-media.sh [--regenerate]" >&2
  exit 2
fi

if [[ "${1:-}" == "--regenerate" ]]; then
  if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "Explicit fixture regeneration requires FFmpeg; normal builds do not." >&2
    exit 1
  fi
  mkdir -p "$(dirname "$fixture")"
  temp_dir=$(mktemp -d "$(dirname "$fixture")/.playback-fixture.XXXXXX")
  trap 'rm -rf "$temp_dir"' EXIT
  # Original synthetic picture and tone, never downloaded or production content.
  # Preserve the existing fixture if encoding fails. Do not silently update its pin.
  ffmpeg -y -hide_banner -loglevel error \
    -f lavfi -i 'color=c=0x203e52:size=320x180:rate=12' \
    -f lavfi -i 'sine=frequency=220:sample_rate=44100' -t 30 \
    -c:v libx264 -preset fast -crf 35 -c:a aac -b:a 24k -movflags +faststart \
    "$temp_dir/playback-fixture.mp4"
  mv "$temp_dir/playback-fixture.mp4" "$fixture"
fi

if [[ ! -f "$fixture" ]]; then
  echo "Missing committed synthetic fixture: $fixture. Restore it from Git." >&2
  exit 1
fi
# Both paths are offline; shasum also supports developer machines without coreutils.
if command -v sha256sum >/dev/null 2>&1; then
  checksum=$(sha256sum "$fixture")
elif command -v shasum >/dev/null 2>&1; then
  checksum=$(shasum -a 256 "$fixture")
else
  echo "Fixture validation requires sha256sum or shasum." >&2
  exit 1
fi
actual_sha256=${checksum%% *}
if [[ "$actual_sha256" != "$expected_sha256" ]]; then
  echo "Synthetic fixture SHA-256 mismatch: expected $expected_sha256, got $actual_sha256." >&2
  echo "Restore the committed bytes, or review deliberate regeneration using app/src/androidTest/assets/README.md." >&2
  exit 1
fi
echo "Verified original synthetic instrumentation fixture: $actual_sha256"
