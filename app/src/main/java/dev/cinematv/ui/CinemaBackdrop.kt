package dev.cinematv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.cinematv.data.MediaItem

/**
 * Full viewport 16:9 backdrop. Crossfade keeps the previous scene visible until
 * the next item begins rendering. Portrait cover art is deliberately blurred so it
 * cannot look like an artificially stretched landscape still.
 *
 * Video surfaces must never be sampled by this composable.
 */
@Composable
fun CinemaBackdrop(item: MediaItem?) {
    val landscape = item?.backdrop.orEmpty()
    val art = landscape.ifBlank { item?.poster.orEmpty() }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF413037), Color(0xFF202633), Ink))
        )
    ) {
        Crossfade(targetState = art, animationSpec = tween(durationMillis = 650), label = "cinema-backdrop") { image ->
            if (image.isNotBlank()) AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(
                    if (landscape.isBlank()) Modifier.blur(36.dp) else Modifier
                )
            )
        }
        Box(Modifier.matchParentSize().background(Color(0x42070910)))
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            listOf(Ink.copy(alpha = .96f), Ink.copy(alpha = .56f), Ink.copy(alpha = .11f))
        )))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0.0f to Color(0x66090A10),
            0.4f to Color.Transparent,
            0.72f to Ink.copy(alpha = .64f),
            1.0f to Ink
        )))
    }
}
