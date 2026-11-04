package dev.cinematv.ui.tv.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.*
import dev.cinematv.ui.tv.design.*

enum class CardVisualState { DEFAULT, FOCUSED, PRESSED, SELECTED, LOADING, ERROR }

@Composable
fun FocusMediaCard(
    item: MediaItem,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
    selected: Boolean = false,
    continueWatching: Boolean = false
) {
    var focused by remember(item.presentationKey) { mutableStateOf(false) }
    var loading by remember(item.poster, item.backdrop) { mutableStateOf(false) }
    var failed by remember(item.poster, item.backdrop) { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val effects = LocalTvEffects.current
    val state = when {
        pressed -> CardVisualState.PRESSED
        focused -> CardVisualState.FOCUSED
        selected -> CardVisualState.SELECTED
        loading -> CardVisualState.LOADING
        failed -> CardVisualState.ERROR
        else -> CardVisualState.DEFAULT
    }
    val scale by animateFloatAsState(when (state) {
        CardVisualState.PRESSED -> .985f
        CardVisualState.FOCUSED -> effects.focusScale
        else -> 1f
    }, tween(effects.focusMillis), label = "card-focus")
    val lift by animateFloatAsState(if (focused && !effects.reducedMotion) -3f else 0f, tween(effects.focusMillis), label = "card-lift")
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.zIndex(if (focused) 2f else 0f)
        .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift.dp.toPx() }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .semantics(mergeDescendants = true) { contentDescription = item.title }
        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (portrait) 2f / 3 else 16f / 9)
            .graphicsLayer { shadowElevation = if (focused) 16.dp.toPx() else 0f; this.shape = shape; clip = false }
            .clip(shape).background(TvColors.Surface)) {
            AmbientArtwork(Modifier.matchParentSize(), item.title.hashCode())
            val landscape = landscapeArt(item)
            if (portrait || landscape != null) {
                val image = if (portrait) item.poster else landscape
                if (!image.isNullOrBlank()) AsyncImage(
                    model = image, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onLoading = { loading = true; failed = false },
                    onSuccess = { loading = false; failed = false }, onError = { loading = false; failed = true })
            } else {
                if (item.poster.isNotBlank()) AsyncImage(
                    model = item.poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.43f),
                    onError = { failed = true })
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    listOf(TvColors.Background.copy(alpha = .25f), TvColors.Background.copy(alpha = .45f), Color.Transparent))))
                Text(item.title, color = TvColors.Text, fontSize = 20.sp, lineHeight = 25.sp,
                    fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(.59f).padding(15.dp))
            }
            if (portrait && (item.poster.isBlank() || failed)) Text(item.title, color = TvColors.Text,
                fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 3,
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
            if (continueWatching && item.positionMs > 0) {
                Text("已观看 ${item.positionMs / 60000} 分钟", fontSize = 11.sp, color = TvColors.Text,
                    modifier = Modifier.align(Alignment.BottomStart).padding(9.dp)
                        .background(TvColors.Background.copy(alpha = .8f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp))
            }
            if (continueWatching && item.watchProgress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .2f))) {
                    Box(Modifier.fillMaxWidth(item.watchProgress!!).fillMaxHeight().background(TvColors.Text))
                }
            }
            Box(Modifier.matchParentSize().border(if (focused) 2.dp else .5.dp,
                if (focused) TvColors.Text else Color.White.copy(alpha = if (selected) .6f else .08f), shape))
        }
        Text(item.title, color = TvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.metadataLine().ifBlank { if (item.isTv) "剧集" else "电影" }, color = TvColors.Secondary,
            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
