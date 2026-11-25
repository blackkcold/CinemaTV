# Native TV baseline — 2026-11-07

Plan: `docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md`
Branch: `dev-compose`
Plan baseline commit: `5c24360493ac5486d9cedd7c890f2877a018b695`

This file records measured facts only. Items marked `BLOCKED` were not run and must
not be quoted as results.

## G0.1 Governance inputs

Accepted into the worktree:

- `AGENTS.md` — governing plan now names the reviewed revision; G4 deletion,
  G3.1/G3.2/G3.4 numbering and the native performance harness wording are aligned.
- `docs/NATIVE_TV_MIGRATION.md` — branch migration input, retained as history.
- Both 2026-11-07 plan files — the reviewed revision is authoritative; the original
  is retained unchanged.

No unrelated work was present. No commit, push or tag was created because the user
chose worktree-only execution.

## G0.2 Host toolchain (measured 2026-11-07)

| Item | Finding |
|---|---|
| Host | macOS (darwin), arm64 |
| Default JDK | Temurin 21.0.12.1 |
| JDK used for Android builds | Temurin 17.0.20 |
| Gradle | 8.13 downloaded to the approved temp tool cache; no system install |
| Android SDK | `/Users/11169285/android-sdk`; `local.properties` is git-ignored |
| SDK platforms | android-34, android-35, android-36, android-37.0 |
| Build tools | 35.0.0, 36.0.0, 37.0.0 |
| Emulator / platform tools | 37.2.12 / 37.0.1 |
| System images | `android-34;google-tv;arm64-v8a`, `android-34;google_apis;arm64-v8a` |
| AVD | `cinematv-tv`: Google TV API34, arm64, 1920x1080 @320, 2 GB, `-gpu host` |
| Python / Node | Python 3.9 with Pillow 11.3.0; Node 22.22.3 / npm 10.9.8 |
| GNU timeout | absent locally; CI Linux provides it |

## G0.3 Exact-source CI baseline

**BLOCKED — the user selected worktree-only execution.** No commit, push or workflow
dispatch was performed. Therefore the required `android.yml` / `tv-smoke.yml` exact
checkout SHA, run URLs and artifact hashes do not exist yet. The local equivalents
below are not a substitute for that gate.

## G0.4 Archive and baseline tags

**BLOCKED for the same reason.** `archive/tv-web-5c24360` and
`baseline/native-tv-<governance short SHA>` were not created. The archive tag must be
created once and never moved; the baseline tag must point at the governance commit.

## G0.5 Local baseline and current verification

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | 507 tests, 0 failures, 0 errors, 0 skipped |
| `:backdrop:testDebugUnitTest` | 9 tests, 0 failures |
| `:app:assembleDebug`, `:app:assembleDebugAndroidTest` | pass |
| `:app:compileGlassBenchmarkKotlin` | pass |
| `:app:lintDebug`, `:backdrop:lintDebug` | pass |
| `npm --prefix backend/tv-bff test` | 78 pass |
| `scripts/glass-production` | 47 pass |
| `scripts/glass-performance` | 38 pass |
| `scripts/perf_common` | 6 pass |
| `scripts/native-tv-perf` | 2 pass |
| `assembleReleaseAndroidTest -PcinematvNativeTvAcceptance=true` | pass; recorder output now uses schema 3 and requires source SHA/APK hash/renderer at run time |
| TV AVD: `TvUiRegressionTest` | pass |
| TV AVD: `TvLaunchSmokeTest`, `MainActivityPathSmokeTest`, `TvRouteBackTest` | pass |
| TV AVD: `TvFieldChipTest`, `TvStatusDialogTest`, `TvHeroTest`, `TvImeBackTest` | pass |
| TV AVD: `ContentRailVisibilityTest`, `ContentRailTraversalTest`, `GlassActionLayoutTest` | pass |
| TV AVD: `GlassMaterialSmokeTest` | pass |
| TV AVD: `FocusBoundRelocationTest` | **one pre-existing failure**: `withoutProxyNativeBlurKeepsOldRelocationMoving` also fails on clean `5c24360` in a detached worktree; not introduced by this change |

Local Python 3.9 cannot run the repository-wide `scripts/tests` discovery cleanly:
`preview-release.py` uses `hashlib.file_digest` (3.11+) and the harness expects GNU
`timeout`. The device-matrix, target-dispatch, glass-action, update-pipeline and
perf-protocol tests that do not need those facilities pass. The complete
`scripts/tests` discovery is therefore **not green locally** and remains a CI gate on
its newer Python; do not quote the local subset as a full host-suite pass.

## Gates not entered

- **G3.4 physical smoke: BLOCKED** — no physical Android TV/box is attached.
- **G4 deletion: NOT STARTED** — gate order requires G3.4; deleting without it would
  violate the plan and AGENTS.md.
- **G5 visual finalisation / G6 T-gate: NOT STARTED** — no physical TV evidence and no
  recaptured goldens were produced.
- **Golden thresholds** were not changed.
