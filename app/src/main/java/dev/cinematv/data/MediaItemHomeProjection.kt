package dev.cinematv.data

/**
 * Douban is a separate chain. A public Douban score, rank or catalogue attribution may appear
 * where the user asked for Douban; it must never decorate a home rail or a continue-watching
 * card, because that would present an unlicensed third-party score as part of the home product.
 *
 * The item keeps its identity, source and playback state; only the badge-bearing fields go.
 */
fun MediaItem.withoutDoubanHomeBadges(): MediaItem {
    // "豆瓣" and "douban" are the same provider; reuse the one normaliser instead of a
    // second string table that would drift.
    val doubanRating = RatingPolicy.normalizeProvider(ratingSource) == "douban"
    val doubanCatalog = RatingPolicy.normalizeProvider(catalogProvider) == "douban-public"
    if (!doubanRating && !doubanCatalog && doubanTopRank == null) return this
    return copy(
        rating = if (doubanRating) "" else rating,
        ratingSource = if (doubanRating) "" else ratingSource,
        ratingPublic = if (doubanRating) false else ratingPublic,
        ratingLicenseExpiresAt = if (doubanRating) 0L else ratingLicenseExpiresAt,
        doubanTopRank = null,
        catalogProvider = if (doubanCatalog) "" else catalogProvider,
        catalogAttribution = if (doubanCatalog) "" else catalogAttribution
    )
}
