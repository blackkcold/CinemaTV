package dev.cinematv.data

/**
 * Provider descriptions are HTML fragments, not display text. Strip only the markup
 * and common entities so real LibreTV results never show raw `<p style=...>` on TV.
 */
internal fun plainProviderText(raw: String): String {
    if (raw.isBlank()) return ""
    var text = raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
    text = text.replace(Regex("(?is)<[^>]*>"), "")
    text = text.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
    return text.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
