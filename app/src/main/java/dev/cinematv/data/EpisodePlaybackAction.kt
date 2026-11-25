package dev.cinematv.data

import dev.cinematv.domain.EpisodeProgress

enum class EpisodePlaybackAction { RESUME, RESTART }

object EpisodePlaybackPolicy {
    fun startPosition(progress: EpisodeProgress?, action: EpisodePlaybackAction = EpisodePlaybackAction.RESUME): Long {
        if (action == EpisodePlaybackAction.RESTART || progress == null || progress.completed) return 0L
        return progress.positionMs.coerceAtLeast(0L).let { position ->
            if (progress.durationMs > 0) position.coerceAtMost(progress.durationMs) else position
        }
    }
}
