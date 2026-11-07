# CinemaTV engineering handoff (AGENTS.md)

CinemaTV has an explicitly decoupled LibreTV adapter and optional BFF. The approved
2026-11-06 direction is **hybrid Android TV presentation**: locally packaged
HTML/CSS/React for browsing, Kotlin for application/data services, and the existing
Media3/Compose native player. The former blanket prohibition on WebView is superseded
by this explicit user decision. This is not a remote LibreTV website wrapper.

## Non-negotiable constraints
1. Preserve Android 8.0/API26 installation support, native Media3 playback and the independent phone UI. TV uses a local, origin-restricted Web UI on capable WebView providers, with a native recovery route.
2. LibreTV is an upstream service, not bundled or duplicated. Never embed credentials, unlicensed streams or commercial artwork. Browser fixtures must be original/synthetic and excluded from the production bundle.
3. Keep provider JSON decoding in `data/`. Expose bounded presentation DTOs, not provider responses, cookies, credentials, arbitrary URLs or the whole ViewModel.
4. Keep Media3 lifecycle in `playback/` and `ui/TvPlayerPage.kt`; retain cache, episode/source identity, progress persistence and player release.
5. Apple TV-like fullscreen cinematic hero, restrained liquid glass, floating navigation and focus-driven poster motion are the visual target. Optical sampling is restricted to the app's still scene; never imply sampling a separate video Surface. Provide reduced-motion and bounded rendering modes.
6. Every interactive component must work with D-pad, OK and Back. One route owner, stable focus keys, list virtualization and playback-return restoration are mandatory. Browser success alone is not TV acceptance.
7. MIT licensing applies. Do not copy GPL DpadTV/FongMi implementation code or redistribute third-party proprietary fonts/assets.
8. Push coherent incremental commits; never overwrite working history. Work on the user-named `dev-hybird` development line, based on `dev`. Preserve `fix/hybrid-acceptance-serial-qa` as historical recovery only. Do not merge, publish releases or move `dev`/`main` without a new explicit instruction. The original PR #36 and branch stay closed. Do not merge unaccepted personal-account code into `main`.
9. Compile and test the exact candidate with GitHub Actions before claiming success. A source commit is not a tested APK; emulator results are not physical TV certification.
10. Keep the existing public package and fixed signing identity for installable Dev releases. All private signing material stays outside GitHub, Actions, artifacts and logs. Do not bypass signing gates, publish a differently signed same-package APK as an upgrade, or fabricate performance results.

## Build/test

Existing native baseline:
```sh
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
npm --prefix backend/tv-bff test
```

Hybrid sources and instructions are under `frontend/tv-web/` and `docs/HYBRID_TV.md`.
Test browser rendering and Android WebView independently. Retain existing native
regression tests alongside new hybrid bridge, navigation and playback tests.

## Folder layout
- `frontend/tv-web/`: TV React UI, tokens, focus, browser mocks and browser tests.
- `app/src/main/java/dev/cinematv/hybrid/`: local WebView host, presentation adapter and restricted message bridge.
- `app/src/main/java/dev/cinematv/ui/`: native player, mobile UI, native system/credential surfaces and recovery presentation.
- `app/src/main/java/dev/cinematv/data/`: source schemas, authenticated adapters and persistence.
- `app/src/main/java/dev/cinematv/playback/`: Media3 factory, cache and lifecycle.
- `docs/UPDATE_CHANNELS.md`: external signing, global versionCode ledger and channel safety.

## Completion gate

Require build/lint/unit/backend checks, front-end type/contract/browser checks,
Android WebView five-way input/focus/Back/playback-return acceptance, renderer-loss
recovery and bounded resources. Record exact source SHA, artifact hashes and test
scope. Physical low-end TV, HDR/codec breadth and long-play performance require
actual hardware evidence. Never convert an unmeasured budget into a measured claim.

## Recovery after interrupted work
1. Inspect the feature branch and Dev head and their latest Actions results.
2. Read `docs/HYBRID_TV.md` and the checked-in implementation/acceptance status.
3. Fix regressions and add focused tests before expanding scope.
4. Release only the exact accepted artifact with its original approved signing identity.
