package dev.cinematv.data

import java.text.Normalizer
import java.util.Locale

/**
 * Conservatively identify a title across LibreTV CMS origins.
 * Matching a different movie/episode is worse than showing no result.
 */
object MediaIdentity {
    /**
     * Only these namespaces are authoritative enough to identity-match across sources.
     * A CMS/provider namespace can reuse the same numeric ID for unrelated works.
     *
     * This list governs the SEARCH display identity only. Persisted identity
     * ([MediaDomainMapper.workId] → `seasonId`, library records, manual associations) keeps its
     * own baseline lookup and is intentionally not unified with this normalization.
     */
    internal val TRUSTED_ID_NAMESPACES = listOf("tmdb", "imdb", "douban")

    fun normalizedTitle(title: String): String {
        val nfkc = Normalizer.normalize(title, Normalizer.Form.NFKC)
        return nfkc.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{P}\\p{Z}\\s]+"), "")
    }

    fun isSameWork(titleA: String, yearA: String, titleB: String, yearB: String): Boolean {
        val a = normalizedTitle(titleA)
        val b = normalizedTitle(titleB)
        if (a.isBlank() || b.isBlank() || a != b) return false
        val ya = yearA.trim()
        val yb = yearB.trim()
        return ya.isBlank() || yb.isBlank() || ya == yb
    }

    /**
     * One normalized trusted external ID, never guessed from a title or a source URL.
     * Returns (namespace, id) so a key can be namespaced without string concatenation ambiguity.
     * Namespace keys are compared case-insensitively, matching the conflict check.
     *
     * Search-display evidence only. Persisted [MediaDomainMapper.workId] must not call this: its
     * baseline semantics are an exact-key lookup, and changing it would move stored work ids.
     */
    internal fun trustedId(item: MediaItem): Pair<String, String>? {
        val byNamespace = item.metadataIds.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .associate { it.key.trim().lowercase(Locale.ROOT) to it.value.trim() }
        for (namespace in TRUSTED_ID_NAMESPACES) {
            byNamespace[namespace]?.let { return namespace to it }
        }
        return null
    }
}
