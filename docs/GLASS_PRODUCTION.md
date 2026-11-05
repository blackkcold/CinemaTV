# Production glass

This integration uses the two materials approved from Glass Lab `f49a5bf`.
It is native Compose, not a screenshot, WebView or a blur of the video surface.
The standalone lab remains the visual reference; this document describes the
production source graph and fallback contract rather than claiming device speed.

## Materials and admission

| Effective mode | Implementation | Admission |
|---|---|---|
| Basic | Opaque depth gradient and soft edge; no recording/effect nodes | All supported Android versions, including API26–30 |
| Blur | Live scene replay, 12dp platform blur, plain highlight, no lens/AGSL highlight | API31+, hardware window, eligible memory/power/thermal state |
| Full | Live scene replay, approved depth/refraction/chromatic optics and softened directional edge | API33+, same guards, explicit user selection |

`AUTO` is conservative Blur, never a guessed high-end GPU classification. The
persisted `AUTO/BASIC/BACKDROP/ENHANCED` values are unchanged. An Android version or
heap size is not a physical GPU benchmark. A low-RAM flag or memory class at most
128 MiB selects Basic. Power saving and severe thermal pressure select Basic;
moderate thermal pressure caps Full to Blur and stops animation. These temporary
decisions do not overwrite the saved preference.

Each new foreground session first submits a lightweight Basic hardware frame.
Effects become eligible only after the frame-commit callback, window focus and a
following frame boundary. The actual drawing Canvas is checked again before
effects are created/applied. Software drawing and caught renderer failures latch
Basic for the scene session instead of retrying every frame or preference change.
Driver termination, native crashes and memory exhaustion are not recoverable
success cases and still require device testing.

Reduced Motion and Android's disabled-animator setting are separate from material
quality: they remove geometric animation while retaining a selected static Full
or Blur material. Lifecycle callbacks refresh platform hints and remove listeners
when stopped. There is no continuous hardware polling or model-name blacklist.

## Source graph

- TV records only `BackdropLayer`: decoded landscape art, original atmospheric
  fallback, crossfade and scrims. Page UI and consumers are later siblings.
- Navigation records a glass-only container, combines it with the scene for the
  moving lens, then paints labels. No label is recursively refracted into itself.
- TV `GlassAction` and mobile `MobileGlassButton` keep their native interaction
  nodes. The material Box and text/icon foreground are separate siblings with
  matching transforms. Emergency fallback repaint cannot cover the foreground.
- Mobile Home records only the local hero. Detail controls below its image, other
  routes and mobile navigation intentionally have no live source yet.
- An inherited source is rejected when the Compose owner differs, including an
  Android dialog. Separate popup/dialog windows use Basic unless a future change
  provides and validates their own source.
- Playback has no source. Media3 `SurfaceView` pixels are not in the Compose layer
  recording. Controls use Basic, not a fabricated blur of a movie still. Neither
  `PixelCopy` nor recurring CPU/GPU readback is used for rendering.

## Fidelity and cost

The pinned Full shader is byte-identical to the approved lab. Production does not
silently substitute a frozen bitmap, reduce the source resolution, disable
chromatic refraction on large controls or gate Full on focus. Blur intentionally
removes those optical passes and Basic removes scene recording. These are visible
material choices, not three claims about achieved frame rate. Artwork decode is
bounded independently of render-layer resolution.

Only actual scene changes and interactions drive redraws. There is no perpetual
decorative motion. Full-resolution live recording and multiple optical consumers
still have GPU/memory cost, particularly on 4K TVs. AUTO does not promise 60 fps.
Physical low-end TVs, vendor drivers, thermals, sustained usage and real video
overlays remain a separate verification requirement.

## Verification and provenance

- `ProductionGlassPolicyTest`: deterministic admission, power/thermal/memory,
  first-window readiness, preference preservation and independent Reduced Motion.
- `backdrop` unit tests and source-lock verifier: session failure contract and
  unchanged upstream optical shader.
- `GlassMaterialSmokeTest`: actual source draw, Full/Blur transitions, input state,
  missing-source and latched-failure material fallback. No GPU screenshot readback
  is used by that smoke test.
- The dedicated `production-glass` workflow builds once and reuses identical APKs
  for targeted API35 TV/phone, API31 Blur and API26 Basic checks. Native window PNGs
  and source-SHA/viewport/foreground receipts are evidence of the tested main UI,
  not the independent lab. A conservative downgrade is reported, not overridden
  to manufacture Full coverage. Exact run results belong in the delivery report.
- Deferred page restoration captures route/bookmark ownership at entry, observes
  navigation and per-control content focus acquisition synchronously, and checks
  ownership after every suspension and
  immediately before target/fallback requests. New user navigation/content and
  cancelled work cannot be overwritten by a stale restoration. Dedicated native
  regression tests cover interrupted entry frames, lazy preparation/attachment,
  route changes, missing targets and unchanged normal restoration.
- Horizontal rail traversal transfers focus to already attached cards in the
  native key event instead of waiting for scrolling or two rendered frames.
  Offscreen preparation keeps smooth scrolling and hands focus over as soon as
  the stable-key target attaches. New input, route/data changes and newer focus
  ownership cancel pending work. Dedicated actual-rail regressions cover visible
  movement, animated viewport crossings, rapid reversal and interruptions.
- Full release still requires the repository's media, D-pad, lifecycle and release
  gates. A targeted glass pass is not complete release acceptance. SwiftShader
  timing is not a physical television GPU or presented-frame guarantee.

The rendering engine is pinned to
[Kyant0/AndroidLiquidGlass 896a94a](https://github.com/Kyant0/AndroidLiquidGlass/tree/896a94a3ade1cc1a940b92365f942a34971fecda),
under Apache-2.0. `backdrop/NOTICE.md`, `LICENSE` and `upstream-lock.json` document
source and modifications; license assets are packaged with the APK. CinemaTV's
own integration is MIT. The earlier `GlassRenderPolicy`/StillLens tests document
the retired V4 renderer and do not govern this production renderer. Aerial's
design/settings research was reference only, not copied implementation code.
