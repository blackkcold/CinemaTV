# G6 glass baseline — execution receipt (2026-11-25)

**Scope:** E-gate only. One nondebuggable `glassBenchmark` APK, one local API34
Google TV AVD, `-gpu host`, ANGLE Metal, 1920x1080 at 320 dpi. These numbers are not
display FPS and are not physical-TV acceptance.

## Artifact identity

| Item | Value |
|---|---|
| Source SHA | `6768ab40f9a8c9637abb62a14b513f5aa4b11071` |
| Glass-benchmark APK | `6acfaf88b32229a1562bc2a6715e45ed887a01e25b0dea1630f9a9626b0e7cab` |
| Build type | `glassBenchmark`, nondebuggable, debug-signed |
| AVD | `cinematv-tv`, API34 Google TV, 1920x1080 / 320 dpi, hardware GLES |
| Renderer | `Google (Apple), Android Emulator OpenGL ES Translator (Apple M4), OpenGL ES 3.0 (4.1 Metal - 90.5)` |

## Harness repair

The previous `glass-controls` lane was blocked at `activate-movies: SEARCH != MOVIES`.
The warmup ended on the first rail while the measured cycle assumed Home hero, so the
first measured `navigation-up` did not own navigation. The cycle now ends with
`rail-up-hero` and the warmup asserts Home hero before measurement. It is a 12-key cycle
repeated twice: 24 measured windows, 14 warmup/preparation keys. The driver's warmup
assertion is explicit; no product focus was changed.

The current app exports schema 3 compact input receipts. The analyzer now:

- accepts schema 2 and 3;
- reconstructs schema-3 window identity and applied draw/route state from the next
  native receipt and the full start/end snapshots;
- compares host/native receipts on the causal identity fields for schema 3, while
  schema 2 keeps exact equality;
- has a regression test for schema-3 reconstruction.

`scripts/glass-performance` suite: 39/39.

## Result

Nine collected runs, all valid, in the required interleaved order:
`completeComparison=true`, no setup issues, no callback drops, no truncated capture.

| Tier | Valid runs | App render p95 total (ms), median across runs (min–max) | GPU p95 (ms), median (min–max) |
|---|---|---|---|
| FULL | 3/3 | 52.69 (39.42–61.90) | 23.44 (21.10–23.89) |
| BLUR | 3/3 | 67.64 (55.81–69.51) | 24.32 (23.32–24.43) |
| BASIC | 3/3 | 33.96 (23.15–42.56) | 19.05 (17.73–20.43) |

All tiers report app-render cadence p50/p95 at the 60 Hz AVD refresh; this is app
render cadence, not display FPS and not a product budget.

**Finding:** on this E-gate configuration BLUR is more expensive at p95 than FULL;
FULL is not the worst tier. This matches the earlier audit's warning (G7) and argues
against assuming the highest optical tier is the most expensive one.

## Decision

- No `ProductionGlassPolicy` degradation is introduced from emulator data.
- No blur radius, lens, highlight, shadow, area cap, one-panel rule or material level was
  changed. The complete-effect configuration remains the production default.
- Any degradation needs measured failures on the target device plus the T-gate; this
  receipt cannot authorize one.
- The focus acceptance on the G6.1 code (source `3764cad`, APK
  `08775f274beb8b4cfb3939d4528018813e4747e017cbe29da2cf478eec14b1dc`) is valid:
  `tier-direct`, FULL drawn, 5/6 paired, no callback drops.

## Remaining G6 evidence

- API26/29/31/33/36 and API32 policy boundary: CI/device matrix, not run on this host
  (only the API34 TV image is installed). This is not a pass.
- Golden recapture/review and real compressed artwork: tv1080/tv4k lanes were not
  available locally; no goldens were recaptured or reviewed.
- G6.4 T-gate: owner-run on at least two physical Android TVs, 30-minute thermal and
  20 route/playback cycles, legal local media, exact signed candidate. Prepare only;
  do not substitute this receipt.
- The no-Node Android-only CI job and the full device matrix remain CI verification
  items.
