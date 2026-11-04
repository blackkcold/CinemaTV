# CinemaTV

**CinemaTV** is an open-source, native Android TV / Google TV video browsing and playback client for a **self-hosted LibreTV** instance.

**Release status: v0.1.0 Preview (debug-signed)**. This is an installable technical preview, not a certified stable product or finished visual replica.

[Download preview APK from Releases](https://github.com/blackkcold/CinemaTV/releases/tag/v0.1.0-preview) · [CI builds](https://github.com/blackkcold/CinemaTV/actions/workflows/android.yml) · [中文安装与设备测试指南](docs/INSTALL.md) · [Release readiness tracker](https://github.com/blackkcold/CinemaTV/issues/1)

## Features available in preview

- **Native TV presentation**: immersive cinematic hero, dark/frosted-glass top navigation, landscape content rails, center-focused poster Cover Flow, movies, series, search, library, settings and live.
- **LibreTV protocol adapter**: auth/session cookie, recommended movies/TV, aggregate search, detail/episodes, optional custom CMS sources and M3U subscriptions.
- **Media3**: integrated ExoPlayer with seek, resume, remote controls, 256 MiB bounded VOD cache, playback retry and limited same-title/same-episode automatic source fallback.
- **Live**: M3U channel discovery and current XMLTV program when configured.
- **Local library**: favorite media, watched items and playback progress.
- **Optional Node 22 BFF**: independent backend API for unified search/metadata, with bearer authentication, no content feeds, and HTTP integration tests.
- **Continuous verification**: Gradle Android Debug APK build, Node BFF tests, APK archive and debug signature verification on GitHub Actions.

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
