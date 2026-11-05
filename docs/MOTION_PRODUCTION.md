# Production poster motion

CinemaTV's TV poster cards use an independently written Android/Compose adaptation of focus elevation, press feedback and layered parallax. This is Apple TV-inspired behavior, not a verified recreation of a particular tvOS release or Apple's private timing constants.

## Runtime boundary

- `TvRootApp` installs `PosterMotionProvider` inside the existing theme and production glass provider. Its modifier observes preview key events and always returns `false`. Native Android focus traversal, click activation, route ownership and Back restoration remain authoritative.
- Only `FocusMediaCard` and small helpers under `ui/tv/motion` change. Glass source recording, optical rendering, navigation selection policy and video surfaces are not replaced.
- A D-pad provides a discrete direction, not a continuous Siri Remote touch position. Each direction is a short 110 ms hint, then settles to neutral. A newer direction supersedes the previous one; Back, activation, route changes, window-focus loss and reduced motion clear stale hints.
- Focus/press/depth states are read in graphics-layer or draw callbacks. Unfocused cards do not subscribe to every direction event. Bitmaps are not wrapped in an extra Offscreen cache, and there is no perpetual motion loop.

## Current parameters

These are CinemaTV tuning choices, not measured Apple constants. Focus scale is 1.06 with the existing -3 dp lift; focused press uses 1.015, touch-only press 0.97. Focus and press use damping 0.78 and stiffness 460. Depth uses damping 0.86 and stiffness 520, up to 3 degrees of tilt, 4 dp artwork movement and 7 dp movement for the fallback title. Interrupted springs settle from their current values rather than snapping on blur.

Artwork has uniform overscan sized to cover the movement without stretching its aspect ratio. The static crop does not change when Reduce Motion is toggled. External captions, watch-progress information and accessibility descriptions are retained. The existing focused shadow is static rather than animated per frame; the live sheen is separate from poster images and text.

## Accessibility and capability policy

The shared production policy supplies reduced motion, including the system animation-scale and environment safeguards. Reduced motion immediately disables scale, lift, tilt and parallax while preserving an obvious focus border, a static focused material and all focus/click/Back behavior. It does not select a different glass material.

The helper supports `FOCUS_ONLY` and `LAYERED` separately from glass quality. Production currently uses `LAYERED`; this change does not add the lab's low/medium/high motion-settings UI. Existing Basic/Blur/Full glass choices are independent. CPU count, memory capacity or an emulator's model string are not treated as GPU performance scores.

## Verification and performance boundaries

The pure policy suite has 28 tests. The nine added component instrumentation tests cover actual `FocusMediaCard` instances, interrupted focus/press, D-pad reversal and expiry, Back/route reset, reduced motion, stable media identity, loading/error behavior and retained labels/progress. A test-only monotonic-clock injection makes component timing deterministic; the production-root performance host must keep the real system clock.

Compilation and API 26/31/35 functional results must be recorded for the exact integration SHA before release. Do not treat this description or a local policy-test pass as an Android build or device-test pass.

Use the nondebuggable production-root benchmark without screen recording. The existing glass profile exercises hero/navigation/detail controls, so it does not establish poster-motion performance. A separate native-input profile must enter the Home poster rail, traverse and reverse across the visible range, open an exact media identity, and verify Back restores that identity. Keep its results separate from the original glass profile, using the same viewport, fixture, active-window definition and interleaved trials.

`Window.FrameMetrics` app-vsync cadence is not physical-display presentation FPS. Report render total P50/P95, within-window app-vsync spacing, measurement callback loss and any deadline statistics separately. Display refresh metadata, encoded frame rate and a layer-recording counter are not render-FPS evidence. This work does not capture video and does not certify 60 fps or a particular physical TV.

The separate procedural-art lab evaluates an explicit raster cache. That cache is not installed around production poster bitmaps. Its results must not be presented as a production-app improvement.

## Source and license audit

Research checked 2026-11-05. No source code or art from these projects was copied and none is a runtime dependency. No GPL DpadTV/FongMi implementation is used.

- Apple [focus interactions](https://developer.apple.com/documentation/uikit/about-focus-interactions-for-apple-tv), [focus and selection](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection/), [layered-image guide](https://tvpartners.apple.com/assets/parallax-exporter-tvos-user-guide.pdf), and [Reduce Motion](https://support.apple.com/en-euro/guide/tv/atvb1f949820/tvos) inform behavior and accessibility. They do not publish this application's spring constants.
- [PGSSoft/ParallaxView](https://github.com/PGSSoft/ParallaxView/tree/a4165b0edd9c9c923a1d6e3e4c9a807302a1a475), MIT, commit `a4165b0edd9c9c923a1d6e3e4c9a807302a1a475`, [license](https://github.com/PGSSoft/ParallaxView/blob/a4165b0edd9c9c923a1d6e3e4c9a807302a1a475/LICENSE). UIKit focus coordination and separate child layers/sheens/shadows; library timings are not Apple's timings. License blob `02df5f348bd90cd7de9df5180a49a85f7ccc3aa8`.
- [DroidsOnRoids/MPParallaxView](https://github.com/DroidsOnRoids/MPParallaxView/tree/9223ac3a1828a6475fe0f13dbba18f6de27f7971), MIT, commit `9223ac3a1828a6475fe0f13dbba18f6de27f7971`, [license](https://github.com/DroidsOnRoids/MPParallaxView/blob/9223ac3a1828a6475fe0f13dbba18f6de27f7971/LICENSE). An iOS touch/CoreMotion simulation, not a tvOS focus-engine implementation. License blob `593ed78a45d3876c9de0dfe8647987b56a024c30`.
- [android/tv-samples, JetStreamCompose](https://github.com/android/tv-samples/tree/15ac8df7df91a07450751a5fe8c6fd17a6a4297f/JetStreamCompose), Apache-2.0, commit `15ac8df7df91a07450751a5fe8c6fd17a6a4297f`, [license](https://github.com/android/tv-samples/blob/15ac8df7df91a07450751a5fe8c6fd17a6a4297f/LICENSE). Native Android focus restoration and navigation-indicator design; its card treatment is not a promise of Apple fidelity. License blob `d645695673349e3947e8e5ae42332d0ac3164cd7`.

The detailed inspected-path lock and original license texts remain in the independent motion-lab branch, under `tools/motion-lab/references.lock.json` and `reference-licenses`. The production implementation does not require merging that experimental application.
