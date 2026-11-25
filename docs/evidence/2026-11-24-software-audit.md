# 2026-11-24 Software optimization and pre-device verification audit

**Status: audit plus execution log.** Five parallel audits were performed on
`dev-compose` @ `8ecb49a`; the execution log is §12 and is separate from the original
read-only findings. UX is the product gate. Emulator evidence remains E-gate; physical TV remains
T-gate.

> **Execution update (2026-11-24):** the first low-risk batch (per-item `entryId` memo,
> `HomeDiscoveryStore` IO off Main, table-based hex), playback progress 2A, targeted
> progress 2B, gate hardening (paired floor, JUnit skip/TV audit, focus acceptance run),
> the `dev-compose` host push CI, and a 10-minute real-VOD playback soak landed after
> this audit; see §12. The real-VOD soak passed. The earlier synthetic fixture stall was
> a fixture-server `Range` bug, now fixed and guarded by a clock-progression assertion.
> Glass pure-cost, derived/root scope, real compressed artwork and the physical-TV
> T-gate remain.

The current E-gate baseline is stable: 37.3.10 on the 2 GB Google TV API 34 AVD gives
p50 `TOTAL_DURATION` 17.1–17.2 ms, jank mostly <1%, and `AUTO→BLUR` / `ENHANCED→FULL`
is actually drawn. But the 20 s windows contain only ~33 **new** frame samples/s; that is
input/animation cadence, not FPS. Display-present FPS, release-build behaviour, real
compressed artwork and physical TV were not measured.

## 0. Scope and evidence

- Audits: Compose/focus/recomposition; glass/backdrop rendering; data/startup/image;
  playback/lifecycle/reliability; test/CI/emulator coverage.
- Evidence labels: **observed** (code/config read), **inferred** (derived from code
  semantics, needs measurement), **assumed** (external fact).
- Raw audit reports are in the session. This document is the deduplicated synthesis.
- No code changes. The only new repository content is this report; the pre-existing
  `docs/evidence/2026-11-24-gpu-rendering.md` is also untracked.

## 1. Executive summary

**No P0 correctness defect was observed** (no certain crash, data loss or leak). There is
one **P0 process** problem: several gates can produce a false green and waste physical-TV
time.

The highest-value software costs are:

1. **Write amplification in playback/library** — every 5 s of playback writes progress,
   then `LocalMediaLibrary` re-reads and JSON-decodes the whole library and the ViewModel
   re-ranks recommendations; `selected` is rewritten and `TvRootApp` recomposes. This is
   the most likely software cause of GC/IO contention during playback.
2. **Identity recomputation** — `presentationKey`/`entryId` performs SHA-256 plus 32
   `String.format` calls on every access; it is used in composition, list keys and card
   draw paths, with a 5000-item catalog.
3. **Derived state identity and root over-read** — `browseMovies`, `history()`,
   `featured + tvShows` and `filtered` are getters that create new lists; `remember(...)`
   never hits. The TV root reads message, decoded artwork, IME, focus and navigation
   state, so unrelated changes recompose the root.
4. **Glass layer re-record/overdraw** — each glass control has six offscreen layers; four
   are rebuilt on every animation frame. Live material also paints an opaque “safe” fill
   under an opaque blur, and `ShapeProvider` has no value equality, so any recomposition
   invalidates the whole material chain.
5. **Focus/restore complexity and blind telemetry** — four focus/scroll restoration
   owners; a 210 ms model-focus debounce; focus ownership is not snapshot state; the
   acceptance lane passes `focusKey = null` and never uses `NativePerfProbe.firstAfter()`.
   The E-gate does not actually enforce input → state → frame.
6. **Network/cache/startup redundancy** — Home is cleared and re-fetched on every resume /
   network-available event; detail has no cache and repeats full-source resolution; the
   metadata mutex is held across network; search rebuilds on every NDJSON source event;
   discovery does file I/O on the main thread; image loading uses Coil defaults and the
   fixture is XML vector art.
7. **Lifecycle/writer/privacy gaps** — playback ticker continues in the background; three
   progress writers can regress a checkpoint; `playbackPosition` is stale after
   recreation; playback error text can expose URLs; the updater deletes an APK after
   handing it to the installer.
8. **Test/CI trust gaps** — `dev-compose` has no push CI; `tvNative` can silently skip
   production-entry tests; native E-gate is not in CI; `analyze.py` accepts 1 paired input
   while the test requires 5; the glass-performance harness is blocked; real navigation
   focus is not in a green lane.

