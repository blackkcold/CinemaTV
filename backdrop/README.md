# Production Backdrop adapter

Pinned Kyant AndroidLiquidGlass Android-branch engine, supplied by the user-approved
CinemaTV Glass Lab f49a5bf. See NOTICE.md and upstream-lock.json. The optical shader
and material parameter choices are unchanged here; the app owns appearance/tiering.

## Host contract

- Create the source with nullable `rememberSafeLayerBackdrop(failureState)`, not
  the upstream default `rememberLayerBackdrop()` allocator. Source creation and
  release are guarded; abandoned compositions release their allocation exactly once.
- Remember one `BackdropFailureState()` for the root scene/session. Pass the same
  instance to every `layerBackdrop` source and every `drawBackdrop`/`drawPlainBackdrop`
  consumer. Check `failed` when selecting the app's effective quality. Switching
  quality does not reset the failure. A new scene session may create a new state.
- `failure.reason` contains only the renderer stage and exception class. Never show
  `failure.failure?.cause.message` to the user or include it in diagnostics.
- Pass `onDrawFallback: DrawScope.() -> Unit` to each material. It must draw the
  complete static fallback, including enough opacity to cover a partially drawn
  optical material. Paint the same safe material behind the effect initially.
- Apply the effect to an independent background Box. Draw text/icons afterward as
  siblings. Emergency repaint does not replay drawContent and therefore must never
  be asked to recover foreground children included inside the material node.
- Draw callbacks must not change semantics or interact with focus. Material failure
  leaves the host's native clickable/selectable node intact.
- API26–30: the host must not call `rememberLayerBackdrop()` at all. It should draw
  its static material without a live source. As a second guard, layerBackdrop and
  drawBackdrop entry points construct no optical modifier nodes below API31.
- API31/32: use blur and Plain highlight; lens and AGSL highlight helpers are API33
  gated. RuntimeShader is never constructed by those helpers below33.
- Every effect/source draw checks the actual native Canvas hardware flag. Software
  Canvas latches fallback. Effects are only created/applied from an eligible draw,
  not from attachment or state-observation callbacks.
- Do not share a source across Android Dialog/Popup windows or into playback.
  The source must exclude its own consumers, text/icon foreground and video surfaces.

Example (inside an API31+ scene branch):

```kotlin
val failure = remember { BackdropFailureState() }
val source = rememberSafeLayerBackdrop(failure)
// If source == null, render the static scene/material without optical consumers.
Box {
    Scene(if (source == null) Modifier else Modifier.layerBackdrop(source, failureState = failure))
    if (source != null) Box(Modifier.drawBackdrop(
        backdrop = source,
        shape = { RoundedCornerShape(26.dp) },
        effects = { blur(12.dp.toPx()) },
        highlight = { Highlight(style = HighlightStyle.Plain()) },
        failureState = failure,
        onDrawBehind = { paintStaticMaterial() },
        onDrawFallback = { paintStaticMaterial() },
    ))
    // Foreground is a separate later sibling.
    Foreground()
}
```

The optional `failureState` parameters preserve existing source compatibility, but
production must pass a remembered shared instance. The default is only a local
modifier-instance latch, not a durable root-session policy.

## Failure coverage

Source allocation/recording, optical layer creation, effect construction/application,
material rendering, highlight shader construction/drawing, inner/outer shadows,
and layer release have synchronous RuntimeException boundaries. Drawing restores
its Canvas state before static repaint. Foreground drawContent is never retried or
swallowed. RuntimeException failures latch; Error subclasses, native driver crashes
and process termination do not become false success.

`BackdropFailureState.fail(stage, cause)` allows the host to report its own
synchronous renderer failure and supports deterministic fallback tests. Test that
its state is observed on the rendered frame, rather than inferring fallback from
an exception counter.

## Validation

- `python3 backdrop/scripts/verify-production-source.py`: pinned hashes, unmodified
  shader, production build/API guards and failure-boundary source contracts.
- `:backdrop:testDebugUnitTest`: session latch, no retries, first failure preserved,
  sanitized reason, RuntimeException-only behavior, forgotten/abandoned source
  ownership, allocation/release failure and exactly-once release.
- `:backdrop:lintDebug :backdrop:assembleDebug`: compile/API checks.

These are not a substitute for instrumented API26/31/33+ testing, native-window
pixel evidence, lifecycle/focus tests or physical-device GPU profiling.

## Reviewed AndroidX API/lifecycle references

Reviewed against the official AndroidX sources on 2026-11-05. These document the
public context factory and ownership contract; this adapter is not a copy of their
implementation and does not change the production dependency versions:

- [rememberGraphicsLayer / GraphicsContextObserver](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/graphics/GraphicsLayerScope.kt)
- [LocalGraphicsContext](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/platform/CompositionLocals.kt)
- [GraphicsContext](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/GraphicsContext.kt)
