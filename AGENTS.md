# CinemaTV engineering handoff (AGENTS.md)

CinemaTV has a decoupled LibreTV adapter and an optional BFF. The current direction
(2026-11-07, branch `dev-compose`) is **native Compose TV presentation**:
`TvRootApp` is the only TV entry; TV pages, focus, motion and glass live under
`app/src/main/java/dev/cinematv/ui/tv/`; Media3 remains the native player. The hybrid
TV WebView browsing path is frozen and scheduled for removal under
`docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md`. This supersedes the
2026-11-06 hybrid direction recorded in the previous revision.

Do not add new TV Web features or new hybrid bridge behavior. WebView remains only
for non-TV surfaces such as Douban official login. Until the plan's G4 deletion, the
legacy hybrid code may remain in the tree for A/B evidence, but it is not the TV
mainline and must not be made the default again.

## Non-negotiable constraints

1. Preserve Android 8.0/API26 installation, native Media3 playback and the independent phone UI. TV uses native Compose; API26 must still install and launch.
2. LibreTV is an upstream service, not bundled or duplicated. Never embed credentials, unlicensed streams or commercial artwork. Fixtures must be original/synthetic and excluded from production bundles.
3. Keep provider JSON decoding in `data/`. Expose bounded presentation DTOs, not provider responses, cookies, credentials, arbitrary URLs or the whole ViewModel.
4. Keep Media3 lifecycle in `playback/` and `ui/TvPlayerPage.kt`; retain cache, episode/source identity, progress persistence and player release.
5. Visual target: Apple TV-like fullscreen cinematic hero, restrained liquid glass, floating navigation and focus-driven poster motion. Optical sampling is restricted to the app's still `BackdropLayer`; never sample the video Surface, a Dialog, or the glass's own consumers. Reduced-motion and bounded rendering are mandatory.
6. Every interactive component must work with D-pad, OK and Back. One route owner (`TvRootApp`), stable focus keys, list virtualization and playback-return restoration are mandatory. Native TV acceptance, not browser success, is the gate.
7. Glass production path is `backdrop` + `ProductionGlassScene` + `cinemaGlassMaterial` + `ProductionGlassPolicy`. Do not add Haze/Liquid or upgrade Compose/Kotlin/compileSdk as part of migration. `AUTO` may stay BLUR; Full requires API33+ and ENHANCED. Dialog and player overlays stay Basic.
8. Glass selection principle: feature-first. G1/G2/G5 implement the full glass intent first, including large-area Blur/Full panels; performance limits (area caps, one-panel, Basic fallback) are introduced only after measurement in G6. Hard constraints: Dialog and player overlays stay Basic; never sample the video Surface; API fallback still applies. Quality tiers must not change focus, Back, favorites, source switching or playback rules.
9. `dev-compose` is the only development mainline and the Dev-channel source. Every feature, fix, performance and test branch derives from it and opens its PR against it. `dev` is transitional and scheduled for retirement: until then it receives only pushes from `dev-compose` and never accepts independent work. `main` is the Stable release line and accepts only reviewed release candidates. Never force-push a shared branch. Do not work on `feature/hybrid-tv-liquid-glass-complete`; it is historical. Push coherent incremental commits; never overwrite working history.
10. Keep `dev-compose`'s Douban public-data/diagnostics work isolated from stable. Do not productize the Douban account experiment; keep it dev-only. Never merge unaccepted personal-account code into `main`.
11. MIT licensing applies. Do not copy GPL DpadTV/FongMi/Jellyfin implementation code or redistribute third-party proprietary fonts/assets.
12. Compile and test the exact candidate with GitHub Actions before claiming success. A source commit is not a tested APK; emulator results are not physical TV certification.
13. Keep the existing public package and fixed signing identity for installable Dev releases. All private signing material stays outside GitHub, Actions, artifacts and logs. Do not bypass signing gates, publish a differently signed same-package APK as an upgrade, or fabricate performance results.
14. Performance claims require exact source SHA, APK SHA-256, device/AVD, actual renderer, material tier and valid samples. Never use vsync rate as FPS; never treat a `gfxinfo framestats` window as a 60-second distribution; never convert an unmeasured budget into a measured claim. E-gate is protocol/relative only; jank is deadline/refresh-rate based, never a fixed 16.67 ms; product acceptance is T-gate on an Android TV image, with the owner's physical-TV run recorded separately.
15. Golden thresholds stay mean <= 0.020 and changed <= 0.08. Recapture and review; do not loosen to pass.
16. Freeze the Web layer now: no new `frontend/tv-web` or `app/src/main/java/dev/cinematv/hybrid/` features. Delete in G4 after the owner's short physical-TV smoke and archive; migrate coverage first; preserve `archive/tv-web-5c24360`.
17. UX is the product gate. A performance or degradation change may not reduce focus clarity, text legibility, contrast safety or Back predictability. Fix the interface first; earn the budget second.