**Recommended order:** fix the gates and establish a trustworthy baseline first; then
apply low-risk, same-visual code fixes (identity, derived state, progress writes, glass
pure-cost, network/cache, lifecycle); then run the release/emulator green board with real
content; only then book the physical-TV T-gate. If the release/TV baseline meets the UX
budgets, **stop — no optimization is warranted**.

## 2. Severity and evidence

This report normalizes the raw audits:

| Level | Meaning |
|---|---|
| **P0-process** | Can produce a false green or waste physical-TV time; fix before code optimization |
| **P1-high** | High-confidence, high-impact, same-visual/same-UX cost or reliability issue; first code candidates |
| **P1** | High value; fix after P1-high |
| **P2** | Clear waste/complexity; later |
| **P3** | Hygiene/documentation |

The data audit's “P0-1” and the glass audit's “P0 F1/F2” are **P1-high** here: they are
performance/GC risks, not proven correctness failures. The test audit's “P0” items are
**P0-process**.

## 3. Cross-cutting root causes

| ID | Root cause | Representative evidence | Impact | First safe move | Proof |
|---|---|---|---|---|---|
| R1 | Write amplification | `TvPlayerPage.kt:201-209`, `LocalMediaLibrary.kt:224-230,298-317`, `CinemaViewModel.kt:342-348` | 5 s progress → full DB read/JSON decode/rank/root recompose; ~720 writes/h; pause also writes | Save only on meaningful position/state change; dirty-entry refresh; separate progress stream; no rank on progress; one writer | Fake-DAO call counts; 10 min playback GC/`gfxinfo`; no UX change |
| R2 | Identity recomputation | `MediaDomainMapper.kt:17-32`, `MediaPresentation.kt:8`; UI call sites in `ContentRail`, `CatalogScreen`, `ExploreScreen`, `SearchScreen`, `FocusMediaCard`, `TvRootApp` | SHA-256 + 32 `String.format` per access; 5000-item catalog; card draw | Cache key in DTO/VM; `HexFormat`/lookup; stable list of keys | JVM benchmark; composition counter; no UX change |
| R3 | Derived state identity + root over-read | `CinemaViewModel.kt:173-174`, `HomeScreen.kt:28`, `TvRootApp.kt:56,73`, `CatalogScreen.kt:33`, `ExploreScreen.kt:44` | `remember` never hits; deep list equality; root recomposes on message/decode/focus | VM cached lists + `derivedStateOf`; scope state reads; move message/decode/prefetch out of root | Recomposition counters; goldens; no UX change |
| R4 | Glass layer re-record/overdraw | `HighlightModifier.kt:129`, `ShadowModifier.kt:108`, `InnerShadowModifier.kt:128`, `GlassControlMaterial.kt:95,116-126`, `DrawBackdropModifier.kt:118,243,364-366`, `LayerBackdropModifier.kt:62-67`, `ArtworkPrefetch.kt:25` vs `BackdropLayer.kt:51-52` | 6 offscreen layers/control; 4 rebuilt per animation frame; safe fill under opaque blur; full-screen source re-record; prefetch memory miss | Dirty checks; remove live safe fill; `ShapeProvider` value equality; source content version; align prefetch size; zero-alloc diagnostics | Record counters; GPU p50/p99; goldens; upstream hash/NOTICE; no visual change |
| R5 | Focus/restore/probe | `CinemaViewModel.kt:827-845`, `TvRootApp.kt:56-60`, `TvNativePerformanceAcceptanceTest.kt:60-67`, `NativePerfProbe.kt:26-38`, `perf_common/protocol.py:12` | Four owners; 210 ms debounce; focus not snapshot state; acceptance `focusKey=null`; `firstAfter` unused; 1-pair floor | Single scroll owner; record focus at callback; acceptance uses `firstAfter` and asserts focus key; protocol floor | Instrumented focus tests; XML skip audit; no UX change |
| R6 | Network/cache/startup redundancy | `MainActivity.kt:51,96`; `CinemaViewModel.kt:758-807`; `LibreTvRepository.kt:677-700`; `SourceResolution.kt:83-120`; `CinemaViewModel.kt:732-745,1108-1140`; `LibreTvRepository.kt:205-240` | Home clears/re-fetches; detail re-resolves; metadata lock across network; search rebuild per source; discovery main I/O | Coalesce/TTL; detail single-flight; lock scope; debounce search; IO dispatcher; unify HTTP client | Contract tests; emulator timing; no UX change |
| R7 | Lifecycle/writers/privacy | `PlaybackLifecycle.kt:13-22`; `TvPlayerPage.kt:174,201-209`; `CinemaViewModel.kt:1332-1355`; `UpdateViewModel.kt:135-136` | Background writes; three writers; stale position; URL on screen; installer file deletion | Lifecycle ticker; single latest-wins writer; position persistence; error-code copy; installer file lifecycle | JVM/instrumented; background test; no UX change |
| R8 | Test/CI trust | `.github/workflows/android.yml`, `tv-smoke.yml`, `native-tv-perf.yml`, `production-glass-performance.yml`; `TvLaunchSmokeTest.kt:26,41`; `protocol.py:12` vs `TvNativePerformanceAcceptanceTest.kt:179` | `dev-compose` no push CI; tvNative skip; E-gate not CI; 4/6 accepted by analyzer; glass-perf blocked | Add push CI, XML skip/TV identity audit, native E-gate run, paired-input floor, fix glass cycle | CI green board; no product code |
| R9 | Content realism | `qa_poster.xml`, `qa_scene_*.xml`; no `ImageLoaderFactory` in main | XML vector art hides JPEG/WebP decode, TLS, CDN, image size and cache cost | JPEG/WebP/slow-network fixture; legal-source final validation | Release/emulator A/B; T-gate for absolute |
| R10 | Hygiene | `Regex(...)` in hot functions; `runCatching` around suspend; `browseJobs` not cancelled; `metadataCache` non-LRU; `GLASS_V4` dead rules; non-volatile counters; detach `requireGraphicsContext` | Small per-call waste; cancellation/stale state; misleading docs | Constant regex; rethrow cancellation; cancel previous job; LRU; mark historical; atomic counters; guard detach | Unit tests; no UX change |

