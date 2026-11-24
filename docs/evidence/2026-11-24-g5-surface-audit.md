# G5 surface audit and visual finalisation — 2026-11-24

Scope: every production glass surface, the reading scrim policy, the navigation and
poster focus indicators, and the isolated Haze evaluation. Evidence is either a code
path, a measured golden pixel, or a native-window capture from the production host.

## Method

1. Measured the approved 2026-11-23 goldens (960×540) directly instead of judging by eye:
   per-region luminance percentiles and WCAG contrast.
2. Audited every call site of `CinemaGlassSurface` / `cinemaGlassMaterial` against the
   §4 role table.
3. Reproduced the navigation over the bright HOME hero at 1920×1080 on the TV AVD and
   measured the pill interior only.
4. Extended the existing production host (`ProductionGlassTestActivity`) rather than
   building a material lab, and asserted per-tier draw counts for large panels.

## Findings and fixes

| Surface | Finding | Fix |
|---|---|---|
| Floating navigation | Unselected 14sp labels used `Secondary` on a 13% white face: 2.2–3.2:1 on the approved golden | Navigation-only glass face (`WHITE .045`, `VEIL .34`, darker icon actions) and `Accent` labels |
| Poster focus ring | One 2dp `Text`-colour border disappeared on white posters | `Ink` 4dp + `Ivory` 2dp pair, same contract as the action buttons |
| Settings / LIVE / DETAIL / LIBRARY panels | Readable on their own dark backdrops; no change | Unchanged |
| Dialog and player overlay | Basic by contract, no scene sampling | Unchanged |
| Poster cards, hero artwork | No glass material | Unchanged |
| Phone | Out of this round | Unchanged |

### Navigation measurements

Pill interior only (x 330–630, y 19–63 in the 960×540 layout), HOME hero behind:

| Tier | Pill p50 | Accent label | White label |
|---|---|---|---|
| ENHANCED (Full) | 0.057 | 6.94:1 | 8.91:1 |
| BACKDROP (Blur) | 0.061 | 6.67:1 | 8.57:1 |
| BASIC | 0.028 | 9.47:1 | 12.16:1 |

Before the change the same band measured p50 0.147 with `Secondary` labels, i.e. about
2.2:1. The artwork outside the pill keeps its approved brightness: the direction-B scrim
policy is untouched.

## Executable guards

- `NavigationGlassFaceTest` proves the navigation face over the measured p90 pill
  (`#808080`) keeps every label ≥ 4.5:1 and that the face stays meaningfully darker than
  the panel face.
- `FocusRingContrastTest` sweeps every grey background and proves the Ink/Ivory pair keeps
  at least one rim ≥ 3:1 (worst case ≈ 4.2:1 at mid-grey), plus each rim owns one extreme.

## G5.2 host extension

`ProductionGlassUiTest.largePanelsRenderTheRequestedTierOnOneBackground` runs the real
production root on the existing debug host and asserts, per requested tier:

- SETTINGS (no artwork image, full-height panel): the expected tier draws, and the scene
  is sampled for Full/Blur.
- LIVE (two-column panels with repainting EPG): same assertions.
- HOME (bright hero behind the navigation pill): same assertions plus a capture.

Captures are written for every tier (`*-settings-panel`, `*-live-panel`, `*-home-nav`),
so the large-panel and navigation samples share one background per tier. Visual capture
and performance timing still run separately; no timing claim is made here.

## Haze pre-check (G5.3) — not adopted

| Candidate | Requirement | Verdict |
|---|---|---|
| `dev.chrisbanes.haze:haze` 2.1.0 | AAR metadata `minCompileSdk=37`, Kotlin 2.4.21, CMP 1.12.2 | FAIL: needs compileSdk 37 + AGP 9.x + Compose 1.12 |
| 1.7.3 | `minCompileSdk=37` | FAIL |
| 1.7.2 | `minCompileSdk=1`, Kotlin 2.2.21, Compose 1.10.0 | Technically fits, but v1 is end-of-life and the 2.x API is incompatible |

The hard stop in the plan (any compileSdk>36 or Compose/Kotlin/AGP upgrade) is reached, so
no PoC was built and `backdrop` remains the production material.

## Verification runs

| Check | Result |
|---|---|
| `:app:testDebugUnitTest --tests dev.cinematv.ui.tv.design.*` | BUILD SUCCESSFUL |
| `:app:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `ProductionGlassUiTest#largePanelsRenderTheRequestedTierOnOneBackground` on `cinematv-tv` | OK (21s, three tiers) |
| Navigation capture, 1920×1080, Apple M4 host GPU | measured above |
| tv1080 golden lane | recaptured from [45314161245](https://github.com/blackkcold/CinemaTV/actions/runs/45314161245) (artifact 14885387790); confirmation [45327167071](https://github.com/blackkcold/CinemaTV/actions/runs/45327167071) passed |
| tv4k golden lane | recaptured from [45324367460](https://github.com/blackkcold/CinemaTV/actions/runs/45324367460) (artifact 14889319771); confirmation [45332510107](https://github.com/blackkcold/CinemaTV/actions/runs/45332510107) passed |

The local AVD is E-gate evidence only. Physical TV acceptance remains the owner's T-gate.

## 4K lane capacity and one recorded flake

The 4K lane grew from 18 to 21 suites when the page-state coverage joined. The old 24m
instrumentation budget stopped at 14/21 with zero failures (stage exit 124), so the budget
is now 40m with a 55m job timeout; all 21 suites then finished in 25m11s. This changes no
assertion, class list, mask or threshold.

One confirmation run, [45328665762](https://github.com/blackkcold/CinemaTV/actions/runs/45328665762),
failed at `RecommendationDashboardUiTest`'s 15s wait for the asynchronous ranker. The same
artifact passed in 45324367460 and again in 45332510107, and the app/test code is identical
across all three, so this is recorded as a timing assumption on a slow 4K software renderer,
not a product regression. Making that wait independent of wall time stays a G6 follow-up.

## Boundaries

- No performance limit, area cap or per-screen glass count was introduced; G6 measures
  before any such change.
- The local `ProductionGlassUiTest#actualProductionRoots…` method fails on this TV image at
  a pre-existing focus assertion (the same failure occurs on the base commit), so it was
  not used as evidence. It is unrelated to this change.
- Haze 1.7.2 was not compiled; its transitive AAR metadata was not fully audited.
