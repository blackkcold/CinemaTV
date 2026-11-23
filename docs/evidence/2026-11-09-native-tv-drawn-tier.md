# Native TV drawn tier — 2026-11-09

Plan: `docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md` (G1)
Protocol: `scripts/perf_common/protocol.py` v4

This file records measured facts only. It closes the "declared tier is not a measured tier"
gap for one emulator. It is not a physical-TV or product-frame-rate claim.

## Provenance

| Item | Value |
|---|---|
| Source SHA | `2bff6e441b72dc0efd52cef3ab0df115c9412ad7` |
| Working tree at build | clean |
| Installed APK SHA-256 | `15b7405312923b9b15042a3a96c04205955791ec6b42a598861a2090bc16fb98` |
| Device | AVD `cinematv-tv`, Google TV API34, arm64, 1920x1080 @320, 2048 MB |
| Renderer | Android Emulator OpenGL ES Translator (Apple M4), GLES 3.0 |

## What is measured now

`NativeGlassProbe` is installed by `MainActivity` only when the measurement lane enabled it
before the activity composed. It holds:

- the policy observation (`ProductionGlassObservation`: requested quality, environment,
  decision, resolved mode, failure latch);
- the draw counters written inside the draw path (`GlassDrawDiagnostics`), separated into
  full / blur / basic and source-present draws.

`actualTier` is the highest tier that drew at least once during the run. It is not read
from an argument.

## Results

| Run | Purpose | Policy tier | Drawn tier | Draws | Paired | Outcome |
|---|---|---|---|---|---|---|
| `base` | input-causality | BLUR | BLUR | full 0 / blur 6 / basic 0 | 6 of 6 | passed |
| `tier_blur` | tier-direct BLUR | BLUR | BLUR | full 0 / blur 6 / basic 0 | 6 of 6 | passed |
| `tier_full` | tier-direct FULL | BLUR | BLUR | full 0 / blur 6 / basic 0 | 6 of 6 | **failed as intended** |

`tier_full` failed with the lane's own assertion: the directed tier `FULL` was not drawn,
policy resolved to `BLUR`. That is the behaviour the plan requires — a Full run that fell
back must be rejected, not recorded as a Full result.

`sourceReady` is now measured (`sourcePresentDrawsTotal > 0`), not hard-coded.

## Not covered (BLOCKED)

- Physical Android TV, 4K viewport, thermal and low-RAM behaviour.
- ~~A device that can actually draw FULL; this emulator never does~~ — superseded by the
  2026-11-23 correction below: the earlier run never requested FULL.
- Frame-rate, jank distribution, long soak, playback, signing and upgrade gates.


## Correction · 2026-11-23: FULL does draw on a host-GPU emulator

The earlier `tier_full` result above is a **setup error**, not a capability finding: the
acceptance lane asserted `tierDirect=FULL` but never changed the persisted quality, so the app
resolved `AUTO → BLUR` and the assertion correctly rejected a tier that had never been
requested.

Re-run on the same AVD with hardware acceleration and an explicit quality:

```sh
emulator -avd cinematv-tv -no-snapshot-save -no-boot-anim -gpu host -no-audio
# SurfaceFlinger: Android Emulator OpenGL ES Translator (Apple M4), GLES 3.0 (Metal)
am instrument ... -e glassQuality ENHANCED -e tierDirect FULL
```

| Run | Purpose | Policy | Drawn tier | Draw counts | Paired | Result |
|---|---|---|---|---|---|---|
| tier-direct FULL | tier-direct | FULL | FULL | full 14 / blur 0 / basic 0 | 4/6 | **failed: pairing** |
| input-causality #1 | input-causality | FULL | FULL | full 14 / blur 0 / basic 0 | 6/6 | valid, p50 9.91 ms, p95 16.64 ms |
| input-causality #2 | input-causality | FULL | FULL | full 14 / blur 0 / basic 0 | 5/6 | valid, p50 12.02 ms, p95 16.63 ms |

The Full optical path renders on API34 with a host GPU when `ENHANCED` is actually requested.
The tier-direct run that paired only 4/6 is reported as a failed sample: two presses produced
no frame inside their 450 ms window. Two input-causality runs on the same build were valid, so
this is a sampling flake to watch, not a reason to relax the gate.

Still not proven: Full on physical TV hardware, and any frame-rate or jank claim (seven frames
are not a distribution). The emulator's host GPU is not a TV SoC.

Source: `95cebef05ae03a13aaa67450f43f69942126e08d`; installed APK SHA-256
`44569a2f64b9765510aaa929d2573c9f237f9a2fee43c236322dfb9f1bb12598`; AVD `cinematv-tv`
(Google TV API34, arm64, 1920x1080@320, 2048 MB); renderer Apple M4 Metal.