## 4. Findings by area

### 4.1 Data, state and playback

| ID | Severity | Finding | Minimal fix | Verification |
|---|---|---|---|---|
| D1 | P1-high | Playback progress every 5 s triggers `LocalMediaLibrary.mutate → refreshLocked()` (full `sources/entries/progress` read + JSON decode + `episodeId` hashing) and `rebuildRecommendations()`; `selected` is rewritten. | Save only on ≥1 s delta or pause/stop/ENDED; dirty-entry snapshot; separate progress flow; no rank; single latest-wins writer. | `LibraryMutationAmplificationTest`; 10 min playback GC/`gfxinfo`; pause 60 s writes = 0. |
| D2 | P1-high | `presentationKey`/`entryId` recomputed on every access (SHA-256 + 32 `String.format`); 58 UI call sites; 5000-item catalog; card draw path. | Cache in data model/VM; `HexFormat`/lookup; expose stable key list. | `PresentationKeyCostTest`; composition counter; no visual change. |
| D3 | P1-high | Derived lists are getters that create new lists (`browseMovies`, `history()`, `featured+tvShows`, `filtered`); `remember` never hits; root over-reads message/decode/focus/nav. | VM cached state + `derivedStateOf`; scope state reads; message/decode/prefetch subcomposables. | Recomposition counters; goldens. |
| D4 | P1 | Home is cleared and re-fetched on every `onResume`/`onAvailable`; no ETag/TTL/debounce; detail has no cache and repeats full-source resolution (≤75 s); metadata mutex held across network; 96-entry non-LRU cache. | Coalesce refresh; keep old data; detail single-flight/cache; lock map only; LRU. | `RefreshCoalescingTest`, `DetailResolutionCacheTest`; request counts. |
| D5 | P1 | `HomeDiscoveryStore.read/write` on Main; catalog uses `HttpURLConnection` per page; search rebuilds per NDJSON source event; first `SimpleCache` construction on Main; five Noto regions prebuilt; Coil defaults with no explicit disk cache. | IO dispatcher; shared client; debounce/incremental search; prewarm cache; lazy font regions; explicit image loader. | StrictMode; request/GC traces; startup. |
| D6 | P1 | Background playback ticker not lifecycle-bound; three progress writers (periodic/dispose/ENDED); stale `playbackPosition` after recreation; error message may expose URL; installer deletes APK on `onCleared`; no playback diagnostics. | Lifecycle-aware ticker; single writer; persist position; map error codes; keep installer file; allowlist playback events. | `BackgroundDoesNotWriteProgressTest`, `PlayerRecreationKeepsPositionTest`, `PlaybackErrorTextHasNoUrlTest`. |
| D7 | P2 | Hot-path `Regex(...)` compilation; `runCatching` swallows `CancellationException`; `browseJobs` not cancelled (same offset twice, fake error); stale snapshot overwrite; 95% completion policy. | Constant regex; rethrow cancellation; cancel previous job; base failure on current state; product decision for 95%. | Unit tests; no UX change. |

### 4.2 Compose, focus and state scope

