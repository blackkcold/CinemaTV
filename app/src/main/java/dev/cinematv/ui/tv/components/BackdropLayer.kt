package dev.cinematv.ui.tv.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import coil3.BitmapImage
import android.graphics.Bitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.landscapeArt
import dev.cinematv.ui.tv.design.*

val LocalBackdropArt = compositionLocalOf<String?> { null }

/** Original atmospheric fallback. Never claims to be a still from a film. */
@Composable
fun AmbientArtwork(modifier: Modifier = Modifier, seed: Int = 0) {
    val tint = remember(seed) {
        listOf(Color(0xFF253B50), Color(0xFF45323C), Color(0xFF253F42), Color(0xFF353249))[seed.and(Int.MAX_VALUE) % 4]
    }
    Canvas(modifier.background(TvColors.Background)) {
        drawRect(Brush.radialGradient(listOf(tint, Color.Transparent), center = Offset(size.width * .76f, size.height * .36f), radius = size.width * .65f))
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x803D5361), Color.Transparent), start = Offset(0f, size.height * .64f), end = Offset(size.width, size.height * .16f)))
        for (i in 0..6) {
            val y = size.height * (.30f + i * .047f)
            drawLine(Color.White.copy(alpha = .012f + i * .002f), Offset(size.width * .30f, y), Offset(size.width, y - size.height * .12f), strokeWidth = (i + 1) * 2f)
        }
    }
}

/** Decode before swapping; a failed or portrait-sized image resolves to fallback. */
@Composable
fun BackdropLayer(item: MediaItem?, onDecodedFrame: (GlassBackdropFrame?) -> Unit = {}, onDecodedArt: (String?) -> Unit = {}) {
    val context = LocalContext.current
    val effects = LocalTvEffects.current
    val decodeWidth = if (effects.glass == GlassQuality.BASIC) 960 else 1600
    val decodeHeight = if (effects.glass == GlassQuality.BASIC) 540 else 900
    val requested = landscapeArt(item)
    val latest by rememberUpdatedState(requested)
    val notify by rememberUpdatedState(onDecodedArt)
    val notifyFrame by rememberUpdatedState(onDecodedFrame)
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var decoded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(requested) {
        if (requested == null) { decoded = null; bitmap = null; notify(null) }
    }
    LaunchedEffect(bitmap, decoded, requested, origin, viewport, effects.glass) {
        notifyFrame(null) // Never refract a different still during the scene crossfade.
        if (decoded == requested && bitmap != null && effects.glass != GlassQuality.BASIC) {
            kotlinx.coroutines.delay(effects.sceneMillis.toLong())
            if (viewport.width > 0 && viewport.height > 0)
                notifyFrame(GlassBackdropFrame(bitmap!!, origin, viewport))
        }
    }
    DisposableEffect(Unit) { onDispose { notifyFrame(null) } }
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        origin = it.positionInRoot(); viewport = it.size.toSize()
    }) {
        AmbientArtwork(Modifier.matchParentSize(), item?.title?.hashCode() ?: 0)
        if (requested != null) {
            val request = remember(requested, decodeWidth) {
                ImageRequest.Builder(context).data(requested)
                    .size(decodeWidth, decodeHeight).build()
            }
            AsyncImage(model = request, contentDescription = null,
                modifier = Modifier.size(1.dp).alpha(0f),
                onSuccess = { state ->
                    if (latest == requested) {
                        val image = state.result.image
                        val ratio = image.width.toFloat() / image.height.coerceAtLeast(1)
                        decoded = requested.takeIf { image.width >= 640 && ratio in 1.3f..2.5f }
                        bitmap = (image as? BitmapImage)?.bitmap.takeIf { decoded != null }
                        notify(decoded)
                    }
                },
                onError = { if (latest == requested) { decoded = null; bitmap = null; notify(null) } })
        }
        Crossfade(targetState = decoded, animationSpec = tween(effects.sceneMillis), label = "decoded-backdrop") { art ->
            if (art != null) AsyncImage(
                model = ImageRequest.Builder(context).data(art).size(decodeWidth, decodeHeight).build(),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to TvColors.Background.copy(alpha = .94f), .46f to TvColors.Background.copy(alpha = .42f), 1f to Color.Transparent
        )))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to TvColors.Background.copy(alpha = .46f), .30f to Color.Transparent,
            .63f to TvColors.Background.copy(alpha = .32f), 1f to TvColors.Background
        )))
    }
}
