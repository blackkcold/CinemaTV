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
