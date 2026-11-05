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

@Stable
internal class PosterMotionInput {
    private var state by mutableStateOf(PosterMotionInputState())
    val generation: Long get() = state.generation

    fun direction(nowMillis: Long = SystemClock.uptimeMillis()): PosterDirection = state.directionAt(nowMillis)
    fun keyDown(keyCode: Int) { state = state.keyDown(keyCode, SystemClock.uptimeMillis()) }
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
    val input = remember { PosterMotionInput() }
    val enabled = !LocalTvEffects.current.reducedMotion
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(input) {
        // The timer subscribes outside composition. A D-pad hint never invalidates
        // the whole root (only the currently focused card reads its direction).
        snapshotFlow { input.generation }.collectLatest { generation ->
            delay(PosterMotionTokens.DIRECTION_HINT_MILLIS)
            input.expire(generation)
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
