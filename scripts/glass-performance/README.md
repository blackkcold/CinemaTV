# Actual production glass performance protocol

This is an isolated benchmark host, not the material/motion lab and not a second renderer.
It runs the real `CinemaViewModel`, `CinemaNotoProvider`, `TvRootApp`, and its current
`ProductionGlassProvider`. The only replacements are a loopback synthetic metadata
fixture and original same-repository QA imagery. No video surfaces are sampled.
No recording is performed, concurrently or separately.

## Isolated build wiring

The application registers this build type after `release`:

```kotlin
create("glassBenchmark") {
    initWith(getByName("release"))
    signingConfig = signingConfigs.getByName("debug")
    isDebuggable = false
    applicationIdSuffix = ".glassbenchmark"
    matchingFallbacks += listOf("release")
}
```

It includes only the original fixture resources from the debug source set:

```kotlin
sourceSets["glassBenchmark"].res.srcDir("src/debug/res")
```

This includes the three original QA drawables, not debug source code or its activities.
The benchmark activity/fixture do not enter ordinary release or debug APKs. Its preferences,
local library and output files are isolated under `dev.cinematv.glassbenchmark`. The fixture
binds only `127.0.0.1` and resolves resources with `context.packageName`. It uses no actual
user library, public media provider, account credential or playable stream.

## Execution

The `.github/workflows/production-glass-performance.yml` builds one
release-inherited, nondebuggable, debug-signed APK. It does not repeat the media/QA matrix.
Push-triggered sampling runs only on the dedicated `feature/production-glass-perf`
branch when a coherent commit explicitly includes `[glass-performance]`. An approved
manual dispatch is also supported. Pushing the same commit to the UI branch does not
repeat this performance matrix; unrelated pushes do not repeat it either.
The device job downloads the APK, checks its SHA-256 and source receipt, verifies installed
APK bytes, package isolation and absence of the debuggable flag. No build runs beside the
emulator. The benchmark does not run on the user's Mac.

- API35 cloud emulator, 1920×1080 at **320 dpi** (960×540 dp production layout).
- Same binary, 3 interleaved rounds: Full/Blur/Basic, Blur/Basic/Full, Basic/Full/Blur.
- Each status request carries a unique nonce echoed with runId by the native activity.
  A bounded 15-second wait accepts only that exact response, not stale status or the
  return of `am start -W`. Control stdout, native callback receipts and raw tag logs
  distinguish delayed delivery, missing native handling and parsing failures. A missing
  handshake aborts later launches with an incomplete report; a genuine tier fallback
  can still allow the remaining tiers to be examined.
- Every run starts a clean isolated package, loads the synthetic catalog/art/fonts, then
  performs an unmeasured native-input warmup cycle. No startup or shader warmup is timed.
- Default `glass-controls`: 22 real `adb shell input keyevent` presses per measured run, two identical cycles:
  Home hero → navigation Up/Right/Right/Left → activate Movies → Back (navigation retained)
  → Down to hero → hero Right/Left → open detail → Back to hero.
- KeyDown receives a native monotonic timestamp. A 450 ms active window starts there.
  The next key is not sent until after a >=700 ms delay and its native receipt. Static
  gaps, startup, warmup and `FIRST_DRAW_FRAME` samples are excluded.
- Real policy, hardware acceleration, thermal/power/reduced-motion guards and failure latch
  remain intact. A fallback blocks that tier; nothing forces a higher renderer.
- Initial and whole-run successful material recording traversals, font/art readiness,
  exact native key and route sequence, stable PID/foreground, run order and both
  host/native receipts are verified. The material observer is outside drawBackdrop's
  graphicsLayer: focus/press updates to its inner RenderNode need not increment it.
  Per-input deltas through 520 ms may legitimately be zero. They and new Detail-entry
  deltas are recording-traversal diagnostics, not per-frame GPU passes, displayed
  frames or FPS. FrameMetrics, not this counter, provides renderer-timing samples.
- A key may be received after its response frame's VSYNC. The preceding one-refresh-period
  candidate is retained separately as boundary sensitivity, not assumed input-caused.
  The fixed active window may also truncate a long animation tail.
- No screenshot or screen-recording command is part of this performance protocol.

Local checks without Android:

```sh
python3 -m unittest discover -s scripts/glass-performance -p 'test_*.py' -v
python3 -m py_compile scripts/glass-performance/*.py
```

## Selecting one profile

Both driver and analyzer accept `--profile glass-controls|poster-motion`. The default
is `glass-controls`, preserving its exact 11-key cycle repeated twice (22 measured
windows, 13 warmup/preparation keys), original route assertions and timing statistics.
Workflow dispatch exposes the same choice; push-triggered runs remain glass-controls.
One invocation chooses **one** profile, runs **nine** trials (three interleaved tiers),
and uses the same build-once APK. It never doubles the default work to 18 trials.
Use a fresh output directory for each invocation. For example:

```sh
python3 scripts/glass-performance/drive-performance.py --profile poster-motion \
  --sha "$GITHUB_SHA" --requested-config "$OBSERVED_CONFIGURATION" --out qa/poster-motion
python3 scripts/glass-performance/analyze.py --profile poster-motion --directory qa/poster-motion
```

`poster-motion` begins at native Home hero `hero/primary`. Native Up/Down first exits
touch mode. One complete unmeasured 12-key cycle then warms the real UI. Measurement
repeats this exact closed loop twice, for **24 measured windows** (14 warmup/preparation
keys, measured indices 14–37):

