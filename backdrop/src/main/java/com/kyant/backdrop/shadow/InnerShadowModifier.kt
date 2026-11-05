package com.kyant.backdrop.shadow

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.BackdropFailureState
import com.kyant.backdrop.attemptBackdropDraw
import com.kyant.backdrop.ShapeProvider
import com.kyant.backdrop.clipOutline

internal class InnerShadowElement(
    val shapeProvider: ShapeProvider,
    val shadow: () -> InnerShadow?,
    val failureState: BackdropFailureState,
    val onDrawFallback: (DrawScope.() -> Unit)?
) : ModifierNodeElement<InnerShadowNode>() {

    override fun create(): InnerShadowNode {
        return InnerShadowNode(shapeProvider, shadow, failureState, onDrawFallback)
    }

    override fun update(node: InnerShadowNode) {
        node.shapeProvider = shapeProvider
        node.shadow = shadow
        node.failureState = failureState
        node.onDrawFallback = onDrawFallback
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "innerShadow"
        properties["shapeProvider"] = shapeProvider
        properties["shadow"] = shadow
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InnerShadowElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (shadow != other.shadow) return false
        if (failureState !== other.failureState) return false
        if (onDrawFallback != other.onDrawFallback) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + shadow.hashCode()
        result = 31 * result + failureState.hashCode()
        result = 31 * result + (onDrawFallback?.hashCode() ?: 0)
        return result
    }
}

internal class InnerShadowNode(
    var shapeProvider: ShapeProvider,
    var shadow: () -> InnerShadow?,
    var failureState: BackdropFailureState,
    var onDrawFallback: (DrawScope.() -> Unit)?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var shadowLayer: GraphicsLayer? = null

    private val paint = Paint()
    private var clipPath: Path? = null

    private var prevRadius = Float.NaN

    override fun ContentDrawScope.draw() {
        drawContent()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || failureState.failed) return
        attemptBackdropDraw(failureState, "innerShadow.draw") {
            val shadow = shadow() ?: return@attemptBackdropDraw

            val shadowLayer = shadowLayer
            if (shadowLayer != null) {
                val size = size
                val density: Density = this
                val layoutDirection = layoutDirection

                val radius = shadow.radius.toPx()
                val offsetX = shadow.offset.x.toPx()
                val offsetY = shadow.offset.y.toPx()

                val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)
                val clipPath =
                    if (outline is Outline.Rounded) {
                        clipPath ?: Path().also { clipPath = it }
                    } else {
                        null
                    }

                configurePaint(shadow)

                shadowLayer.alpha = shadow.alpha
                shadowLayer.blendMode = shadow.blendMode
                if (prevRadius != radius) {
                    shadowLayer.renderEffect =
                        if (radius > 0f) {
                            BlurEffect(radius, radius, TileMode.Decal)
                        } else {
                            null
                        }
                    prevRadius = radius
                }
                shadowLayer.record {
                    val canvas = drawContext.canvas
                    canvas.save()
                    canvas.clipOutline(outline, clipPath)
                    canvas.drawOutline(outline, paint)
                    canvas.translate(offsetX, offsetY)
                    canvas.drawOutline(outline, ShadowMaskPaint)
                    canvas.translate(-offsetX, -offsetY)
                    canvas.restore()
                }

                val canvas = drawContext.canvas
                canvas.save()
                canvas.clipOutline(outline, clipPath)
                drawLayer(shadowLayer)
                canvas.restore()
            }
        }
        if (failureState.failed) onDrawFallback?.invoke(this)
    }

    override fun onAttach() {
        failureState.attempt("innerShadow.attach") {
            val graphicsContext = requireGraphicsContext()
            shadowLayer =
                graphicsContext.createGraphicsLayer().apply {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
        }
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer?.let { layer ->
            shadowLayer = null
            try {
                graphicsContext.releaseGraphicsLayer(layer)
            } catch (cause: RuntimeException) {
                failureState.fail("innerShadow.detach", cause)
            }
        }
    }

    private fun DrawScope.configurePaint(shadow: InnerShadow) {
        paint.color = shadow.color
    }

    private fun DrawScope.drawMaskedShadow(outline: Outline, layer: GraphicsLayer) {
        val canvas = drawContext.canvas
        canvas.save()
        canvas.clipOutline(outline, clipPath)
        drawLayer(layer)
        canvas.restore()
    }
}

private val ShadowMaskPaint = Paint().apply {
    blendMode = BlendMode.Clear
}
