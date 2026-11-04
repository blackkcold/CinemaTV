#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v rsvg-convert >/dev/null || { echo "Install librsvg2-bin (rsvg-convert)"; exit 1; }
mkdir -p app/src/main/res/drawable-nodpi
rsvg-convert -w 512 -h 512 design/branding/cinematv-app-icon.svg -o app/src/main/res/drawable-nodpi/ic_launcher_legacy.png
rsvg-convert -w 512 -h 512 design/branding/cinematv-background.svg -o app/src/main/res/drawable-nodpi/ic_launcher_bg.png
rsvg-convert -w 320 -h 180 design/branding/cinematv-tv-banner.svg -o app/src/main/res/drawable-nodpi/tv_banner.png
file app/src/main/res/drawable-nodpi/*.png
