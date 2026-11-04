# CinemaTV

**CinemaTV** is an open-source, native Android TV / Google TV and Android phone video browsing and playback client for a **self-hosted LibreTV** instance.

**Release status: v0.2.0 Preview (debug-signed, when its CI finishes)**. Main branch is not hardware-certified. Stable release is blocked by [Release Gate #1](https://github.com/blackkcold/CinemaTV/issues/1).

[Download preview APK from Releases](https://github.com/blackkcold/CinemaTV/releases/tag/v0.2.1-preview) · [CI builds](https://github.com/blackkcold/CinemaTV/actions/workflows/android.yml) · [中文安装与设备测试指南](docs/INSTALL.md) · [Release readiness tracker](https://github.com/blackkcold/CinemaTV/issues/1)

## Features available in preview

- **Native TV presentation**: immersive cinematic hero, dark/frosted-glass top navigation, landscape content rails, center-focused poster Cover Flow, movies, series, search, library, settings and live.
- **LibreTV protocol adapter**: auth/session cookie, recommended movies/TV, aggregate search, detail/episodes, optional custom CMS sources and M3U subscriptions.
- **Media3**: integrated ExoPlayer with seek, per-episode resume, TV audio/subtitle/video track panels, speed selection, decoder fallback, 256 MiB bounded VOD cache and limited same-title/same-episode source failover.
- **Live**: M3U discovery with XMLTV now/next and a future-program guide.
- **Local library**: five-state shelves, favorites, watched items and per-episode progress.
- **Optional Node 22 BFF**: bearer-authenticated API with optional TMDB title/year-verified 16:9 backdrops; requires self-managed service configuration. No content feeds are included.
- **Continuous verification**: actual Android JUnit tests, AndroidTest APK compile, Node BFF tests, APK archive/signature validation and a separate emulator smoke workflow.

## Build

Requirements: JDK 17, Android SDK compileSdk 36 / build tools 36.0.0, Gradle 8.13 (or use GitHub Actions).

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
npm --prefix backend/tv-bff test
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Setup

1. Install the preview APK on an Android TV / Google TV device (API 26+).
2. In Settings, supply the URL for **your own** LibreTV server and sign in if required.
3. Use the LibreTV server's configured CMS sources or add your own authorized sources.
4. Browse, search and play content that you are lawfully permitted to access.
5. For live TV, configure your own M3U/XMLTV subscriptions.

**No media streams or copyrighted posters are bundled with this repository.** See the [installation guide](docs/INSTALL.md).

## Architecture

```text
Native TV UI (Compose)
  ├─ CinemaViewModel / focus-aware navigation
  ├─ LibreTvRepository ──────────────> LibreTV backend
  └─ Media3 playback + bounded cache
Optional Node TV-BFF ───────────────> LibreTV backend
```

See [architecture notes](docs/ARCHITECTURE.md) and [handoff rules](AGENTS.md).

## Known limitations

The current UI uses performant translucent/frosted surfaces rather than a fully validated real-time optical Liquid Glass shader. It has not been visually signed off against the provided screenshots on actual TVs. It does **not** yet offer a full custom TV subtitle/audio-track panel, multi-backend playback, device pairing, metadata enrichment, smart source health dashboard, or cross-device sync. The preview has not passed a hardware compatibility matrix or production key signing. These items are tracked in [issue #1](https://github.com/blackkcold/CinemaTV/issues/1).

## Licensing and safety

CinemaTV-authored code is under the [MIT License](LICENSE). It is an independent original Android client; no DpadTV, FongMi or LibreTV code is copied into this repository. LibreTV and third-party libraries have independent licenses. Public deployment should use HTTPS; never check credentials, tokens, private signing keys or user source subscriptions into GitHub.

## Features added since v0.1.0 Preview

Main includes experimental opt-in Android 13+ AGSL specular glass, progressive
LibreTV NDJSON search, remote focus restoration, source probes, error classification,
enhanced live EPG, five-state library and a QA-gated release signing workflow.
Code compilation is not a replacement for physical TV acceptance testing.

**Stable release is not certified.** True backdrop refraction, reference-image
pixel matching, additional hardware media compatibility, secure QR pairing,
full secondary playback engine, long-run stability tests and production signing
remain open in Release Gate #1.

### TMDB / metadata notice
This product may use the TMDB API through an independently configured BFF but
is not endorsed or certified by TMDB. Users/operators must follow TMDB's
attribution, API and image-use conditions. No TMDB access key is bundled.

## Brand assets and phone support (development)

- All source graphics are **original editable SVG** under [`design/branding/`](design/branding/).
- The app icon is a white minimal player glyph on a predominantly black field with restrained horizontal motion-blurred light streaks.
- The **TV banner is 320 × 180 px at runtime**, designed from a 1280 × 720 SVG. Both the player symbol and CinemaTV wordmark use the precise horizontal centerline (`x=640` in source).
- Android launchers use adaptive icon foreground/background resources; TV launchers use a PNG banner. Android PNGs are regenerated automatically when SVG sources change.
- Phone builds offer a touch-first UI with portrait orientation, home, browse, search, detail, playback, live, library and settings. The same backend credentials and player engine are shared with TV.
- Device selection is based on Android television UI mode; a phone is **not** shown the TV D-pad layout.
- Android emulator smoke tests validate launch/navigation. This does not certify any physical TV, codec, GPU or full liquid-glass implementation.

See [branding guidance](design/branding/README.md) and [installation guide](docs/INSTALL.md). Stable release is still gated by physical hardware certification.

## New branding previews

| Standard app launcher icon | Center-aligned Android TV banner |
|---|---|
| ![CinemaTV launcher icon](app/src/main/res/drawable-nodpi/ic_launcher_legacy.png) | ![CinemaTV TV banner](app/src/main/res/drawable-nodpi/tv_banner.png) |

