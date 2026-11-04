package dev.cinematv.data

import java.text.Normalizer
import java.util.Locale

/**
 * Conservatively identify a title across LibreTV CMS origins.
 * Matching a different movie/episode is worse than showing no result.
 */
object MediaIdentity {
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
}
