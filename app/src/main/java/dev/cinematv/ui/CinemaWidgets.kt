package dev.cinematv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import dev.cinematv.ui.tv.components.cinemaGlassMaterial
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.ui.typography.cinemaUiFontFamily
import dev.cinematv.ui.tv.design.LocalTvEffects
import dev.cinematv.ui.tv.focus.trackTvContentFocus
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.icons.CinemaIcon
import dev.cinematv.ui.icons.CinemaSymbol
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString

val Ink = Color(0xFF090A0D)
val Glass = Color(0xB5202229)
val Ivory = Color(0xFFF5F5F7)
val Muted = Color(0xFFA8A9B0)
val Accent = Color(0xFFD5DDE9)
val DeepRose = Color(0xFF3A1E28)

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
            .heightIn(min = 40.dp)
            .trackTvContentFocus()
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .focusProperties { canFocus = true }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Transform the background through the engine's inverse-layer support;
        // never scale a shared ancestor whose coordinates the source cannot invert.
        Box(Modifier.matchParentSize().cinemaGlassMaterial(focus = focus, press = press,
            selected = selected, prominent = prominent, layerBlock = { scaleX = scale; scaleY = scale }))
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
    }
}

@Composable
fun TvInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    onSubmit: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE632333A))
            .border(1.5.dp, if (focused) Accent else Color.White.copy(alpha = .14f), RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value, onValueChange = onValueChange,
            textStyle = TextStyle(color = Ivory, fontSize = 18.sp, fontFamily = cinemaUiFontFamily()),
            singleLine = true, cursorBrush = SolidColor(Accent),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, color = Muted, fontSize = 17.sp)
                inner()
            },
            modifier = Modifier.fillMaxWidth().trackTvContentFocus()
        )
    }
}

