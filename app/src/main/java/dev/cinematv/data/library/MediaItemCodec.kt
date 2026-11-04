package dev.cinematv.data.library

import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import org.json.JSONArray
import org.json.JSONObject

/** One codec for durable snapshots and the v0.x SharedPreferences migration. */
object MediaItemCodec {
    fun encode(item: MediaItem): String = json(item).toString()

    fun json(item: MediaItem): JSONObject = JSONObject()
        .put("id", item.id).put("title", item.title).put("poster", item.poster)
        .put("backdrop", item.backdrop).put("year", item.year).put("category", item.category)
        .put("description", item.description).put("rating", item.rating)
        .put("source", item.source?.let { source -> JSONObject()
            .put("key", source.key).put("name", source.name).put("url", source.url)
            .put("detail", source.detail).put("enabled", source.enabled) })
        .put("episodes", JSONArray(item.episodes)).put("isTv", item.isTv)
        .put("positionMs", item.positionMs).put("lastEpisode", item.lastEpisode)
        .put("sourceCount", item.sourceCount).put("durationMs", item.durationMs)
        .put("aliases", JSONArray(item.aliases)).put("actors", JSONArray(item.actors))
        .put("ratingSource", item.ratingSource).put("doubanTopRank", item.doubanTopRank)
        .put("metadataIds", JSONObject(item.metadataIds))
        .put("episodeNames", JSONArray(item.episodeNames)).put("seasonNumber", item.seasonNumber)
        .put("ratingLicenseExpiresAt", item.ratingLicenseExpiresAt)
        .put("mediaTypeKnown", item.mediaTypeKnown).put("catalogProvider", item.catalogProvider)
        .put("catalogAttribution", item.catalogAttribution).put("catalogLicenseExpiresAt", item.catalogLicenseExpiresAt)

    fun decode(value: String): MediaItem = decode(JSONObject(value))

    fun decode(j: JSONObject): MediaItem {
        val source = j.optJSONObject("source")?.let { s ->
            val url = s.optString("url")
            if (url.isBlank()) null else TvSource(s.optString("key", url), s.optString("name"),
                url, s.optString("detail"), s.optBoolean("enabled", true))
        }
        val ids = j.optJSONObject("metadataIds")?.let { ids ->
            ids.keys().asSequence().mapNotNull { key ->
                ids.optString(key).takeIf(String::isNotBlank)?.let { key to it }
            }.toMap()
        }.orEmpty()
        return MediaItem(
            id = j.optString("id"), title = j.optString("title"),
            poster = j.optString("poster"), backdrop = j.optString("backdrop"),
            year = j.optString("year"), category = j.optString("category"),
            description = j.optString("description"), rating = j.optString("rating"), source = source,
            episodes = strings(j.optJSONArray("episodes")), isTv = j.optBoolean("isTv"),
            positionMs = j.optLong("positionMs").coerceAtLeast(0), lastEpisode = j.optInt("lastEpisode").coerceAtLeast(0),
            sourceCount = j.optInt("sourceCount", 1).coerceAtLeast(1), durationMs = j.optLong("durationMs").coerceAtLeast(0),
            aliases = strings(j.optJSONArray("aliases")), actors = strings(j.optJSONArray("actors")),
            ratingSource = j.optString("ratingSource"),
            doubanTopRank = if (j.isNull("doubanTopRank")) null else j.optInt("doubanTopRank").takeIf { it > 0 },
            metadataIds = ids, episodeNames = strings(j.optJSONArray("episodeNames")),
            seasonNumber = if (j.isNull("seasonNumber")) null else j.optInt("seasonNumber").takeIf { it >= 0 },
            ratingLicenseExpiresAt = j.optLong("ratingLicenseExpiresAt").coerceAtLeast(0),
            mediaTypeKnown = j.optBoolean("mediaTypeKnown", true),
            catalogProvider = j.optString("catalogProvider"), catalogAttribution = j.optString("catalogAttribution"),
            catalogLicenseExpiresAt = j.optLong("catalogLicenseExpiresAt").coerceAtLeast(0)
        )
    }

    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else
        (0 until array.length()).map { array.optString(it) }
}