1. Down to `rail:为你精选`, featured index 0.
2. Right four times: indices 1, 2, 3, 4.
3. Left twice: indices 3, 2.
4. Center: real Detail route whose `focusRoute` is `DETAIL:` plus featured[2]'s original
   metadata key; selected identity must equal that item's independently derived fixture
   provider entry key after source resolution.
5. Back: Home, same rail/bookmark at index 2.
6. Left twice: indices 1, 0.
7. Up: Home hero `hero/primary` again.

The activity publishes passive `featuredKeys` and `tvShowKeys` (the first five native
`presentationKey` values each), explicit `movieCount`/`tvShowCount` (24/24), `historyCount`,
`selectedKey`, `focusRoute`, `expectedDetailKey`, and existing route/bookmark state. Limiting key arrays keeps every logcat
receipt below Android's per-entry payload limit; all five traversed movie identities are
covered. Assertions use these keys, never baked-in hashes. Clean isolated `pm clear`
requires 24 movies, 24 shows and no continue history. The reported catalog identities and order must
remain stable within warmup, both measured cycles and across the nine poster trials.
The source-less metadata identity legitimately differs from the resolved source entry.
`expectedDetailKey` is computed using production `MediaDomainMapper.entryId` on featured[2]
with the independently known fixture source (`fixture.base + "/provider"`), never from
`selected`. This fixture preserves the catalog item ID when resolving its source; that
mapping is not assumed for arbitrary providers. Missing featured[2] or an already
source-backed catalog item produces an empty expectation and is rejected. A nonempty
entry hash and exact equality are required, not title matching or merely a Detail page.
Original detail `focusRoute` and resolved `selectedKey` must both match. All snapshots,
including preparation/warmup, lock the expected resolved identity within one trial.
Different launches may use different loopback ports and therefore different resolved
keys; only the original movie/show catalog identities and order are compared across runs.
Legacy poster receipts missing either new field are rejected, not reinterpreted as passing.
The driver and analyzer reject wrong keys, routes, selected identity, bookmarks, origin,
catalog, warmup or counts. The activity independently refuses poster measurement unless
its native Home bookmark is hero primary and history is empty. Nothing sets focus,
scrolls a list, substitutes UI, bypasses policy or changes the production clock.

The expected path is source-derived from `HomeScreen`'s `firstRail`, `ContentRail`'s
`rail:为你精选` and `presentationKey` targets, and native Detail/Back restoration.
In particular, Up from the first poster to hero still requires real runtime validation
on the target Compose/Android configuration. Source and host tests do **not** establish
that it works on device. A mismatch must produce blocked evidence, not force focus or
relax the expected path. Android API/Compose differences may therefore block this profile.

All timing remains real `Window.FrameMetrics`: 450 ms windows, at least 700 ms between
inputs, unchanged production animation clocks, no screenshots or recordings. Profile is
explicit in launch metadata, every native state, frame export, setup, run results and
summary. The analyzer's selected profile must match all receipts. Missing profile on old
reports is rejected; use the original analyzer for those legacy glass-controls artifacts,
keeping them separate rather than relabeling or mixing them with new evidence.

## Evidence and interpretation

Artifacts contain the exact APK/build receipt, requested emulator options, actual
SurfaceFlinger GLES line, app `gfxinfo` HWUI pipeline, properties, viewport/density,
per-input receipt and material recording-traversal diagnostics, raw FrameMetrics, and partial failure evidence.
SurfaceFlinger identifies the compositor; `gfxinfo` identifies the app rendering pipeline.
Neither is inferred from the requested `-gpu lavapipe` argument. A software backend such
as ANGLE/SwiftShader is reported as observed.

`summary.json` and `summary.md` report:

- Active-window app render cadence in Hz from adjacent **VSYNC_TIMESTAMP** samples, and
  render intervals. This is app render cadence, **not actual display presentation FPS**.
- Raw frame counts, sample density per active second, window render-total and GPU-duration
  distributions, platform-deadline exceedances, and a fixed 16.67 ms budget count.
- Per-run distributions and median/range of the three per-run p95 values for each tier.
  Complete valid runs permit same-emulator differences against Basic.
- Callback loss is reported as lost **metric callbacks**, never dropped rendered frames.
  Any callback loss, fallback, missing sample window, missing renderer identity, changed
  PID, wrong native route, unready scene, or mismatched APK blocks a complete comparison.

Never invert `TOTAL_DURATION` to claim FPS. Never use video encoding rate. Never claim
these cloud software-GPU measurements establish physical TV 60 fps. A blocked or incomplete
matrix produces a structured incomplete report, not an invented successful tier result.

## Passive focus diagnostics

Each native input includes `focusOwnerBefore`; its after-window status includes
`focusOwner`. These UI-thread snapshots expose synchronous navigation ownership,
independent navigation/content acquisition counters, and the latest restoration's route/state/generation and
monotonic start/request/finish timestamps. They allocate only per input/status,
not per rendering frame, and never request or redirect focus. An increased
acquisition proves the navigation received focus since the earlier snapshot; a
historical bookmark by itself does not. `Pending` starts when `ready` permits
restoration, so a restore route different from the current route can mean that
restoration has not begun yet. These diagnostics distinguish a late restore from
an early input; they are neither frame counts nor an exemption from path gates.

A push to the dedicated performance branch with `[glass-performance]` selects
only the original glass-controls profile. `[poster-performance]` selects only
the poster-motion profile. Manual dispatch exposes the same explicit choice.
Each workflow runs nine trials, never both profiles or an eighteen-trial matrix
by default. A marker is not permission to change route/timing assertions.
