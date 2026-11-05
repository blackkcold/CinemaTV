package com.kyant.backdrop.backdrops

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.LocalGraphicsContext
import com.kyant.backdrop.BackdropFailureState
import com.kyant.backdrop.RememberedBackdropResource

private val DrawSceneContent: ContentDrawScope.() -> Unit = { drawContent() }

/**
 * Production source factory. Creation and release share the scene's synchronous
 * failure latch. No composable invocation is wrapped in try/catch, and API26–30 do
 * not even read the graphics context or create a RememberObserver/resource.
 *
 * The resource is forgotten when this call leaves composition (including a quality
 * fallback) and abandoned when a composition never commits. Both release at most once.
 */
@Composable
fun rememberSafeLayerBackdrop(
    failureState: BackdropFailureState,
    onDraw: ContentDrawScope.() -> Unit = DrawSceneContent,
): LayerBackdrop? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || failureState.failed) return null
    val graphicsContext = LocalGraphicsContext.current
    val resource = remember(graphicsContext, failureState) {
        RememberedBackdropResource(
            failureState = failureState,
            create = { graphicsContext.createGraphicsLayer() },
            release = { graphicsContext.releaseGraphicsLayer(it) },
        )
    }
    val layer = resource.value ?: return null
    // Explicit argument avoids rememberLayerBackdrop's unguarded default allocation.
    return rememberLayerBackdrop(graphicsLayer = layer, onDraw = onDraw)
}
