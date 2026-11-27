# CinemaTV engineering handoff (AGENTS.md)

CinemaTV has a decoupled LibreTV adapter and an optional BFF. The current direction
(2026-11-07, branch `dev-compose`) is **native Compose TV presentation**:
`TvRootApp` is the only TV entry; TV pages, focus, motion and glass live under
`app/src/main/java/dev/cinematv/ui/tv/`; Media3 remains the native player. The hybrid
TV WebView browsing path is frozen and scheduled for removal under
`docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md`. This supersedes the
2026-11-06 hybrid direction recorded in the previous revision.

Do not add new TV Web features or new hybrid bridge behavior. WebView remains only
for non-TV surfaces such as Douban official login. G4 deleted the legacy hybrid host
and TV Web bundle on 2026-11-25 (owner-waived G3.4); recover historical evidence only
from `archive/tv-web-5c24360`, never by re-creating the tree.

## Non-negotiable constraints

1. Preserve Android 8.0/API26 installation, native Media3 playback and the independent phone UI. TV uses native Compose; API26 must still install and launch.
2. LibreTV is an upstream service, not bundled or duplicated. Never embed credentials, unlicensed streams or commercial artwork. Fixtures must be original/synthetic and excluded from production bundles.
3. Keep provider JSON decoding in `data/`. Expose bounded presentation DTOs, not provider responses, cookies, credentials, arbitrary URLs or the whole ViewModel.
4. Keep Media3 lifecycle in `playback/` and `ui/TvPlayerPage.kt`; retain cache, episode/source identity, progress persistence and player release.
5. Visual target: Apple TV-like fullscreen cinematic hero, restrained liquid glass, floating navigation and focus-driven poster motion. Optical sampling is restricted to the app's still `BackdropLayer`; never sample the video Surface, a Dialog, or the glass's own consumers. Reduced-motion and bounded rendering are mandatory.
6. Every interactive component must work with D-pad, OK and Back. One route owner (`TvRootApp`), stable focus keys, list virtualization and playback-return restoration are mandatory. Native TV acceptance, not browser success, is the gate.
7. Glass production path is `backdrop` + `ProductionGlassScene` + `cinemaGlassMaterial` + `ProductionGlassPolicy`. Do not add Haze/Liquid or upgrade Compose/Kotlin/compileSdk as part of migration. `AUTO` may stay BLUR; Full requires API33+ and ENHANCED. Dialog and player overlays stay Basic.
8. Glass selection principle: feature-first. G1/G2/G5 implement the full glass intent first, including large-area Blur/Full panels; performance limits (area caps, one-panel, Basic fallback) are introduced only after measurement in G6. Hard constraints: Dialog and player overlays stay Basic; never sample the video Surface; API fallback still applies. Quality tiers must not change focus, Back, favorites, source switching or playback rules.
9. `dev-compose` is the only development mainline and the Dev-channel source. Every feature, fix, performance and test branch derives from it and opens its PR against it. `dev` is retired as of 2026-11-25; its historical commits remain in `dev-compose`, and the TV Web route is archived at `archive/tv-web-5c24360`. `main` is the Stable release line and accepts only reviewed release candidates. Never force-push a shared branch. Do not work on `feature/hybrid-tv-liquid-glass-complete`; it is historical. Push coherent incremental commits; never overwrite working history.
10. Keep the Douban personal-account experiment dev-only: `data/douban` and `ui/douban` are refused by the stable source guard, and unaccepted personal-account code must never merge into `main`. The anonymous public-list channel (`data/doubanchart`) and the diagnostic log (`diagnostics/`, `ui/diagnostics/`) carry no account state and ship in both channels.
11. MIT licensing applies. Do not copy GPL DpadTV/FongMi/Jellyfin implementation code or redistribute third-party proprietary fonts/assets.
12. Compile and test the exact candidate with GitHub Actions before claiming success. A source commit is not a tested APK; emulator results are not physical TV certification.
13. Keep the existing public package and fixed signing identity for installable Dev releases. All private signing material stays outside GitHub, Actions, artifacts and logs. Do not bypass signing gates, publish a differently signed same-package APK as an upgrade, or fabricate performance results.
14. Performance claims require exact source SHA, APK SHA-256, device/AVD, actual renderer, material tier and valid samples. Never use vsync rate as FPS; never treat a `gfxinfo framestats` window as a 60-second distribution; never convert an unmeasured budget into a measured claim. E-gate is protocol/relative only; jank is deadline/refresh-rate based, never a fixed 16.67 ms. Release acceptance rests on the automated lanes; a physical-TV T-gate is an optional owner-run check, never a release blocker, and its absence must be stated, never implied as passed.
15. Golden thresholds stay mean <= 0.020 and changed <= 0.08. Recapture and review; do not loosen to pass.
16. The Web layer is deleted. G4 removed `frontend/tv-web`, `app/src/main/java/dev/cinematv/hybrid/`, its build wiring and hybrid lanes on 2026-11-25 under the owner G3.4 waiver; `archive/tv-web-5c24360` preserves the original. Do not re-create it.
17. UX is the product gate. A performance or degradation change may not reduce focus clarity, text legibility, contrast safety or Back predictability. Fix the interface first; earn the budget second.

## Current plan and branch state