| ID | Severity | Finding | Minimal fix | Verification |
|---|---|---|---|---|
| C1 | P1-high | The E-gate does not see focus: `onMediaFocus` writes `focused` after 210 ms; focus ownership is a plain `var`; `NativePerfProbe.record` only in `TvRootApp` `SideEffect`; acceptance passes `focusKey=null`; `firstAfter` has no callers. The 20 s probe's `pilotScore=0` is explained by this and by `NativePerfProbe` not being enabled in that probe. | Record logical focus in `ContentRail.onFocusChanged`/`onMediaFocus`; acceptance uses `firstAfter` and asserts focus key; enable the probe in the 20 s lane; add a 60 ms regression test. | Instrumented focus test; `stateFocusKey` non-null and changing; no UX change. |
| C2 | P1-high | Four focus/scroll restoration owners: `RestorePageFocus`, six page-specific first-focus effects, `SaveableStateHolder`/`key(route)`, and `FocusCoordinator` scroll; saveable and coordinator both own scroll; six redundant `remember(model.focusRoute)`. | One scroll owner; one first-focus helper; remove redundant `remember`; define saveable/coordinator precedence; log focus failures. | `InterruptedFocusRestorationTest`; 20 DETAIL entries without saveable growth; no UX change. |
| C3 | P1 | Root reads message, decoded artwork, IME, selected/focused and navigation focus; a message or decode recomposes the whole TV root; `ArtworkPrefetch(featured+tvShows)` builds a new list and deep-compares 48 items every root recomposition. | Move message/decode/prefetch to scoped hosts; pass `State<T>`; cache lists. | Composition counters; message/decode should not recompose pages. |
| C4 | P1 | `GlassAction` reads `animateFloatAsState` values in composition and passes them to `cinemaGlassMaterial`, rebuilding the material chain every animation frame; `FloatingNavigation` rebuilds `entries` per recomposition. `FocusMediaCard` already uses the correct layer/draw-lambda pattern. | Accept `State<Float>`; read in effects/layer lambdas; hoist `entries`; mirror `FocusMediaCard`. | One focus spring should compose `GlassAction` ≈1 time; goldens unchanged. |
| C5 | P2 | Search/Settings read `model.query`/editable fields at page scope, so each key recomposes the whole page; Home's `LaunchedEffect` can scroll after data arrives; `BackdropLayer` decode writes root `decodedArt`; `TvConfirmDialog` uses exception control flow; `focusCoordinator`/saveable entries grow. | Field-level state; guard initial scroll; localize decode; explicit focus failure; caps/removeState. | Page-level recomposition count; startup timing; tests. |

### 4.3 Glass and backdrop

| ID | Severity | Finding | Minimal fix | Verification |
|---|---|---|---|---|
| G1 | P1-high | `ShadowNode`, `InnerShadowNode`, `HighlightNode` unconditionally `record()` every draw; `HighlightNode.prevStyle` is declared but never compared. | Content signature (params + size + density); skip `record` and only `drawLayer` when unchanged. | Record counter; GPU p50/p99; goldens. |
| G2 | P1-high | Live material passes `onDrawBehind = safe`, then an opaque blur layer covers it; large panels fill ~1.45 Mpx twice plus stroke. | Do not pass `onDrawBehind` in live; keep `onDrawFallback`; update `backdrop/README` contract. | Same-tier GPU duration; failure-injection test. |
| G3 | P1 | `ShapeProvider` has no `equals`; `drawBackdrop` creates a new provider each call; four Elements compare by reference and invalidate the whole chain. | Value equality or `remember` the provider; stable shape lambdas. | Invalidation counter; animation GPU; goldens. |
| G4 | P1 | Source `LayerBackdropNode` records the full-screen layer unconditionally; `layoutCoordinates` uses `neverEqualPolicy()`. | Content version (art id + crossfade + scrim + size); explicit dirty flag; measure callback frequency first. | `recordLayer` counter; Perfetto; no visual change. |
| G5 | P1 | Prefetch 960×540 vs consumer 1600×900; Coil memory key includes size → prefetch never hits; backdrop sends a 1dp probe plus a second full request. | Share decode-size constant; single request/`onSuccess`; align prefetch; explicit image loader + bounded disk cache. | Coil hit/miss logs; p99; `GL mtrack`. |
| G6 | P2 | Per-frame `Brush`/`Shape`/`BlurMaskFilter`/effect-chain allocations; diagnostics object churn under acceptance; `GlassAction` hardcodes FULL and role caps are not enforced; `GLASS_V4` dead rules still documented; detach `requireGraphicsContext()` can throw; counters non-volatile. | Hoist allocations; zero-alloc diagnostics; enforce `effectiveGlassLevel`; mark V4 historical; guard detach; atomic counters. | Unit/Compose tests; goldens; upstream verification. |
| G7 | Note | `BLUR` 9dp can be more expensive than `FULL` 1.6dp + 7-sample lens; do not assume FULL is the most expensive tier. No video Surface sampling was found. | Measure the tier curve before any policy change. | Tier A/B; no FPS claim. |

