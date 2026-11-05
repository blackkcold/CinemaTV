package dev.cinematv.ui.tv.motion

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import dev.cinematv.ui.tv.design.LocalTvEffects
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private object UptimePosterMotionClock : PosterMotionClock {
    override fun nowMillis(): Long = SystemClock.uptimeMillis()
    override suspend fun awaitHintExpiry(issuedAtMillis: Long) {
        var remaining = posterHintRemainingMillis(issuedAtMillis, nowMillis())
        while (remaining > 0L) {
            delay(remaining)
            remaining = posterHintRemainingMillis(issuedAtMillis, nowMillis())
        }
    }
}

internal val LocalPosterMotionClock = staticCompositionLocalOf<PosterMotionClock> { UptimePosterMotionClock }

@Stable
internal class PosterMotionInput(private val clock: PosterMotionClock) {
    private var state by mutableStateOf(PosterMotionInputState())
    val hintState: PosterMotionInputState get() = state

    fun direction(): PosterDirection = state.directionAt(clock.nowMillis())
    fun keyDown(keyCode: Int) { state = state.keyDown(keyCode, clock.nowMillis()) }
    fun clear() { state = state.clear() }
    fun expire(expectedGeneration: Long) { state = state.expire(expectedGeneration) }
}

internal val LocalPosterMotionInput = staticCompositionLocalOf<PosterMotionInput?> { null }

/**
 * One passive listener in the existing TV root. The caller applies [Modifier] to
 * its existing layout, so there is no new focus group, layout or glass recording.
 * Native preview events arrive before focus traversal, including on the old card.
 */
@Composable
fun PosterMotionProvider(resetKey: Any?, content: @Composable (Modifier) -> Unit) {
    val clock = LocalPosterMotionClock.current
    val input = remember(clock) { PosterMotionInput(clock) }
    val enabled = !LocalTvEffects.current.reducedMotion
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(input) {
        // The timer subscribes outside composition. A D-pad hint never invalidates
        // the whole root (only the currently focused card reads its direction).
        snapshotFlow { input.hintState }.collectLatest { hint ->
            if (hint.direction != PosterDirection.None) {
                // Deadline starts at native key receipt, not collector scheduling.
                clock.awaitHintExpiry(hint.issuedAtMillis)
                input.expire(hint.generation)
            }
        }
    }
    LaunchedEffect(resetKey, enabled, windowFocused) { input.clear() }
    CompositionLocalProvider(LocalPosterMotionInput provides input) {
        content(Modifier.onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                if (enabled && windowFocused) input.keyDown(event.nativeKeyEvent.keyCode) else input.clear()
            }
            false // Navigation, activation, key-up and Back retain their existing owners.
        })
    }
}
