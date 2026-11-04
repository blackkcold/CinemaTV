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
    val durationMs: Long = 0L
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
    val version: String
)

data class SourceProbe(val reachable: Boolean, val latencyMs: Long, val message: String)
