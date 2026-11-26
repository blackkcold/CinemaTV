package dev.cinematv.data

import dev.cinematv.domain.Episode
import dev.cinematv.domain.MediaGraph
import dev.cinematv.domain.Season
import dev.cinematv.domain.SourceMapping
import dev.cinematv.domain.Work
import dev.cinematv.domain.WorkKind
import java.security.MessageDigest

/**
 * Work IDs deliberately do not use title similarity, year, artwork or stream URLs.
 * Signed playback URLs can rotate. Two unrelated CMS sources can reuse the same vod ID.
 * Only an explicit trusted metadata ID can associate sources with a common work.
 */
object MediaDomainMapper {
    private val HEX = "0123456789abcdef".toCharArray()

    private fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    private fun id(prefix: String, vararg parts: String): String {
        // Length prefixes avoid collisions when a provider ID contains delimiters.
        val value = parts.joinToString("") { "${it.length}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return prefix + ":" + hex(digest)
    }

    fun sourceId(item: MediaItem): String = id("source", item.source?.url.orEmpty())
    fun entryId(item: MediaItem): String {
        item.cachedEntryId?.let { return it }
        val computed = if (item.source == null) {
            // Metadata providers can reuse numeric IDs across movie/TV namespaces. A source-less
            // unknown result must not accidentally claim either namespace before type resolution.
            id("entry", "metadata", mediaKind(item), item.id)
        } else {
            // Preserve provider listing IDs across unknown -> movie/series enrichment.
            id("entry", item.source.url, item.id)
        }
        item.cachedEntryId = computed
        return computed
    }

    private fun mediaKind(item: MediaItem): String =
        if (!item.mediaTypeKnown) "unknown" else if (item.isTv) "tv" else "movie"

    fun workId(item: MediaItem): String {
        val kind = mediaKind(item)
        // Unknown namespaces are not allowed to silently join works.
        //
        // This is PERSISTED identity: `workId` feeds `seasonId`, library records and manual work
        // associations. It is deliberately byte-identical to the released baseline, so it uses an
        // exact map lookup — no namespace case folding, no key trimming. `MediaIdentity.trustedId`
        // normalizes namespaces for the search display path only; adopting that here would change
        // the stored workId of any record with a non-literal namespace key and orphan its seasons
        // and associations. Do not "unify" the two.
        for (namespace in listOf("tmdb", "imdb", "douban")) {
            val external = item.metadataIds[namespace]?.trim().orEmpty()
            if (external.isNotBlank()) return id("work", namespace, kind, external)
        }
        return id("work", "source", entryId(item))
    }

    /**
     * The stable selection identity of one provider listing.
     *
     * Built from the listing identity only — the source URL (hashed) plus the provider's listing
     * ID, both already inside [entryId] — because every other field is filled in later:
     * [dev.cinematv.data.LibreTvRepository.fetchDetail] populates `episodeIds` and re-derives
     * `seasonNumber` from the detail response. A key that hashed those would differ for the same
     * listing between the search snapshot and the loaded detail, which duplicates the source row,
     * lets the current source be offered against itself, and breaks "switch back to the source I
     * just left".
     *
     * Two listings of one source (different provider IDs) and identically-numbered listings of
     * different sources still hash differently. Season and episode identity remain in [seasonId]
     * and [episodeId], which own playback and progress; this key never participates in either.
     *
     * Limitation, deliberate: two seasons exposed under a single provider listing ID are one
     * selectable source here. Season choice stays the work/season navigation's job, and enrichment
     * must never fabricate a second version of one listing.
     */
    fun versionId(item: MediaItem): String = id("version", entryId(item))

    /** A short, delimiter-free digest for display keys; never reversible to the input. */
    internal fun shortDigest(value: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))).take(16)

    fun seasonId(item: MediaItem): String = id("season", workId(item), item.seasonNumber?.toString() ?: "unknown")
    /** Only unique, provider-supplied evidence is authoritative. Never normalize signed URLs. */
    fun episodeIdentity(item: MediaItem, index: Int): String? {
        if (index !in item.episodes.indices) return null
        fun unique(values: List<String>): String? = values.getOrNull(index)?.trim()
            ?.takeIf { value -> value.isNotBlank() && values.count { it.trim() == value } == 1 }
        unique(item.episodeIds)?.let { return "provider-id:$it" }
        unique(item.episodeNames)?.takeUnless(::isGeneratedEpisodeLabel)?.let { return "provider-name:$it" }
        // Exact URL equality is useful evidence; removing arbitrary query parameters is not.
        val url = item.episodes[index]
        return url.takeIf { it.isNotBlank() && item.episodes.count { candidate -> candidate == it } == 1 }
            ?.let { "exact-url:$it" }
    }

    private fun isGeneratedEpisodeLabel(value: String): Boolean =
        value == "正片" || Regex("(?i)(第\\s*\\d+\\s*集|分段\\s*\\d+|episode\\s*\\d+|ep\\s*\\d+|\\d+)").matches(value)

    fun episodeId(item: MediaItem, index: Int): String = episodeIdentity(item, index)?.let {
        id("episode-v2", entryId(item), item.seasonNumber?.toString() ?: "unknown", it)
    } ?: id("episode-unresolved", entryId(item), item.seasonNumber?.toString() ?: "unknown",
        item.episodes.joinToString("") { "${it.length}:$it" }, index.coerceAtLeast(0).toString())

    fun map(item: MediaItem): MediaGraph {
        val work = Work(workId(item), item.title,
            if (!item.mediaTypeKnown) WorkKind.UNKNOWN else if (item.isTv) WorkKind.SERIES else WorkKind.MOVIE,
            item.year, item.aliases, item.actors, item.description, item.poster, item.backdrop,
            item.rating, item.ratingSource, item.metadataIds, item.category, item.doubanTopRank, item.ratingLicenseExpiresAt,
            item.mediaTypeKnown, item.catalogProvider, item.catalogAttribution, item.catalogLicenseExpiresAt)
        val season = Season(seasonId(item), work.id, item.seasonNumber,
            item.seasonNumber?.let { "第${it}季" } ?: if (item.isTv) "未分季" else "正片")
        val source = SourceMapping(entryId(item), work.id, sourceId(item), item.source?.name.orEmpty(),
            item.source?.url.orEmpty(), item.id)
        val episodes = item.episodes.mapIndexed { index, url ->
            Episode(episodeId(item, index), season.id, source.id, index,
                item.episodeNames.getOrNull(index)?.takeIf(String::isNotBlank)
                    ?: if (item.isTv) "第${index + 1}集" else if (item.episodes.size == 1) "正片" else "分段 ${index + 1}", url)
        }
        return MediaGraph(work, season, source, episodes)
    }
}
