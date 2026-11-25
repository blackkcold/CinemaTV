package dev.cinematv.data

/** LibreTV caches by source URL, so a cached response may contain another client's local key. */
internal object SourceResponseIdentity {
    private fun supplied(value: String?): String? = value?.takeUnless { it.isBlank() || it == "null" }

    fun matches(requested: TvSource, reportedKey: String?, reportedUrl: String?): Boolean {
        val url = supplied(reportedUrl)
        val key = supplied(reportedKey)
        // An explicit different origin must never fall back to a matching local key.
        return if (url != null) url == requested.url else key == null || key == requested.key
    }

    fun resolve(active: List<TvSource>, reportedKey: String?, reportedUrl: String?): TvSource? {
        val url = supplied(reportedUrl)
        val key = supplied(reportedKey)
        return if (url != null) active.firstOrNull { it.url == url }
        else key?.let { value -> active.firstOrNull { it.key == value } }
    }
}
