package dev.cinematv.data

/** EPG ids identify schedules; different stream variants may legitimately share that id. */
object ChannelIdentity {
    fun key(source: LiveSource?, channel: TvChannel): String =
        listOf(source?.url.orEmpty(), source?.key.orEmpty(), channel.id, channel.url)
            .joinToString(separator = "") { "${it.length}:$it" }
}