Any `backdrop` change must keep `Shaders.kt` upstream hash, update the pinned-file hashes/NOTICE and pass `backdrop/scripts/verify-production-source.py`; no visual change without goldens.

### 4.4 Test, CI and device verification

| ID | Severity | Finding | Minimal fix |
|---|---|---|---|
| T1 | P0-process | `dev-compose` has no push CI; `android.yml` only main/tag/PR; `tv-smoke` is weekly/manual. | Add a lightweight push job for JVM, lint, contracts, BFF and `assembleDebugAndroidTest`; do not run the full UI matrix on every push. |
| T2 | P0-process | `tvNative` production-entry tests use `assumeTrue(isTv)`; the harness does not parse JUnit XML or check `ro.build.characteristics`. | Add `qa-results-audit.py`: tests>0, failures/errors/skipped=0, require `TvLaunchSmokeTest`/`MainActivityPathSmokeTest`; require `tv` in characteristics. |
| T3 | P0-process | Native E-gate is not in CI; `analyze.py` `MIN_PAIRED_INPUTS=1` while the test asserts `paired >= 5`; a 4/6 run can be “valid”. | Add a per-purpose floor (≥5 or ≥6), write `pairedFloor` in the run document, fail closed; run the E-gate locally/CI. |
| T4 | P0-process | Glass-performance 9/9 blocked at `activate-movies: SEARCH != MOVIES`; workflow dormant. | Fix the harness cycle using the existing focus receipts; do not change product focus; or record blocked in `TEST_PLAN`. |
| T5 | P1 | Real production navigation (5 tabs + 3 icons) is not in a green lane; `InterruptedFocusRestorationTest` uses a synthetic two-key nav; `ProductionGlassUiTest#actualProductionRoots…` is known red. | Add `ProductionGlassUiTest#largePanels…` to tv1080/tvNative; fix the existing focus assertion; add real-nav D-pad/OK/Back. |
| T6 | P1 | No detekt/ktlint/lint baseline/release lint/coverage/macrobenchmark/baseline profile; no startup/PSS/thermal/soak assertions. | Add lint baseline and release lint; then detekt/ktlint report-only; Kover report-only; startup/PSS/thermal as relative E-gate; no absolute TV claims. |
| T7 | P2 | Player tests use synthetic offline media; no HLS/DASH/4K/HDR/DRM/audio/subtitle; no real remote/CEC; no production signing upgrade. | Keep as T-gate items; add one real-content contract fixture where legal; never use public streams/credentials. |
| T8 | Note | Local `scripts/tests` has 4F/42E because GNU `timeout` is absent; CI is fine. | Install coreutils/`gtimeout` locally or run contracts in CI; do not treat as product red. |

## 5. Safe vs not safe to optimize

**Safe (same visual/same UX, must still be verified):**

- Identity caching and `HexFormat`/lookup.
- VM cached lists, `derivedStateOf`, scoped state reads, message/decode/prefetch hosts.
- Progress write decoupling, dirty-entry refresh, single writer, lifecycle ticker.
- Glass G1–G5: decoration dirty checks, remove live safe fill, `ShapeProvider` equality,
  source content version, prefetch size; G6 allocation/effect/diagnostics cleanup.
- Network coalescing/TTL, detail single-flight, metadata lock scope, search debounce,
  discovery IO, unified HTTP client, regex constants.
- Error-code copy, installer file lifecycle, playback diagnostics, bounded coordinator
  maps, single PLAYER Back owner.
- Lint/detekt/coverage/test infrastructure.

**Not safe without product/visual approval:**

- Changing blur radius, lens/dispersion, highlight/shadow/inner-shadow, scene downsampling,
  tier caps, one-panel limits, motion removal.
- Changing focus/Back/IME rules or the 210 ms focus debounce for performance.
- The 95% “completed” policy, `SimpleCache` process lifetime, `FULL` hardcode/role cap,
  `GLASS_V4` cleanup — product/contract decisions.
- Any `backdrop` shader change without upstream hash/NOTICE and goldens.
- Any change that reduces the actual FULL tier or visible glass quality.

## 6. Pre-device green board

Do not book physical-TV time until all of the following are the **same SHA** and
traceable.

### 6.1 Gate fixes (P0-process, no product code)

1. Add `dev-compose` push CI (host checks only).
2. Add JUnit XML audit: no skipped tests; require the production-entry classes; require
   `ro.build.characteristics` contains `tv` for `tvNative`.
