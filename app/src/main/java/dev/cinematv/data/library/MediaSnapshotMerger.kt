package dev.cinematv.data.library

import dev.cinematv.data.MediaItem

/** Pure snapshot merge policy; provenance and licensed content travel as one bundle. */
internal object MediaSnapshotMerger {
    fun merge(old: MediaItem, fresh: MediaItem): MediaItem {
        // A new catalog record replaces its metadata bundle atomically. Otherwise old licensed
        // artwork/actors could survive while being relabelled with a new unlicensed provider.
        val hasCatalogUpdate = fresh.catalogProvider.isNotBlank() || fresh.catalogAttribution.isNotBlank() ||
            fresh.catalogLicenseExpiresAt > 0
        val hasRatingUpdate = hasCatalogUpdate || fresh.rating.isNotBlank() || fresh.ratingSource.isNotBlank() ||
            fresh.doubanTopRank != null || fresh.ratingLicenseExpiresAt > 0
        fun text(current: String, previous: String) = if (hasCatalogUpdate) current else current.ifBlank { previous }
        fun values(current: List<String>, previous: List<String>) =
            if (hasCatalogUpdate) current else current.ifEmpty { previous }
        return fresh.copy(
            title = text(fresh.title, old.title).ifBlank { fresh.id },
            poster = text(fresh.poster, old.poster), backdrop = text(fresh.backdrop, old.backdrop),
            year = text(fresh.year, old.year), category = text(fresh.category, old.category),
            description = text(fresh.description, old.description),
            rating = if (hasRatingUpdate) fresh.rating else old.rating,
            ratingSource = if (hasRatingUpdate) fresh.ratingSource else old.ratingSource,
            ratingLicenseExpiresAt = if (hasRatingUpdate) fresh.ratingLicenseExpiresAt else old.ratingLicenseExpiresAt,
            doubanTopRank = if (hasRatingUpdate) fresh.doubanTopRank else old.doubanTopRank,
            catalogProvider = if (hasCatalogUpdate) fresh.catalogProvider else old.catalogProvider,
            catalogAttribution = if (hasCatalogUpdate) fresh.catalogAttribution else old.catalogAttribution,
            catalogLicenseExpiresAt = if (hasCatalogUpdate) fresh.catalogLicenseExpiresAt else old.catalogLicenseExpiresAt,
            episodes = fresh.episodes.ifEmpty { old.episodes }, episodeNames = fresh.episodeNames.ifEmpty { old.episodeNames },
            aliases = values(fresh.aliases, old.aliases), actors = values(fresh.actors, old.actors),
            metadataIds = if (hasCatalogUpdate) fresh.metadataIds else old.metadataIds + fresh.metadataIds,
            seasonNumber = fresh.seasonNumber ?: old.seasonNumber,
            mediaTypeKnown = fresh.mediaTypeKnown || old.mediaTypeKnown,
            isTv = if (fresh.mediaTypeKnown) fresh.isTv else old.isTv,
            sourceCount = maxOf(old.sourceCount, fresh.sourceCount)
        )
    }
}
