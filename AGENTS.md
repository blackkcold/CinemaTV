# CinemaTV engineering handoff (AGENTS.md)

This repository is for a native Android TV application with an **explicitly decoupled** LibreTV adapter and an optional BFF. The product direction is Apple TV-like fullscreen movie hero, frosted/dark glass navigation, center-focused poster coverflow, reliable D-pad remote interactions, media search, playback, live EPG and history.

## Non-negotiable constraints
1. Preserve native Kotlin + Jetpack Compose for TV. No WebView shell.
2. LibreTV is an upstream service, not bundled or duplicated. Do not embed credentials or unlicensed streams.
3. Keep provider JSON decoding confined to `app/src/main/java/dev/cinematv/data/`.
4. Keep Media3 lifecycle isolated in `playback/` and `ui/TvPlayerPage.kt`. Playback must free its player.
5. Never install untested GPUs / video-surface blur shaders as a blanket effect; low-end TV fallback first.
6. Review D-pad focus and Back handling for every new interactive component.
7. Code is MIT-licensed here; **do not copy GPL DpadTV/FongMi implementation source**.
8. Push incremental commits to GitHub after each coherent change; never overwrite working history.
9. Every commit must compile via GitHub Actions before claiming success. A source commit is not a tested APK.
10. Tag/release gate must not publish production/stable APK without testing and private signing.

## Build/test
```sh
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
npm --prefix backend/tv-bff test
```
The repository GitHub Actions workflow also validates APK archive integrity and debug signature.

## Folder layout
- `app/src/main/java/dev/cinematv/ui/`: Compose screens + TV components
- `app/src/main/java/dev/cinematv/data/`: source schemas + authenticated LibreTV API adapter
- `app/src/main/java/dev/cinematv/playback/`: Media3 player/cache factory
- `app/src/main/java/dev/cinematv/CinemaViewModel.kt`: application state and navigation
- `backend/tv-bff/`: separate Node API and unit/integration tests
- `docs/INSTALL.md`: deployment and device QA instructions

## Completion gate
Production-ready is **not** satisfied by compiling an APK alone. Require tested TV input/focus matrix, controlled metadata quality, player format and decode coverage, app lifecycle, security review, and release signing. See GitHub release-readiness issue for exact remaining tasks.

## Current limitations
The glass is currently a GPU-light frosted/translucent effect rather than measured true optical refraction. Browse metadata may not have cinematic 16:9 backgrounds. Login, search, metadata, player and live require the user's own legally accessible LibreTV server. Direct source discovery is not included.

## Recovery procedure after interrupted work
1. Inspect last `main` SHA and the latest GitHub Actions workflow result.
2. Review `docs/INSTALL.md`, `docs/ARCHITECTURE.md`, then open release-readiness issue.
3. Fix any CI regression and commit.
4. Add focused tests for the changed functionality.
5. Only describe a release as complete when its exact commit has an uploaded artifact and documented TV acceptance test.
