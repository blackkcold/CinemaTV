# Native TV input causality — 2026-11-09

Plan: `docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md` (G1)
Protocol: `scripts/perf_common/protocol.py` v4, `purpose=input-causality`

This file records measured facts only. It supports **Build Verified** and a narrow
**Device QA Verified on one emulator** claim for the input-causality chain. It does not
support a product-FPS, tier or physical-TV claim.

## Provenance

| Item | Value |
|---|---|
| Source SHA tested | `88d249aa4364ead04d6ec892114dfe561e4b5d78` |
| Working tree at build | clean (`git status --porcelain` empty) |
| Installed APK SHA-256 | `f333a514363d0a31582e11e1bdc9526fad2edc1b2fe4cdd5c647c05158ee4d32` |
| APK build | release, `-PcinematvNativeTvAcceptance=true`, signed with the local debug key for install only |
| Device | AVD `cinematv-tv` |
| Image / API | Google TV `android-34`, arm64-v8a, API 34 |
| Display | 1920x1080 @ 320 dpi, 2048 MB |
| Renderer | `Android Emulator OpenGL ES Translator (Apple M4)`, OpenGL ES 3.0 (4.1 Metal 90.5) |
| Run document | `b1ad2151-055e-4989-a2a2-a937d37cb02b.json` |

The release app APK is unchanged by this commit because `src/perf` is not part of the
release main source set; the hash above is the artifact actually installed and measured.

## Result

`scripts/native-tv-perf/analyze.py` returned `valid: true` with no errors.

| Metric | Value |
|---|---|
| Frames in run | 13 |
| Inputs | 6 |
| Paired inputs | 5 |
| Unpaired inputs | 1 (first DOWN, `handled=false`) |
| Callback drops | 0 |
| Truncated | false |
| Latency p50 | 11.29 ms |
| Latency p95 | 15.89 ms |
| Latency p99 | 15.89 ms |

The unpaired press is the first sample press: the window had no focusable target yet and
`dispatchKeyEvent` returned false. It is excluded by the protocol, never back-filled. The
lane requires five of six paired inputs.

## What each link now proves

1. **Receipt**: `Window.Callback.dispatchKeyEvent` stamps `receivedNs` before dispatch.
2. **Consumption**: `handled` records whether the UI consumed the press.
3. **State boundary**: `stateAppliedNs` is taken when dispatch returns on the UI thread,
   where the focus or page state the press caused has already been written.
4. **Response frame**: pairing happens in `finish()` after every FrameMetrics callback has
   drained. A frame pairs only if its vsync lies inside that input's own 450 ms window and
   at or after `stateAppliedNs`.

## Rejected runs (kept as evidence that the gate works)

| Attempt | Result | Error raised |
|---|---|---|
| 6 consecutive DOWN presses without spacing | every input claimed the same single frame | `window-overlap` |
| spaced presses, no handled flag | 5 of 6 presses were `handled=false` | `not-enough-paired-inputs` |
| alternating directions at a page edge | one handled press drew nothing | `not-enough-paired-inputs` |

None of these were reported as a pass, and none were retried until green without a code
change.

## Not covered (BLOCKED)

- Physical Android TV, 4K viewport, thermal and low-RAM behaviour.
- `actualTier`: the drawn glass tier is still not measured; this run declares none.
- 60 fps or any product frame-rate claim. `jank_ratio` over 13 frames is not a distribution.
- Long soak, playback, signing, install/upgrade and data-preservation gates.