## Current plan and branch state

- Governing plan: `docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md` (the reviewed revision; it supersedes the original plan wherever they differ).
- Branch: `dev-compose` @ `5a68260e` (re-verified 2026-11-09). It is 13 commits ahead of `dev` and 72 ahead / 4 behind `main`. The native TV surfaces, the reviewed plan and the capture protocol are already on this branch; `dev` carries no commits of its own and can fast-forward without history loss.
- Branch roles: `dev-compose` develops and publishes the Dev channel; `dev` is a transitional mirror scheduled for retirement; `main` holds Stable. Dev and Stable share one package name and one signing identity, with a single monotonically increasing versionCode ledger.
- Baseline tag: `baseline/native-tv-<G0 governance short SHA>` does not exist yet. Create it once after the G0 governance commit; never move it.
- Web archive tag: `archive/tv-web-5c24360` does not exist yet. Create it once before G4 deletion; `5c24360` is the last commit of the TV Web experiment.
- Gate order: G0 governance + baseline -> G1 components, glass interfaces and capture protocol -> G2 pages + full-surface glass -> G3 real-device lanes + root Back + cutover + short physical smoke + archive -> G4 Web deletion -> G5 feature-complete glass/visual polish -> G6 performance/degradation/T-gate. Do not skip G3.1 lanes, G3.2 root Back/IME, G3.4 pre-delete smoke, or G6.3 post-delete E-gate rerun.
- G3.4 and the T-gate are owner-run manual runs on a physical Android TV. They produce receipts and are not CI jobs. Emulator lanes cover remote input, focus, Back/IME and the 4K viewport; they never substitute for the owner's run.
- `docs/NATIVE_TV_MIGRATION.md` is the branch migration input, not the current truth; the consolidated plan supersedes it where they conflict.
- `docs/GLASS_PRODUCTION.md` is the authoritative current production glass description. `docs/GLASS_V4.md` holds the V4 depth/lens contract; merge its still-valid sections in G6.4, then mark it historical with a pointer.

## Build/test

Use CI or a provisioned Android toolchain. The repository has no committed `gradlew`;
CI expects Gradle 8.13.

Native baseline:
```sh
gradle --no-daemon :app:testDebugUnitTest :backdrop:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
npm --prefix backend/tv-bff test
```