3. Run native E-gate: 4 tiers (`AUTO`, `BASIC`, `BACKDROP`, `ENHANCED` + `tierDirect FULL`)
   and 3 input-causality runs; fix `MIN_PAIRED_INPUTS`/`pairedFloor`.
4. Fix focus telemetry: record at the focus callback; acceptance uses `firstAfter` and
   asserts a changing focus key; the 20 s probe enables `NativePerfProbe`.
5. Fix the glass-performance cycle or record it blocked; never change product focus for
   the harness.
6. Add real navigation (`ProductionGlassUiTest#largePanels…`) to tv1080/tvNative; fix the
   known `actualProductionRoots…` red.
7. Record known flaky/blocked items; do not retry to green.

### 6.2 Host/JVM/static

- `:backdrop:testDebugUnitTest`, `:app:testDebugUnitTest` (no skips/no-source),
  `assembleDebug`, `assembleDebugAndroidTest`, `compileGlassBenchmarkKotlin`,
  `lintDebug`, `lintRelease`, `lintGlassBenchmark`, BFF tests.
- Script contracts in CI (or with GNU `timeout` locally): `scripts/tests`,
  `glass-production`, `glass-performance`, `perf_common`, `native-tv-perf`,
  `libretv-contracts`.
- Lint baseline first; detekt/ktlint/coverage report-only; no new warnings gate yet.

### 6.3 Release/emulator matrix

- `-PcinematvNativeTvAcceptance=true :app:assembleRelease :app:assembleReleaseAndroidTest`
  succeeds; record app/test APK SHA-256; sign only for installation with the local debug
  key, never a production identity.
- `tvNative`: ~15 methods, 0 skipped, production `MainActivity`, `ro.build.characteristics`
  contains `tv`.
- `tv1080`: ~66 methods + 6 goldens; `tv4k`: ~21 + 6 goldens; `compat26`: ~43;
  `tv29`: ~13; `tv31`: ~5; `tv36`: ~3; `phone`: ~93 if shared components changed.
- Native E-gate: actual tier equals expected; input-causality ≥5/6; `analyze.py` exit 0.
- Glass-performance: 9/9 collected, or explicitly blocked in `TEST_PLAN`; real compressed
  backdrops, not XML vectors.
- Update/install lane if updater/manifest touched.
- No silent skips; every lane has tests/failures/skipped counts.

### 6.4 Real content

- JPEG/WebP at 200 KB / 800 KB / 2 MB; 1600×900 backdrops; slow network 300/800 ms;
  50 items × 5 pages; 50 NDJSON source events; 404/504; keep-alive/TLS.
- Current `qa_poster.xml` / `qa_scene_*.xml` only prove UI/glass; they do not prove
  decode, upload, network or memory.
- A legal source URL can be the final validation; no public streams, credentials or
  unlicensed art in fixtures.

### 6.5 T-gate (owner-run, not emulator)

Cold start to HOME; every rail D-pad reachable; DETAIL/Dialog/Search Back; player Back
ladder; 30 min thermal; 4K/HDR/DRM/audio/subtitle/decoder failover; real remote/CEC/IME;
production signing install/upgrade; 60 Hz sustained deadline jank/input p95/p99.
Emulator cannot prove TV SoC GPU/thermal, real decoder/HDR/DRM, 4K panel, remote, signing,
display-present FPS or input latency.

## 7. Recommended sequence

### Phase 0 — Trust the gates and establish the baseline (1–2 days; no product code)

- Implement 6.1 gate fixes; run 6.2 host checks; add the realistic fixture; record exact
  SHA/APKs and a baseline for `entryId`, `browseMovies` identity, progress write counts,
  recomposition counts and glass record counts.
- Stop if the baseline cannot be trusted; do not optimize on a false green.

### Phase 1 — Low-risk, same-visual code candidates (3–5 days)

One variable at a time, each with a JVM/instrumented test, a before/after E-gate run and
a rollback.

1. **Identity cache** (`presentationKey`/`entryId`, `HexFormat`, VM key lists) — D2/C3.
2. **Derived state/root scope** (cached lists, `derivedStateOf`, message/decode/prefetch
   hosts, `GlassAction` state/layer pattern) — D3/C3/C4.
3. **Progress write decoupling** (meaningful delta, dirty entries, separate progress flow,
   no rank, single writer, lifecycle ticker) — D1/D6.
4. **Glass pure-cost** (G1 decoration dirty checks, G2 live safe fill, G3 `ShapeProvider`,
   G4 source version, G5 prefetch size; G6 allocations/diagnostics) — upstream/golden/GPU
   A/B.
