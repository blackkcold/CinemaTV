package com.kyant.backdrop.backdrops

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.BackdropFailureState
import com.kyant.backdrop.attemptBackdropDraw
import com.kyant.backdrop.recordLayer

fun Modifier.layerBackdrop(
    backdrop: LayerBackdrop,
    failureState: BackdropFailureState? = null
): Modifier = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) this
    else this then LayerBackdropElement(backdrop, failureState ?: BackdropFailureState())

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val failureState: BackdropFailureState
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop, failureState)
    }

    override fun update(node: LayerBackdropNode) {
        node.backdrop = backdrop
        node.failureState = failureState
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (failureState !== other.failureState) return false

        return true
    }

    override fun hashCode(): Int {
        return 31 * backdrop.hashCode() + failureState.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var failureState: BackdropFailureState
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        drawContent()
        attemptBackdropDraw(failureState, "source.record") {
            recordLayer(backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
