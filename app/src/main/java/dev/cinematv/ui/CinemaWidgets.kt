package dev.cinematv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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

val Ink = Color(0xFF0C0D11)
val Glass = Color(0xCC28282C)
val Ivory = Color(0xFFF7F5F1)
val Muted = Color(0xFFAFB0BA)
val Accent = Color(0xFFD9C6B6)
val DeepRose = Color(0xFF3A1E28)

@Composable
fun GlassAction(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    prominent: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "action-scale")
    val bg by animateColorAsState(
        when {
            focused || selected -> Ivory
            prominent -> Color(0xFFD5C5BB)
            else -> Glass
        }, label = "action-bg"
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(26.dp))
            .background(bg)
            .border(1.dp, if (focused) Color.White else Color.White.copy(alpha = .16f), RoundedCornerShape(26.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (focused || selected || prominent) Color(0xFF19181A) else Ivory,
            maxLines = 1
        )
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
            textStyle = TextStyle(color = Ivory, fontSize = 18.sp),
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

@Composable
fun MediaTile(
    item: MediaItem,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    portrait: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.075f else 1f, label = "tile-scale")
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (portrait) .72f else 1.72f)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF3D4857), DeepRose, Ink)))
                .border(if (focused) 2.dp else 1.dp, if (focused) Ivory else Color.White.copy(alpha = .12f), shape)
        ) {
            if (item.poster.isNotBlank()) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (item.poster.isBlank()) {
                Text(
                    item.title, color = Ivory.copy(alpha = .8f),
                    fontSize = if (portrait) 16.sp else 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(15.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (focused) Box(
                Modifier.fillMaxSize().border(2.dp, Ivory, shape)
            )
        }
        Text(item.title, color = Ivory, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(item.year, item.category, item.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "")
                .filter(String::isNotBlank).joinToString(" · ").ifBlank { "影视作品" },
            color = Muted, fontSize = 12.sp, maxLines = 1
        )
    }
}

@Composable
fun PageHeading(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 34.sp, color = Ivory, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, fontSize = 15.sp, color = Muted)
    }
}

@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = 20.sp)
    }
}
