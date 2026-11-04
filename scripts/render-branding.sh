#!/usr/bin/env bash
# Manual candidate generation only. Reviewed runtime PNGs must never be replaced by CI.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 1 ]]; then
  echo "Usage: bash scripts/render-branding.sh /absolute/path/to/review-output" >&2
  echo "Review candidate pixels before deliberately replacing assets and updating their approved hashes." >&2
  exit 2
fi
output="$(realpath -m "$1")"
resources="$(realpath app/src/main/res)"
if [[ "$output" == "$resources" || "$output" == "$resources/"* ]]; then
  echo "Refusing direct runtime-resource output; generate candidates outside app/src/main/res." >&2
  exit 2
fi
command -v rsvg-convert >/dev/null || { echo "Install librsvg2-bin (rsvg-convert)"; exit 1; }
mkdir -p "$output"
rsvg-convert -w 512 -h 512 design/branding/cinematv-app-icon.svg -o "$output/ic_launcher_legacy.png"
rsvg-convert -w 512 -h 512 design/branding/cinematv-background.svg -o "$output/ic_launcher_bg.png"
rsvg-convert -w 320 -h 180 design/branding/cinematv-tv-banner.svg -o "$output/tv_banner.png"
file "$output"/*.png
