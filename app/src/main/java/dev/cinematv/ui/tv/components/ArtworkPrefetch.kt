package dev.cinematv.ui.tv.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.*
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.design.LocalTvEffects

/** Adjacent stills only, cancellable and disabled by the low-memory policy. */
@Composable
fun ArtworkPrefetch(items: List<MediaItem>, focused: MediaItem?) {
    val context = LocalContext.current
    val effects = LocalTvEffects.current
    val urls = remember(items, focused?.presentationKey, effects.glass) {
        if (effects.glass == GlassQuality.BASIC) emptyList() else {
            val index = items.indexOfFirst { it.presentationKey == focused?.presentationKey }
            listOf(index - 1, index + 1).mapNotNull { items.getOrNull(it)?.let(::landscapeArt) }.distinct().take(2)
        }
    }
    DisposableEffect(urls) {
        val requests = urls.map { context.imageLoader.enqueue(ImageRequest.Builder(context).data(it).size(960, 540).build()) }
        onDispose { requests.forEach { it.dispose() } }
    }
}
