package dev.cinematv.ui.tv.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale as scaleCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.Ink
import dev.cinematv.ui.Ivory
import dev.cinematv.ui.icons.CinemaIcon
import dev.cinematv.ui.icons.CinemaSymbol
import dev.cinematv.ui.tv.design.LocalTvEffects
import dev.cinematv.ui.tv.design.TvHeight
import dev.cinematv.ui.tv.focus.trackTvContentFocus
import dev.cinematv.ui.typography.CinemaTvText as Text

// Stable layout modifiers: focus/press only change the existing graphics layers.
private val GlassActionLabelPadding = Modifier.glassActionContentPadding(horizontal = 16.dp)
private val GlassActionIconPadding = Modifier.glassActionContentPadding(horizontal = 8.dp)

/** Measure content once; fixed-size controls give up padding before clipping their text. */
private fun Modifier.glassActionContentPadding(horizontal: Dp) = layout { measurable, constraints ->
    // Keep at least 8dp on each side under bounded width. The remaining preferred
    // padding is added after measurement, so 80dp icon+text and 48dp labels still fit.
    val minimumInset = 8.dp.roundToPx()
    val content = measurable.measure(
        constraints.copy(minWidth = 0, minHeight = 0).offset(horizontal = -minimumInset * 2)
    )
    val width = constraints.constrainWidth(content.width + horizontal.roundToPx() * 2)
    // Do not subtract vertical padding before measuring: existing 35/36dp-high
    // callers must retain their natural line/icon height rather than crop it.
    val height = constraints.constrainHeight(content.height + 8.dp.roundToPx() * 2)
    layout(width, height) { content.placeRelative((width - content.width) / 2, (height - content.height) / 2) }
}

@Composable
fun GlassAction(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    prominent: Boolean = false,
    onFocus: () -> Unit = {},
    icon: CinemaSymbol? = null,
    iconSelected: Boolean = selected,
    /** A disabled action keeps its layout, is skipped by the remote and dims to 40%. */
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val effects = LocalTvEffects.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focus by animateFloatAsState(if (focused) 1f else 0f,
        if (effects.reducedMotion) snap() else spring(.65f, 320f), label = "action-focus")
    val press by animateFloatAsState(if (pressed) 1f else 0f,
        if (effects.reducedMotion) snap() else spring(.55f, 380f), label = "action-press")
    val scale = if (effects.reducedMotion) 1f else 1f + .045f * focus - .035f * press
    Box(
        modifier = modifier
            .heightIn(min = TvHeight.button)
            .graphicsLayer { alpha = if (enabled) 1f else .4f }
            .trackTvContentFocus()
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .focusProperties { canFocus = enabled }
            .semantics { this.selected = selected; if (!enabled) disabled() }
            .clickable(enabled = enabled, interactionSource = interactions, indication = null,
                role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Transform the background through the engine's inverse-layer support;
        // never scale a shared ancestor whose coordinates the source cannot invert.
        Box(Modifier.matchParentSize().cinemaGlassMaterial(focus = focus, press = press,
            selected = selected, prominent = prominent, level = dev.cinematv.ui.tv.design.GlassRenderMode.FULL,
            layerBlock = { scaleX = scale; scaleY = scale }))
        Row((if (label.isEmpty()) GlassActionIconPadding else GlassActionLabelPadding)
            .graphicsLayer { scaleX = scale; scaleY = scale },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) CinemaIcon(icon, Modifier.size(20.dp), selected = iconSelected,
                onLightSurface = false)
            if (label.isNotEmpty()) Text(
                if (icon != null) label.removePrefix("← ").removePrefix("▶ ").removePrefix("＋ ") else label,
                // Override only spoken text, retaining native text-layout semantics.
                modifier = Modifier.weight(1f, fill = false).semantics { text = AnnotatedString(label) },
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = Ivory,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
            )
        }
        // Keep the outline in the foreground, outside the optical pass. It uses
        // the same geometry as the material without an additional graphics layer.
        // Native focus, rather than animation progress, controls its visibility:
        // Reduced Motion must never make the active remote target ambiguous.
        Box(Modifier.matchParentSize().drawBehind {
            if (focused) {
                val inset = 1.dp.toPx()
                val outlineSize = Size((size.width - 2 * inset).coerceAtLeast(0f),
                    (size.height - 2 * inset).coerceAtLeast(0f))
                val radius = (minOf(26.dp.toPx(), size.width / 2, size.height / 2) - inset).coerceAtLeast(0f)
                scaleCanvas(scale) {
                    // A dark outer rim survives bright scenes; the bright core
                    // survives dark scenes. Neither fills or whitens the glass.
                    drawRoundRect(Ink, Offset(inset, inset), outlineSize,
                        CornerRadius(radius), style = Stroke(4.dp.toPx()))
                    drawRoundRect(Ivory, Offset(inset, inset), outlineSize,
                        CornerRadius(radius), style = Stroke(2.dp.toPx()))
                }
            }
        })
        if (selected) {
            // Selection persists independently of focus/press and Reduced Motion.
            // This foreground-only overlay never measures the label or changes the
            // native hit target. The opaque dark rim and bright core stay legible
            // over both light and dark scenes, without changing the glass optics.
            Box(Modifier.matchParentSize()) {
                Box(Modifier.align(Alignment.BottomCenter).offset(y = (-1).dp)
                    .size(width = 16.dp, height = 4.dp)
                    .testTag("glass-action-selection-indicator")
                    .background(Ink, RoundedCornerShape(50))
                    .padding(1.dp)
                    .background(Ivory, RoundedCornerShape(50)))
            }
        }
    }
}
