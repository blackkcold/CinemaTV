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
It runs manually or on this feature branch when a coherent commit explicitly includes
`[glass-performance]`; unrelated source pushes do not repeat this matrix.
The device job downloads the APK, checks its SHA-256 and source receipt, verifies installed
APK bytes, package isolation and absence of the debuggable flag. No build runs beside the
emulator. The benchmark does not run on the user's Mac.

- API35 cloud emulator, 1920×1080 at **320 dpi** (960×540 dp production layout).
- Same binary, 3 interleaved rounds: Full/Blur/Basic, Blur/Basic/Full, Basic/Full/Blur.
- Every run starts a clean isolated package, loads the synthetic catalog/art/fonts, then
  performs an unmeasured native-input warmup cycle. No startup or shader warmup is timed.
- 22 real `adb shell input keyevent` presses per measured run, two identical cycles:
  Home hero → navigation Up/Right/Right/Left → activate Movies → Back (navigation retained)
  → Down to hero → hero Right/Left → open detail → Back to hero.
- KeyDown receives a native monotonic timestamp. A 450 ms active window starts there.
  The next key is not sent until after a >=700 ms delay and its native receipt. Static
  gaps, startup, warmup and `FIRST_DRAW_FRAME` samples are excluded.
- Real policy, hardware acceleration, thermal/power/reduced-motion guards and failure latch
  remain intact. A fallback blocks that tier; nothing forces a higher renderer.
- Actual material draw counters, font/art readiness, exact native key and route sequence,
  stable PID/foreground, run order and both host/native receipts are verified. Counters
  sampled after each input cover through 520 ms, so they prove the input phase exercised
  the material but are not precise per-frame GPU attribution.
- A key may be received after its response frame's VSYNC. The preceding one-refresh-period
  candidate is retained separately as boundary sensitivity, not assumed input-caused.
  The fixed active window may also truncate a long animation tail.
- No screenshot or screen-recording command is part of this performance protocol.

Local checks without Android:

```sh
python3 -m unittest discover -s scripts/glass-performance -p 'test_*.py' -v
python3 -m py_compile scripts/glass-performance/*.py
```

## Evidence and interpretation

Artifacts contain the exact APK/build receipt, requested emulator options, actual
SurfaceFlinger GLES line, app `gfxinfo` HWUI pipeline, properties, viewport/density,
per-input receipt and material deltas, raw FrameMetrics, and partial failure evidence.
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
