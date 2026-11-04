# CinemaTV brand — SVG masters

**Art direction:** predominantly black, subtle horizontal motion-blurred cool-grey streaks, centered white rounded media-player glyph. The horizontal streaks are motion-blur **illustrations**, not continuously animated launcher graphics; Android launchers use static drawable resources.

## Source of truth
- `cinematv-player-mark.svg` — transparent high-contrast symbol.
- `cinematv-app-icon.svg` — 1024 × 1024 source with centered symbol.
- `cinematv-background.svg` — 1024 × 1024 adaptive launcher background only.
- `cinematv-tv-banner.svg` — 1280 × 720 source, exports to 320 × 180 TV banner.

**Center alignment guarantee:** the TV mark is transformed around **x = 640** and the wordmark uses `x="640" text-anchor="middle"`. Both elements are centered in the 1280px viewport, not just visually balanced.

Run `bash scripts/render-branding.sh` after installing `librsvg2-bin` (Debian/Ubuntu). PNGs under `app/src/main/res/drawable-nodpi` are committed by the branding GitHub workflow whenever the SVG sources change; the Android APK does not parse SVG files at runtime.

Adaptive icons use `@mipmap/ic_launcher` with a separate `@drawable/ic_launcher_foreground` vector to preserve legibility inside manufacturer masks. TV banner uses `@drawable/tv_banner` (320 × 180 PNG).

Do not embed the original reference photographs, third-party sports marks, copyrighted lettering or other unlicensed content.
