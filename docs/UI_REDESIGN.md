# CinemaTV immersive UI rebuild — 2026-11-04

Reviewed baseline: `9b5f208` / v0.3.0-preview. Native Kotlin, Compose for TV,
LibreTV protocol boundary, Media3 ownership and existing branding stay in place.

## Reviewed implementation decisions

- Use standard Foundation lazy lists/grids with TV focus grouping. TV-specific
  lazy containers are deprecated; do not add them back.
- Compose TV owns the TV theme; phone Material 3 screens stay independent.
- Build a full-viewport scene, responsive hero, compact navigation and content
  rails. Movie and series catalogs use different compositions.
- Never stretch a portrait poster into a hero. Keep the last decoded landscape
  during image changes; use original atmospheric fallback art without a backdrop.
- Remember focus and scroll per route and per region, with stable provider/id
  identities. Return from detail/player to the original item, including offscreen items.
- Reduced-motion and low-RAM policies apply centrally. Background-aware glass is
  an image layer, not sampled video or a claim of measured optical refraction.
- Continue watching uses real saved position/duration; unknown duration does not
  produce a fabricated percentage. Filters use only supplied metadata.
- No hardware QA or private signing evidence exists in the baseline. Publish the
  tested APK as a preview; leave stable certification truthful and gated.

## Delivery checkpoints

1. Design tokens, effects policy, focus/scroll memory and presentation contracts.
2. Scene/artwork, navigation, hero, rails, home, discovery and catalogs.
3. Search, details, library, live guide, settings, player and phone alignment.
4. Deterministic emulator screenshots, remote regression tests, CI and release.

Each coherent checkpoint is pushed to GitHub. CI compilation and test results,
emulator evidence and remaining physical-device limits are recorded at release.

Official API references reviewed:
- https://developer.android.com/training/tv/playback/compose/lists
- https://developer.android.com/develop/ui/compose/touch-input/focus/focus-restoration

## Implemented structure

The legacy `Pages.kt` has been retired. TV routes now use the new theme,
composition-local layout/effects policy, live focus registry, full scene and
independent pages. Player and mobile layouts share business state while keeping
input and rendering behavior separate. Progress records accept new duration and
art fields without invalidating older records.

## Automated acceptance

- JVM: focus memory, scroll state, metadata identity, catalog filtering, true
  duration progress and graphics fallback policy.
- BFF: protocol/auth integration, exact title/year matching and ambiguity rejection.
- TV: default focus, repeated horizontal moves across viewport edges, offscreen
  return, title/detail selection, library dialog, catalog/discovery/search/live/
  settings, playback panel Back, saved duration/position and frame sample export.
- Phone: launcher/banner, portrait navigation and playback across rotation.
- Scene capture waits for the requested art to decode. UTP exports screenshots
  before uninstalling test packages. Reviewed original-fixture PNGs are the visual
  regression baseline; Apple screenshots are not used as copyrighted pixel goldens.

Physical hardware and signed-stable gates stay open until measured evidence exists.

## Visual regression contract

`design/ui/goldens/tv1080` and `tv4k` contain six reviewed captures (home,
detail, movie catalog, series catalog, discovery and settings) from the original
fixture. Each manifest records its source commit/run and small dynamic masks:
the transient library notification and settings' ephemeral localhost port.
No control bounds are masked. Separate viewport baselines retain platform
font/inset rasterization rather than widening the difference threshold.

CI normalizes captures to 960×540, requires mean RGB error ≤0.020 and the fraction
of pixels differing by more than 32 channel levels ≤0.08, and exports overlays
for failures. It also requires all 14 full-resolution captures, matching viewport
metadata, a nonempty focus trace and rendered frame samples. Emulator P95/P99 are
informational. Golden updates require visual review, not automatic acceptance.
