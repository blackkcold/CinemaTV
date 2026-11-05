package dev.cinematv.ui.tv.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.cinematv.ui.tv.design.*

/** Approved f49a5bf material. A real live scene is mandatory; video/dialogs stay safe. */
@Composable
fun Modifier.cinemaGlassMaterial(
    radius: Dp = 26.dp,
    focus: Float = 0f,
    press: Float = 0f,
    selected: Boolean = false,
    prominent: Boolean = false,
    dense: Boolean = false,
    backdropOverride: Backdrop? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val effects = LocalTvEffects.current
    val source = currentProductionGlassScene()
    val failure = LocalProductionGlassFailure.current
    val diagnostics = LocalGlassDrawDiagnostics.current
    val diagnosticScope = LocalGlassDrawScope.current
    val emphasis = maxOf(focus, if (selected) .4f else 0f)
    val live = source != null && failure?.failed != true && effects.glass != GlassQuality.BASIC
    val full = live && effects.glass == GlassQuality.ENHANCED && Build.VERSION.SDK_INT >= 33
    val safe: DrawScope.() -> Unit = { drawSafeGlass(radius, emphasis, press, prominent) }
    if (!live) return then(if (layerBlock == null) Modifier else Modifier.graphicsLayer(layerBlock))
        .drawWithContent {
            safe(); drawContent()
            diagnostics?.record(GlassRenderMode.BASIC, diagnosticScope, sourcePresent = source != null)
        }
    // Observe outside the entire optical/highlight/shadow chain: an outer pass can
    // still fail after the core backdrop has drawn and repaint Basic for this frame.
    return drawWithContent {
        drawContent()
        diagnostics?.record(
            if (failure?.failed == true) GlassRenderMode.BASIC else if (full) GlassRenderMode.FULL else GlassRenderMode.BLUR,
            diagnosticScope, sourcePresent = source != null,
        )
    }.drawBackdrop(
        backdrop = backdropOverride ?: source!!.backdrop,
        shape = { RoundedCornerShape(radius) },
        effects = {
            vibrancy()
            blur((if (!full) 12f else if (dense) 6f else 1.6f).dp.toPx())
            if (full) lens((18f + 5f * emphasis + 3f * press).dp.toPx(),
                (26f + 9f * emphasis + 10f * press).dp.toPx(),
                depthEffect = true, chromaticAberration = true)
        },
        highlight = {
            if (full) Highlight(width = (.65f + .20f * emphasis).dp, blurRadius = .8.dp, alpha = .72f,
                style = HighlightStyle.Default(color = Color.White.copy(.46f + .12f * emphasis),
                    angle = 45f + press * 32f, falloff = 1.5f))
            else Highlight(width = (.6f + .15f * emphasis).dp, blurRadius = .6.dp, alpha = .7f,
                style = HighlightStyle.Plain(color = Color.White.copy(.27f + .12f * emphasis), blendMode = BlendMode.SrcOver))
        },
        shadow = { Shadow(radius = 20.dp, offset = DpOffset(0.dp, 8.dp), color = Color.Black.copy(.18f)) },
        innerShadow = { InnerShadow(radius = if (full) 8.dp else 3.dp,
            offset = DpOffset(0.dp, if (full) 3.dp else 1.dp), color = Color(0xFF021425).copy(if (full) .22f else .12f)) },
        layerBlock = layerBlock,
        failureState = failure,
        onDrawBehind = safe,
        onDrawFallback = safe,
        onDrawSurface = {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(.09f + emphasis * .035f),
                Color(0xFFB9DDF5).copy(.035f), Color.White.copy(.025f))))
            if (!full) drawRect(Color(0xFF6E9DBB).copy(.08f))
            if (dense) drawRect(Color(0xFF06111D).copy(.14f))
            if (press > 0f) drawRect(Color.White.copy(.07f * press))
        },
    )
}

/** Constant-cost opaque depth. This is intentionally not advertised as backdrop blur. */
internal fun DrawScope.drawSafeGlass(radius: Dp, focus: Float, press: Float, prominent: Boolean) {
    val r = minOf(radius.toPx(), size.height / 2, size.width / 2)
    val strong = focus > .1f || prominent
    val top = if (press > .5f) Color(0xFF2E3C46) else if (strong) Color(0xFF4B5A65) else Color(0xFF2A343D)
    drawRoundRect(Brush.verticalGradient(listOf(top, Color(0xFF1B242B))), cornerRadius = CornerRadius(r))
    val inset = .5.dp.toPx()
    drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(.24f + focus * .22f),
        Color.White.copy(.07f), Color.White.copy(.17f))), topLeft = Offset(inset, inset),
        size = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)), style = Stroke((.65f + .2f * focus).dp.toPx()))
}

/** Compatibility surface for render tests and non-interactive material specimens. */
@Composable
fun GlassControlMaterial(focused: Boolean, pressed: Boolean, selected: Boolean,
    prominent: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalTvEffects.current.reducedMotion
    val focus by animateFloatAsState(if (focused) 1f else 0f,
        if (reduced) snap() else spring(.65f, 320f), label = "material-focus")
    val press by animateFloatAsState(if (pressed) 1f else 0f,
        if (reduced) snap() else spring(.55f, 380f), label = "material-press")
    Box(modifier.cinemaGlassMaterial(focus = focus, press = press, selected = selected, prominent = prominent))
}

/** A separate, glass-only navigation recording feeds this lens; labels are never input. */
@Composable
internal fun Modifier.cinemaNavigationLens(backdrop: Backdrop?, layerBlock: GraphicsLayerScope.() -> Unit): Modifier {
    val failure = LocalProductionGlassFailure.current
    val full = currentProductionGlassScene() != null && backdrop != null &&
        LocalTvEffects.current.glass == GlassQuality.ENHANCED && Build.VERSION.SDK_INT >= 33 && failure?.failed != true
    val safe: DrawScope.() -> Unit = {
        drawRoundRect(Color.White.copy(.12f), cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(Color.White.copy(.25f), cornerRadius = CornerRadius(size.height / 2), style = Stroke(.65.dp.toPx()))
    }
    if (!full) return graphicsLayer(layerBlock).drawWithContent { safe(); drawContent() }
    val failureSafe: DrawScope.() -> Unit = { drawSafeGlass(50.dp, .4f, 0f, true) }
    return drawBackdrop(
        backdrop = backdrop!!, shape = { RoundedCornerShape(50) },
        effects = { lens(15.dp.toPx(), 22.dp.toPx(), chromaticAberration = true) },
        highlight = { Highlight(width = .7.dp, blurRadius = .75.dp, alpha = .75f,
            style = HighlightStyle.Default(color = Color.White.copy(.52f), falloff = 1.4f)) },
        shadow = { Shadow(radius = 10.dp, color = Color.Black.copy(.12f)) },
        innerShadow = { InnerShadow(radius = 7.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(.13f)) },
        failureState = failure, onDrawBehind = failureSafe, onDrawFallback = failureSafe, layerBlock = layerBlock,
        onDrawSurface = { drawRect(Color.White.copy(.07f)) },
    )
}