5. **Network/cache/startup** (Home coalescing, detail single-flight, metadata lock scope,
   discovery IO, search debounce, HTTP client, regex constants, SimpleCache prewarm, lazy
   font regions) — D4/D5/D7.
6. **Lifecycle/privacy/reliability** (background ticker, three writers, stale position,
   error-code copy, installer file, playback diagnostics, caps, PLAYER Back owner) —
   D6.
7. **Compose/focus complexity** (single scroll owner, one first-focus helper, remove
   redundant `remember`, explicit focus failure) — C2/C5; larger, do after counters/tests.

### Phase 2 — Real content + release/emulator green board (3–5 days)

- Run 6.3/6.4 with the exact release artifact; compare candidates on the same AVD,
  renderer, tier, window length and host state; use ABBA/interleaved runs; no FPS claims.
- Fix regressions; re-run the matrix.

### Phase 3 — Physical-TV T-gate

- Owner-run 6.5; no emulator result is relabelled; no 60 fps/TV claim before T-gate.

## 8. Stop rules and UX guardrails

- If release/TV FULL holds the refresh, p95 within one refresh interval, input/startup/
  PSS/thermal within budgets and no visible jank: **stop; no optimization**.
- If the emulator is slow but TV is fine: no product change.
- If a candidate improves numbers but harms focus, legibility, contrast, Back, motion or
  visual quality: reject.
- If the only fix is lowering the glass tier/area, removing lens/highlight/shadow or
  removing motion: do not ship; revisit the UX/product requirement.
- If the cost is content/network/player: separate track; do not conflate with UI frame
  time.
- No FPS from vsync rate or `TOTAL_DURATION` inverse; no emulator as TV; no threshold
  widening; no silent skips; no multi-variable experiments; no product code before
  approval; no pinned shader changes without upstream hash/NOTICE; no video Surface
  sampling.

## 9. Open decisions

1. **Content:** legal source URL or synthetic JPEG/WebP/slow-network CMS? Recommend
   synthetic for repeatability + one legal-source validation.
2. **TV/T-gate:** which Android TV, owner, availability? Without TV the conclusion is
   E-gate only.
3. **Budgets:** refresh rate, p95/p99, input p95, cold start, PSS/thermal; do not invent
   a pass threshold.
4. **Scope:** UI/glass + data/startup first; player as a separate track; how much
   architecture refactor is allowed.
5. **CI changes:** `dev-compose` push CI, skip audit, native E-gate; these are repo/CI
   changes and need approval.
6. **Product decisions:** 95% completion, 210 ms focus debounce, `SimpleCache` lifetime,
   `FULL` hardcode/role cap, `GLASS_V4` cleanup, `focusCoordinator` caps.

## 10. Limitations

- Read-only audits; no build/test/device run; observed/inferred/assumed are marked.
- The prior 20 s E-gate focus telemetry is invalid; its frame/tier data is valid but does
  not prove input causality.
- Local script contracts have 4F/42E due to missing GNU `timeout`; CI is unaffected.
- Emulator is E-gate; no TV/FPS certification; p99 is coarse; host was not idle; real
  content/TV were not measured.
- Some findings are product decisions, not defects; confirm before changing behavior.

## 11. Source audits

- Compose/focus/recomposition audit (session).
- Glass/backdrop rendering audit (session).
- Data/startup/image audit (session).
- Playback/lifecycle/reliability audit (session).
- Test/CI/emulator coverage audit (session).
- Current E-gate evidence: `docs/evidence/2026-11-24-gpu-rendering.md`.

## 12. Execution log (2026-11-24)

First low-risk batch landed after the audit:

