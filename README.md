# CinemaTV

**CinemaTV** is an open-source, native Android TV / Google TV video browsing and playback client for a **self-hosted LibreTV** instance.

**Release status: v0.1.0 Preview (debug-signed)**. Main branch has additional unshipped development commits; GitHub Actions builds are **not** hardware-certified. Stable release is blocked by [Release Gate #1](https://github.com/blackkcold/CinemaTV/issues/1).

[Download preview APK from Releases](https://github.com/blackkcold/CinemaTV/releases/tag/v0.1.0-preview) · [CI builds](https://github.com/blackkcold/CinemaTV/actions/workflows/android.yml) · [中文安装与设备测试指南](docs/INSTALL.md) · [Release readiness tracker](https://github.com/blackkcold/CinemaTV/issues/1)

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

## Main-branch features not yet in v0.1.0 Preview

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
