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
- A device that can actually draw FULL; this emulator never does, so the Full path is
  still unproven on hardware.
- Frame-rate, jank distribution, long soak, playback, signing and upgrade gates.
