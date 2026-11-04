package dev.cinematv.data

/** A different episode must not inherit a duration that has not been measured. */
fun MediaItem.withPlaybackProgress(position: Long, episode: Int, measuredDuration: Long): MediaItem {
    val index = episode.coerceAtLeast(0)
    return copy(positionMs = position.coerceAtLeast(0), lastEpisode = index,
        durationMs = measuredDuration.takeIf { it > 0 }
            ?: if (lastEpisode == index) durationMs.coerceAtLeast(0) else 0L)
}
