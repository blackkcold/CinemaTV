package dev.cinematv.ui.tv.focus

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import dev.cinematv.BuildConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job

/**
 * Cancels only this target's already-entered relocation requests when focus leaves.
 * Does not move focus, stop an ancestor scroller, or reject non-focused requests.
 * In particular, a Foundation DEFAULT launch entering after blur still passes
 * through: the public request API does not identify its original focus generation.
 */
internal fun Modifier.cancelRelocationOnFocusLoss(): Modifier = then(FocusBoundRelocationElement)

private data object FocusBoundRelocationElement : ModifierNodeElement<FocusBoundRelocationNode>() {
    override fun create() = FocusBoundRelocationNode()
    override fun update(node: FocusBoundRelocationNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "cancelRelocationOnFocusLoss" }
}

private class FocusBoundRelocationNode : Modifier.Node(), BringIntoViewModifierNode, FocusEventModifierNode {
    private val requests = FocusRequestLifetime()
    private var focused = false

    private fun trace(event: String) {
        if (BuildConfig.DEBUG) Log.d("CinemaTVCardRelocation",
            "uptime=${SystemClock.uptimeMillis()} node=${System.identityHashCode(this)} focused=$focused event=$event")
    }

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.isFocused
        trace("focus-event")
        requests.updateFocus(focused)
    }
    override fun onReset() { focused = false; trace("reset"); requests.reset() }
    override fun onDetach() { focused = false; trace("detach"); requests.reset() }

    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        if (!isAttached || !childCoordinates.isAttached) return
        val coordinates = requireLayoutCoordinates()
        fun translatedBounds(): Rect? {
            if (!isAttached || !coordinates.isAttached || !childCoordinates.isAttached) return null
            val bounds = boundsProvider() ?: return null
            // Map every corner, not only the origin: the child's graphicsLayer
            // can scale/translate while the parent is fulfilling this request.
            val topLeft = coordinates.localPositionOf(childCoordinates, bounds.topLeft)
            val topRight = coordinates.localPositionOf(childCoordinates, bounds.topRight)
            val bottomRight = coordinates.localPositionOf(childCoordinates, bounds.bottomRight)
            val bottomLeft = coordinates.localPositionOf(childCoordinates, bounds.bottomLeft)
            return Rect(minOf(minOf(topLeft.x, topRight.x), minOf(bottomRight.x, bottomLeft.x)),
                minOf(minOf(topLeft.y, topRight.y), minOf(bottomRight.y, bottomLeft.y)),
                maxOf(maxOf(topLeft.x, topRight.x), maxOf(bottomRight.x, bottomLeft.x)),
                maxOf(maxOf(topLeft.y, topRight.y), maxOf(bottomRight.y, bottomLeft.y)))
        }
        var latestBounds = translatedBounds() ?: return
        coroutineScope {
            val request = coroutineContext.job
            val identity = System.identityHashCode(request)
            trace("request-enter:$identity")
            val token = requests.register { trace("request-cancel:$identity"); request.cancel() }
            try {
                // This public extension starts at the nearest ANCESTOR; it does
                // not dispatch back into this node's member function.
                this@FocusBoundRelocationNode.bringIntoView {
                    val updated = translatedBounds()
                    if (updated != null) latestBounds = updated
                    else {
                        // End only this request if its target disappears. Never
                        // pass null to the extension: it means whole-node bounds.
                        request.cancel()
                    }
                    latestBounds
                }
            } finally {
                trace("request-end:$identity cancelled=${request.isCancelled}")
                requests.unregister(token)
            }
        }
    }
}
