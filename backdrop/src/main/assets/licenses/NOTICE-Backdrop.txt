# Vendored Backdrop

Copyright 2025 Kyant. Apache License, Version 2.0; see LICENSE.
Source: https://github.com/Kyant0/AndroidLiquidGlass
Commit: 896a94a3ade1cc1a940b92365f942a34971fecda (Android branch, 1.0.6).
Production input: CinemaTV Glass Lab commit f49a5bf.

The original 26 Kotlin files under src/main/java/com/kyant/backdrop retain their
provenance. Shader source and the full optical/refraction/highlight formulas are
unchanged. upstream-lock.json retains upstream hashes and records current local
hashes for modified vendored files.

CinemaTV modifications:
- Lens.kt: the lab removed the optional Kyant Shapes adapter only. Compose
  CornerBasedShape refraction and seven-sample dispersion are unchanged.
- Build configuration: app-aligned Compose BOM 2026.03.01, Kotlin plugin inherited
  from the production root (2.2.21), Java 17, compileSdk36 and minSdk26. The original
  context-parameter helper is retained with -Xcontext-parameters.
- DrawBackdropModifier.kt: API31 entry guard; optional shared synchronous failure
  state and static fallback; RuntimeException boundaries at layer attachment,
  effect construction and material drawing; one-time layer release. Effects are
  deferred to draws after checking the actual hardware Canvas, never built from
  attachment/state-observation callbacks. Software Canvas latches fallback.
- LayerBackdropModifier.kt: API31 guard and shared source-record failure boundary.
- HighlightModifier.kt, ShadowModifier.kt, InnerShadowModifier.kt: shared failure
  state, attachment/draw boundaries, static repaint and one-time layer release.
  Foreground drawContent is never retried after a failure.
- Added BackdropFailureState.kt, unit tests, integration notes and source verifier.
- Added SafeLayerBackdrop.kt and RememberedBackdropResource.kt: nullable guarded
  source allocation with RememberObserver ownership, including onAbandoned and
  idempotent release after either success or failure. These original CinemaTV
  adapters use public AndroidX LocalGraphicsContext/GraphicsContext APIs and the
  lifecycle pattern documented by AndroidX rememberGraphicsLayer; no AndroidX
  implementation source was vendored. The resource holder has focused JVM tests.
  Diagnostics expose stage and exception class only, never exception text.
- Packaged this notice and the unmodified Apache-2.0 license as Android assets.

The failure boundary catches synchronous RuntimeException only. It cannot recover
from native GPU driver crashes, VM errors or process termination. This is not
physical-device performance or compatibility certification.