- Governing plan: `docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md` (the reviewed revision; it supersedes the original plan wherever they differ).
- Branch: `dev-compose` @ `7e2d831` (re-verified 2026-11-23). Ahead/behind counts change on every merge, so read them from git rather than from this file. The native TV surfaces, the reviewed plan, the capture protocol and the refreshed goldens are on this branch. The transitional `dev` branch was retired on 2026-11-25; its tip `7d3ec00` is an ancestor of `dev-compose`. Use `dev-compose` for development and `main` for Stable.
- Branch roles: `dev-compose` develops and publishes the Dev channel; `main` holds Stable; the transitional `dev` branch is retired. Dev and Stable share one package name and one signing identity, with a single monotonically increasing versionCode ledger.
- Baseline tag: `baseline/native-tv-8787e1e` marks the G0 governance state (tested source `6bc1c7f`, identical tree). Never move it.
- Web archive tag: `archive/tv-web-5c24360` exists (created 2026-11-24; original hybrid APK and fingerprint in the same-named release). G4 deletion was completed 2026-11-25; do not move the tag.
- Gate order: G0 governance + baseline -> G1 components, glass interfaces and capture protocol -> G2 pages + full-surface glass -> G3 real-device lanes + root Back + cutover + short physical smoke + archive -> G4 Web deletion -> G5 feature-complete glass/visual polish -> G6 performance/degradation. G3.4 was explicitly owner-waived on 2026-11-25 (see `docs/evidence/2026-11-25-g3.4-waiver.md`); never treat emulator evidence as physical acceptance. The G6.4 T-gate is optional and owner-run; G6.3 post-delete E-gate rerun is mandatory.
- The G3.4 waiver and the T-gate are owner-run manual runs on a physical Android TV; the T-gate is optional. They produce receipts and are not CI jobs. Emulator lanes cover remote input, focus, Back/IME and the 4K viewport; they never substitute for the owner's run, and a release without one must say so.
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
- Native performance harness: `scripts/native-tv-perf/` + `FrameTimingRecorder` + `NativePerfProbe` + `TvNativePerformanceAcceptanceTest`. Valid runs exist on the TV AVD (input → state → drawn frame, measured drawn tier). Always pass `-e glassQuality AUTO|BASIC|BACKDROP|ENHANCED` before `-e tierDirect`; without it the app stays on AUTO and a FULL assertion tests nothing. FULL does draw on an API34 AVD booted with `-gpu host` and `ENHANCED`. `TvFrameTrace` is still unwired. Emulator numbers are E-gate only, never a frame-rate or TV claim.
- Local toolchain (verified 2026-11-09): JDK 21; `local.properties` points at `/Users/11169285/android-sdk` (platforms 34/35/36/37.0, build-tools 35/36/37, `platform-tools` and `emulator`). AVDs: `cinematv-tv` (Google TV API34, arm64, 1920x1080 @320, 2048 MB), `livis34`, `livisdiy`. Boot `cinematv-tv` with `-gpu host`: an explicit `-gpu swiftshader_indirect` overrides the AVD's `hw.gpu.mode=host` and silently falls back to software rendering (verify with `adb shell dumpsys SurfaceFlinger | grep GLES`). `cmd uimode` has no `television` subcommand on API34, so a phone cannot be switched into TV mode through adb. Gradle 8.13 is unpacked at `$TMPDIR/opencode/gradle2/gradle-8.13`, is not on `PATH`, and the repository has no `gradlew`; call it by absolute path with `--no-daemon`. GNU `timeout` is absent, so device-harness steps that require it run in CI only.
- Local run hygiene: when a local run finishes, stop every resource it started — the AVD
  (`adb emu kill`, verify no qemu process), any test container (`docker stop` or `rm`), and
  Gradle/Kotlin daemons. They are shared local resources, not background services; only an
  explicit user request keeps one running past its task.

## Folder layout

- `app/src/main/java/dev/cinematv/ui/tv/`: native TV pages, components, design tokens, focus, motion and glass scenes.
- `app/src/main/java/dev/cinematv/ui/TvPlayerPage.kt`: native Media3 player UI and overlay.
- `app/src/main/java/dev/cinematv/playback/`: Media3 factory, cache and lifecycle.
- `app/src/main/java/dev/cinematv/data/`: source schemas, authenticated adapters and persistence; provider decoding stays here.
- `app/src/main/java/dev/cinematv/ui/douban/`: Douban official login using system WebView; no JS bridge; dev-only.
- `app/src/main/java/dev/cinematv/ui/update/` and `data/update/`: updater and installer.
- `app/src/main/java/dev/cinematv/hybrid/` and `frontend/tv-web/`: removed in G4 on 2026-11-25; recover only from `archive/tv-web-5c24360`; never re-create on the mainline.
- `backend/tv-bff/`: optional metadata service.
- `docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md`: current plan; G4 completed 2026-11-25 under the owner G3.4 waiver; G6 remains.
- `docs/GLASS_PRODUCTION.md`: current glass doc; `docs/GLASS_V4.md`: V4 depth/lens contract, reconciled in G6.4.
- `docs/UPDATE_CHANNELS.md`: external signing, versionCode ledger and channel safety.
- `docs/TEST_PLAN.md`: the single test-plan entry point (layers, TV AVD, E-gate/T-gate boundaries, triage).
- `docs/PROTECTED_BRANCHES.md`: how a change reaches a protected branch, how a bypass is identified, and what a bypass requires afterwards.

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

1. Inspect `dev-compose`, `origin/dev-compose` and the latest Actions results; do not assume the last local state is tested.
2. Read the consolidated plan and `docs/GLASS_PRODUCTION.md`; identify the current gate and its exit criteria.
3. The native entry is already cut over (`MainActivity` dispatches on UI mode). Never treat hybrid as the mainline, even when a legacy test or document still references it.
4. Fix regressions and add focused tests before expanding scope. Revert after three consecutive failed fixes and report.
5. Release only the exact accepted artifact with its original approved signing identity.
