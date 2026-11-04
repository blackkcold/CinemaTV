package dev.cinematv.data.library

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem

/** Playback callbacks own their evidence; a newer cached playlist must never supply it. */
internal data class CheckpointIdentity(val episodeId: String, val currentIndex: Int?) {
    companion object {
        fun resolve(played: MediaItem, playedIndex: Int, current: MediaItem): CheckpointIdentity {
            val id = MediaDomainMapper.episodeId(played, playedIndex)
            val currentIndex = if (MediaDomainMapper.episodeIdentity(played, playedIndex) == null) null else
                current.episodes.indices.firstOrNull { index ->
                    MediaDomainMapper.episodeIdentity(current, index) != null &&
                        MediaDomainMapper.episodeId(current, index) == id
                }
            return CheckpointIdentity(id, currentIndex)
        }
    }
}