| ID | Change | Verification |
|---|---|---|
| E1 | `MediaItem.cachedEntryId` (body-only, `@Transient @Volatile`) + `MediaDomainMapper.entryId` memo; `MediaDomainMapperCacheTest` proves same-instance reuse, `presentationKey` reuse, and `copy()` recomputation. | Target test + full `:app:testDebugUnitTest` |
| E2 | `HomeDiscoveryStore.read/write` are `suspend` and run on `Dispatchers.IO`; `CinemaViewModel` call sites remain in `viewModelScope`/`collect`. | Full JVM compile/tests |
| E3 | `MediaDomainMapper.id` uses a table-based hex encoder instead of per-byte `String.format("%02x")`; output is byte-identical (existing exact-key tests pass). | Full JVM tests |
| E4 | `PlaybackProgressWritePolicy` + TV/mobile ticker/dispose coordination: periodic save only while playing and ≥1 s moved, `C.TIME_UNSET` guard at call sites (real 0 remains persistable), ENDED/dispose duplicate suppression; pure policy test. | Policy test + full JVM + `assembleDebug` + `assembleDebugAndroidTest` + `lintDebug` |
| E5 | `.github/workflows/android.yml` push includes `dev-compose`; host `apk` job only, no device matrix/preview. | `test_device_qa_contracts` 18/18; `test_noto_typography` 9/9; `test_preview_release` 14/14 under Python 3.14 (local Python 3.9 lacks `hashlib.file_digest`) |
| E6 | `LibrarySnapshot.change` + `withProgress`; `LocalMediaLibrary.mutateProgress` writes only the affected record/progress; `CinemaViewModel` skips recommendation/search rebuild on `PROGRESS`; full fallback for missing/uninitialized records. | `LibrarySnapshotProgressTest`; `LocalMediaLibraryTest` 13/13 on `cinematv-tv`; full JVM + `assembleDebug` + `assembleDebugAndroidTest` + `lintDebug` |
| E7 | 10-minute real-VOD playback (`NotoTestActivity` + production `PlaybackFactory`, `cacheVod=true`; HLS from the user-supplied 优酷资源 via local official LibreTV 2.21.1; host `SurfaceFlinger --latency` on the BLAST video layer). | **pass (E-gate)**: 608.1 s test, position +600.0 s, 14,401 rendered frames, 0 dropped, no error, 1280x502 `avc1.64001F`, buffer 656.7 s ahead; debug APK SHA-256 `fcc3f43765eeb73ccb1fd3d50653fe5249f4b992f960cf6b0a1952cb372dc2ec`; 60 host windows / 7,620 unique frames, present p50 40.68 ms, p95 51.64, p99 53.41, jank 11.89%; AVD emulator/ANGLE Metal; the neutral host exercises the production Media3 factory/cache and LibreTV data path, not the full TV page; no TV/FPS claim. Memory was not sampled in this real-source run. |
| E8 | Synthetic fixture harness finding: `UiFixtureServer` ignored `Range`; the 30 s fixture stalled at ~175 ms while the old test only required >150 ms. Added RFC 7233 byte ranges (including suffix) and a 3 s clock-progression assertion to `NativePlaybackSessionTest`. | Two earlier 10-minute synthetic soaks were rejected (position 175–193 ms; host present p50 83.35 ms, jank 59.33%); after the fix `NativePlaybackSessionTest` passes (6.6 s) and requires `currentPosition >= 3 s`. |
| E9 | Production-entry focus acceptance: seed 4 local history items, navigate HOME, focus a real card, press D-pad right/left; direct `FocusMediaCard` focus events feed `NativePerfProbe`; `LocalPerfProbePage` labels the page. Inert in production (`enabled=false`). | `TvNativePerformanceAcceptanceTest` passed: `tier-direct`, policy/actual `FULL`, `sourceReady=true`, 5/6 paired (floor 5), 5 `stateFocusKey` values, analyzer valid, 0 callback drops; APK SHA-256 `9d4a82b878dd1af565591bb0935048c9dbe6999553feb5ebbb46688da208cfff`, source `8ecb49a0bb5fc5ac85638f7a76a783411dc59e88` (worktree dirty, not committed). |
| E10 | Source-list validation: gist raw is invalid JSON (missing comma after `360资源`); 23/86 sources reachable from this network; 优酷资源 search/detail/HLS playable, 光速资源 502/empty. Local `cinematv/libretv:2.21.1` Docker ran on `:18080`; no source list or password committed. | Host probe + LibreTV `/api/search`/`/api/detail` + the 10-minute run; container stopped, no qemu. |

Commands and result:

```sh
$GRADLE --no-daemon :app:testDebugUnitTest :backdrop:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# BUILD SUCCESSFUL

npm --prefix backend/tv-bff test
# 78 pass, 0 fail

python -m unittest test_protocol        # 12 tests OK
python -m unittest test_analyze         # 3 tests OK
python -m unittest test_qa_results_audit test_device_qa_contracts  # 24 tests OK

adb shell am instrument -w -e class dev.cinematv.data.LocalMediaLibraryTest \
  dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner
# OK (13 tests)

adb shell am instrument -w -e class dev.cinematv.TvNativePerformanceAcceptanceTest \
  ... dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner
# OK (1 test); analyzer valid, paired=5, FULL drawn
```

No UX/visual/API behavior change. E9 adds an inert probe read and records only when the
acceptance lane enables it; it draws no UI and changes no focus, Back, favorites or
playback rule. The seeded history exists only on the disposable emulator. Remaining:
glass pure-cost, derived/root scope, real compressed artwork, memory sampling in the
real 10-minute playback lane, and the owner-run physical-TV T-gate. Emulator results are
E-gate only.
