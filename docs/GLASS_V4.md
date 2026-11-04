# V4 depth controls

Implementation follows the approved V4 depth reference: wider curved inner rim,
reduced exterior glow (none is drawn), and white, undistorted text/icons. Focus is
communicated by a brighter perimeter, material lift and the existing D-pad scale;
press contracts and deepens the material. Reduced motion disables scale animation.
The same native clickable/focus path remains; no gesture recognizer intercepts the
remote or Back key.

## What is actually refracted

The explicit **镜片折射 / ENHANCED** tier uses Android `RuntimeShader` (API 33+) with
an input `BitmapShader`. The input is borrowed from the existing validated Coil
scene decode, shared once through the TV root. No per-button request or bitmap copy
is made. The shader reconstructs the scene's center-crop and horizontal/vertical
scrims, using current root coordinates including axis-aligned focus scale. This
is not a capture of the final composited scene or a verified color-management match. A rounded-rectangle
signed distance gives a broad continuous lens rim; three displaced samples give
subtle edge-only RGB dispersion. Content is drawn afterward, outside the shader.

This is real spatial sampling/refraction of the **scene still**, not a screenshot
of the approved reference, arbitrary composited UI refraction, or video refraction.
Ambient procedural fallback art is not captured. Sampling is suppressed during
scene changes/crossfades and on the player page. No PixelCopy, window capture,
SurfaceView capture, frame polling, extra render loop, or full-screen shader exists.
Navigation's existing bounded blur now reuses the shared still instead of creating
its own image request. It remains a frosted approximation, not a lens.

## Cost and fallback contract

- AUTO remains conservative BACKDROP on API 31+, BASIC below it. Low-RAM devices or
  memory class <=128 MB always use BASIC, even after an ENHANCED request.
- ENHANCED is explicit opt-in, not a claim of GPU certification. It requires API
  33+, hardware Canvas, no reduced motion, a valid settled still, and focus.
- Only the focused control gets a lens, at most 600×80 dp and 240,000 local pixels.
  The pixel cap is measured before the control's outer focus scale; at 1.065×,
  screen coverage can be about 13.4% greater. It is not a GPU time budget.
  Large panels, unfocused buttons, missing/failed artwork, software rendering and
  unsupported APIs keep the same static V4 depth material. No outward glow pass.
- Scene decode remains bounded by the existing 1600×900 request (960×540 BASIC).
  Oversized cached frames are rejected by the lens. The lens borrows the bitmap, does not recycle it and does not add an image cache.
- Synchronous shader construction/uniform/draw RuntimeExceptions trip a shared
  latch for the TV root session; the base material was already painted and future
  lens attempts are skipped. Re-entering a new root session allows retry. Native
  GPU driver crashes are not catchable by this fallback and remain a device-QA risk.

## Primary API / dependency review

No dependency or Compose/Kotlin version change was made. Existing Compose BOM
2026.03.01, Kotlin 2.2.21, minSdk 26 and compileSdk 36 remain intact.

- [Android RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader)
  and [BitmapShader](https://developer.android.com/reference/android/graphics/BitmapShader)
  provide the required input shader without new library API constraints.
- [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) is Apache-2.0. Its
  [official effects API](https://kyant.gitbook.io/backdrop/api/backdrop-effects)
  documents API 31 effects and API 33 lens/refraction/dispersion. Its scene-layer
  integration is useful for future composited-UI glass, but is not needed here.
- [Haze official docs](https://chrisbanes.github.io/haze/latest/) describe its
  separately versioned core/blur/glass modules and an
  [Apache-2.0 license](https://github.com/chrisbanes/haze/blob/main/LICENSE). No Haze source was copied and no
  dependency compatibility was presumed or introduced.

## Validation limits / required checks

`GlassRenderPolicyTest` covers opt-in, API, low memory, reduced motion, hardware,
missing still, focus, finite dimensions, area limits and failure latching. Run with
`:app:testDebugUnitTest`; compilation/lint and existing focus tests are still required.
`GlassMaterialSmokeTest` adds runtime AGSL compilation and focus/press/reduced-motion/
missing-art/failure-transition smoke checks. Actual shader compilation is an
Android-runtime check, not a JVM test. On API 33+
validate ENHANCED against a licensed scene, no-art/error states, rapid scene changes,
focus/press/release, scrolling, reduced motion and switching into/out of playback.
Check API 26/30 BASIC and API 31 BACKDROP independently. Inspect crop alignment and
text contrast at both TV resolutions. Physical low-end SoC frame time, GPU memory,
thermal behavior, shader-driver failure recovery, and pixel fidelity to V4 have
not been measured by this implementation. Existing media/CI/golden files are not
changed by this pass.

## Phone controls

Phone primary/secondary actions in browsing, detail, search, settings and playback
now reuse the V4 static depth material via `MobileGlassButton` and
`MobileGlassOutlinedButton`. They retain Material3's single native button role,
click handling, ripple, disabled semantics and at least 48 dp height. Width/weight
constraints and caller content padding are preserved. Focus and press change the
material without adding scale animation. Text-only links and navigation items keep
their original presentation. Phone controls explicitly provide BASIC and no still:
this is a truthful static fallback, not phone scene/video refraction. Font families
are not set by these wrappers.

`MobileGlassButtonTest` exercises both button roles, physical touch clicks, target
height and disabled state. The API-33+ glass smoke test uses a striped fixture and
requires a positive successful hardware lens draw count and a completed window
frame, so a test that silently skips the lens branch fails rather than passing as
shader coverage. Each frame barrier has a 10-second deadline and releases its
listener/thread. It uses no Compose force-redraw, PixelCopy or screenshot readback.
The pressed state must add a lens draw; reduced-motion, missing-art and failure
states must not. This verifies branch execution and runtime survival, not the
visible displacement or pixel fidelity.

At the QA 4K viewport (3840×2160, density 640), the 160×45 dp smoke control occupies
640×180 = 115,200 local pixels, below the 240,000-pixel gate. Large controls may
correctly downgrade at high density; that is expected policy, not a shader error.
The source guardrails in `scripts/tests/test_glass_smoke_contract.py` ensure the
smoke does not accidentally restore bitmap readback; Android execution remains
necessary.


## Brand and typography integration

The glass background is drawn before text/icons. Noto's asynchronous font load can
remeasure labels but does not enter the lens input; brand header trails are likewise
not sampled. The phone wrapper does not override the installed Material typography.
Use the integrated runtime matrix to inspect mixed Latin/SC/TC/Japanese labels at
normal and enlarged font scale, since a native font raster sheet alone does not
prove that fixed-width buttons avoid clipping. No physical-device performance or
phone optical-refraction claim follows from successful font/brand/glass compilation.

Glass and brand startup both use static fallback for the system low-RAM flag or
memory class <=128 MB, including devices without the low-RAM flag. Brand runtime
tests cover that boundary; this conservative policy is not a memory benchmark.
