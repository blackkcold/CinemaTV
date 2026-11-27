package dev.cinematv.ui.tv.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.*
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.design.LocalTvEffects

/** Shared with BackdropLayer so prefetch keys and consumer keys describe one decode. */
internal const val BACKDROP_DECODE_WIDTH = 1600
internal const val BACKDROP_DECODE_HEIGHT = 900

/** Adjacent stills only, cancellable and disabled by the low-memory policy. */
@Composable
fun ArtworkPrefetch(items: List<MediaItem>, focused: MediaItem?) {
    val context = LocalContext.current
    val effects = LocalTvEffects.current
    val capacity = remember(context) { deviceProfile(context).artworkPrefetchCount }
    val urls = remember(items, focused?.presentationKey, effects.glass) {
        if (effects.glass == GlassQuality.BASIC) emptyList() else {
            val index = items.indexOfFirst { it.presentationKey == focused?.presentationKey }
            listOf(index - 1, index + 1).mapNotNull { items.getOrNull(it)?.let(::landscapeArt) }.distinct().take(capacity)
        }
    }
    DisposableEffect(urls) {
        // Same size as the consumer's request. Coil's memory cache key includes the size, so
        // prefetching at a different size warms the disk cache only and the frame that needs
        // the image still pays the decode.
        val requests = urls.map {
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(it)
                    .size(BACKDROP_DECODE_WIDTH, BACKDROP_DECODE_HEIGHT).build(),
            )
        }
        onDispose { requests.forEach { it.dispose() } }
    }
}
