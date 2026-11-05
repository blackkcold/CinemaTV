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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.icons.CinemaIcon
import dev.cinematv.ui.icons.CinemaSymbol
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString

val Ink = Color(0xFF090A0D)
val Glass = Color(0xB5202229)
val Ivory = Color(0xFFF5F5F7)
val Muted = Color(0xFFA8A9B0)
val Accent = Color(0xFFD5DDE9)
val DeepRose = Color(0xFF3A1E28)

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
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .focusProperties { canFocus = true }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Transform the background through the engine's inverse-layer support;
        // never scale a shared ancestor whose coordinates the source cannot invert.
        Box(Modifier.matchParentSize().cinemaGlassMaterial(focus = focus, press = press,
            selected = selected, prominent = prominent, layerBlock = { scaleX = scale; scaleY = scale }))
        Row(Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) CinemaIcon(icon, Modifier.size(20.dp), selected = iconSelected,
                onLightSurface = false)
            if (label.isNotEmpty()) Text(
                if (icon != null) label.removePrefix("← ").removePrefix("▶ ").removePrefix("＋ ") else label,
                modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(label) },
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = Ivory,
                maxLines = 1
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
            modifier = Modifier.fillMaxWidth()
        )
    }
}

