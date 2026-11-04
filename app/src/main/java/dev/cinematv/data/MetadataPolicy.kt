package dev.cinematv.data

/** Keep user-owned library records, while removing licensed metadata after its expiry. */
fun MediaItem.usableMetadata(now: Long = System.currentTimeMillis()): MediaItem? {
    if (catalogLicenseExpiresAt == 0L || catalogLicenseExpiresAt > now) return this
    if (source == null) return null
    return copy(poster = "", backdrop = "", description = "", rating = "", ratingSource = "",
        aliases = emptyList(), actors = emptyList(), doubanTopRank = null, metadataIds = emptyMap(),
        ratingLicenseExpiresAt = 0, catalogProvider = "", catalogAttribution = "", catalogLicenseExpiresAt = 0)
}
