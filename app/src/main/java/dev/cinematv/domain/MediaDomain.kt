package dev.cinematv.domain

/** Provider-neutral catalog model. Network/JSON and Android objects stay in data/. */
enum class WorkKind { MOVIE, SERIES, UNKNOWN }

data class Work(
    val id: String,
    val title: String,
    val kind: WorkKind,
    val year: String = "",
    val aliases: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val description: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val rating: String = "",
    val ratingSource: String = "",
    val metadataIds: Map<String, String> = emptyMap(),
    val category: String = "",
    val doubanTopRank: Int? = null,
    val ratingLicenseExpiresAt: Long = 0L,
    val mediaTypeKnown: Boolean = true,
    val catalogProvider: String = "",
    val catalogAttribution: String = "",
    val catalogLicenseExpiresAt: Long = 0L
)

data class Season(val id: String, val workId: String, val number: Int?, val title: String)

data class Episode(
    val id: String,
    val seasonId: String,
    val sourceMappingId: String,
    val index: Int,
    val title: String,
    val playbackUrl: String
)

/** One source's listing of a work. Same CMS ID at another origin is a different listing. */
data class SourceMapping(
    val id: String,
    val workId: String,
    val sourceId: String,
    val sourceName: String,
    val sourceUrl: String,
    val providerItemId: String
)

data class MediaGraph(
    val work: Work,
    val season: Season,
    val source: SourceMapping,
    val episodes: List<Episode>
)

/** A duration is known only after a player measures it. Never borrow another episode's duration. */
data class EpisodeProgress(
    val episodeId: String,
    val sourceMappingId: String,
    val episodeIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val fraction: Float?
        get() = durationMs.takeIf { it > 0 }?.let {
            (positionMs.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
        }
    val completed: Boolean get() = fraction?.let { it >= 0.95f } ?: false

    companion object {
        fun record(
            episodeId: String,
            sourceMappingId: String,
            episodeIndex: Int,
            positionMs: Long,
            measuredDurationMs: Long,
            updatedAt: Long,
            previous: EpisodeProgress? = null
        ): EpisodeProgress {
            val duration = measuredDurationMs.takeIf { it > 0 }
                ?: previous?.takeIf { it.episodeId == episodeId && it.sourceMappingId == sourceMappingId }
                    ?.durationMs?.coerceAtLeast(0) ?: 0L
            val position = positionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
            return EpisodeProgress(episodeId, sourceMappingId, episodeIndex.coerceAtLeast(0),
                position, duration, updatedAt.coerceAtLeast(0))
        }
    }
}
