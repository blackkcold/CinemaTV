#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/src/androidTest/assets
if [ -f app/src/androidTest/assets/playback-fixture.mp4 ] && [ "${1:-}" != "--regenerate" ]; then
  exit 0
fi
# Original synthetic test pattern and tone; shipped only in the instrumentation APK.
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i 'color=c=0x203e52:size=320x180:rate=12' \
  -f lavfi -i 'sine=frequency=220:sample_rate=44100' -t 30 \
  -c:v libx264 -preset fast -crf 35 -c:a aac -b:a 24k -movflags +faststart \
  app/src/androidTest/assets/playback-fixture.mp4