Device QA:
- `scripts/qa-device-matrix.py` / `.github/workflows/tv-smoke.yml`: phone API30, `tvNative` API33 (Android TV image), `tv1080` API33, `tv4k` API33, `tv29`, `tv31`, `tv36` and `compat26` API26. `tv1080`/`tv4k` use phone system images, so `isTv` is false; treat them as component/visual lanes only. Only `tvNative` proves the production television entry.
- Native TV acceptance uses `TvTestActivity` for controlled component/golden tests and a `MainActivity` production-entry smoke test on `tvNative`. `TvTestActivity` alone is not production evidence; a phone image resized to 1080p is not a TV lane.
- Existing glass frame harness: `scripts/glass-performance/` (glassBenchmark build type, 450 ms windows, three tiers). It checks protocol validity, not product FPS.
- Native performance harness: `scripts/native-tv-perf/` + `FrameTimingRecorder` + `TvFrameTrace` + `TvNativePerformanceAcceptanceTest`. The protocol and recorder exist, but no device run has yet produced a valid end-to-end sample, `TvFrameTrace` is not wired into acceptance, and the declared material tier is not measured. Do not present any existing run as performance evidence.
- Local toolchain (verified 2026-11-09): JDK 21 and `local.properties` exist, but `gradle` is not on PATH, the Android SDK directory is absent, no emulator binary is installed and GNU `timeout` is missing. Do not assume a local build; run the exact candidate in CI until a toolchain is provisioned.

## Folder layout

- `app/src/main/java/dev/cinematv/ui/tv/`: native TV pages, components, design tokens, focus, motion and glass scenes.
- `app/src/main/java/dev/cinematv/ui/TvPlayerPage.kt`: native Media3 player UI and overlay.
- `app/src/main/java/dev/cinematv/playback/`: Media3 factory, cache and lifecycle.
- `app/src/main/java/dev/cinematv/data/`: source schemas, authenticated adapters and persistence; provider decoding stays here.
- `app/src/main/java/dev/cinematv/ui/douban/`: Douban official login using system WebView; no JS bridge; dev-only.
- `app/src/main/java/dev/cinematv/ui/update/` and `data/update/`: updater and installer.
- `app/src/main/java/dev/cinematv/hybrid/`: frozen legacy TV hybrid host; delete in G4; do not extend.
- `frontend/tv-web/`: frozen TV Web experiment; delete in G4; do not extend.
- `backend/tv-bff/`: optional metadata service.
- `docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md`: current plan; the unreviewed original is retained beside it as history.
- `docs/GLASS_PRODUCTION.md`: current glass doc; `docs/GLASS_V4.md`: V4 depth/lens contract, reconciled in G6.4.
- `docs/UPDATE_CHANNELS.md`: external signing, versionCode ledger and channel safety.

## Completion gate

Require:

- `:app:testDebugUnitTest`, `:backdrop:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug` (no new errors), and `backend/tv-bff` tests.
- Native TV device QA: D-pad/OK/Back on every page, focus restoration after DETAIL/PLAYER/LIBRARY/dialog, player return, no accidental exit.
- Production TV entry must run on an Android TV image (`ro.build.characteristics` contains `tv`, `isTv=true`) through `MainActivity`; IME Back (first Back hides the keyboard, page unchanged) and the player Back ladder (panel -> controls -> route) are part of the gate.
- Feature-complete glass is implemented and visually reviewed first; performance limits/degradation are introduced only after G6 measurement, not pre-degraded in G1/G2.
- Golden recapture and human review; thresholds unchanged.
- E-gate (emulator) evidence with valid samples and actual renderer, protocol/relative only; T-gate (owner-run physical TV) evidence before any 60fps/product claim.
- API26/29/31/33/36 fallback, low-RAM/thermal Basic behavior, and no video Surface sampling.
- Exact source SHA, APK SHA-256, test scope, device model/SoC/refresh rate/renderer, and known limitations.

Never convert an unmeasured budget into a measured claim. Emulator results are not physical TV certification.

## Recovery after interrupted work

1. Inspect `dev-compose`, `origin/dev` and the latest Actions results; do not assume the last local state is tested.
2. Read the consolidated plan and `docs/GLASS_PRODUCTION.md`; identify the current gate and its exit criteria.
3. The native entry is already cut over (`MainActivity` dispatches on UI mode). Never treat hybrid as the mainline, even when a legacy test or document still references it.
4. Fix regressions and add focused tests before expanding scope. Revert after three consecutive failed fixes and report.
5. Release only the exact accepted artifact with its original approved signing identity.
