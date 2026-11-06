package dev.cinematv.data

data class TvSource(
    val key: String,
    val name: String,
    val url: String,
    val detail: String = "",
    val enabled: Boolean = true
)

data class LiveSource(
    val key: String,
    val name: String,
    val url: String,
    val epg: String = ""
)

data class MediaItem(
    val id: String,
    val title: String,
    val poster: String = "",
    val backdrop: String = "",
    val year: String = "",
    val category: String = "",
    val description: String = "",
    val rating: String = "",
    val source: TvSource? = null,
    val episodes: List<String> = emptyList(),
    val isTv: Boolean = false,
    val positionMs: Long = 0L,
    val lastEpisode: Int = 0,
    val sourceCount: Int = 1,
    val durationMs: Long = 0L,
    val aliases: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val ratingSource: String = "",
    val doubanTopRank: Int? = null,
    /** Explicit IDs supplied by a metadata adapter, never inferred from the title. */
    val metadataIds: Map<String, String> = emptyMap(),
    val episodeNames: List<String> = emptyList(),
    /** Null means the source did not identify a season. Do not invent season one. */
    val seasonNumber: Int? = null,
    /** Epoch milliseconds; 0 means no licensed-metadata expiry was supplied. */
    val ratingLicenseExpiresAt: Long = 0L,
    /** False when a source result did not identify movie versus series. */
    val mediaTypeKnown: Boolean = true,
    /** True only for a score read from a public page without a licence declaration. */
    val ratingPublic: Boolean = false,
    val catalogProvider: String = "",
    val catalogAttribution: String = "",
    /** Epoch milliseconds; expiry covers the entire licensed catalog metadata record. */
    val catalogLicenseExpiresAt: Long = 0L,
    /** Explicit provider episode identifiers, aligned with episodes. Never generated from position. */
    val episodeIds: List<String> = emptyList(),
    /** True only when a successful detail response supplied an authoritative playlist, even empty. */
    val playlistKnown: Boolean = false
)

data class TvChannel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val tvgId: String = ""
)

data class TvProgram(
    val title: String,
    val start: Long,
    val stop: Long
)

data class ServerStatus(
    val passwordRequired: Boolean,
    val verified: Boolean,
    val sources: List<TvSource>,
    val liveSources: List<LiveSource>,
    val version: String,
    /** Non-secret response receipt; a queued status cannot outlive a newer login. */
    val connection: LibreTvConnection? = null,
    val sessionRevision: Long? = null,
    val defaultSubscriptions: List<SourceSubscription> = emptyList()
)

data class SourceProbe(val reachable: Boolean, val latencyMs: Long, val message: String)
