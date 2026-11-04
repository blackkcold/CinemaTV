package dev.cinematv.data

/** Keep user-owned library records, while removing licensed metadata after its expiry. */
fun MediaItem.usableMetadata(now: Long = System.currentTimeMillis()): MediaItem? {
    if (catalogLicenseExpiresAt == 0L || catalogLicenseExpiresAt > now) {
        // Earlier clients labeled upstream scores with the transport name. Do not
        // resurrect those unverified values from history, favorites or detail.
        return if (RatingPolicy.provider(this) == "libretv") copy(
            rating = "", ratingSource = "", doubanTopRank = null, ratingLicenseExpiresAt = 0
        ) else this
    }
    if (source == null) return null
    return copy(poster = "", backdrop = "", description = "", rating = "", ratingSource = "",
        aliases = emptyList(), actors = emptyList(), doubanTopRank = null, metadataIds = emptyMap(),
        ratingLicenseExpiresAt = 0, catalogProvider = "", catalogAttribution = "", catalogLicenseExpiresAt = 0)
}
