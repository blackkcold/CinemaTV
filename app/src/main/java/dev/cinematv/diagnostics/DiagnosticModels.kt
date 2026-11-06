package dev.cinematv.diagnostics

import java.util.Locale

/** Off by default. Basic records state changes and request outcomes; verbose adds flow. */
enum class DiagnosticLevel { OFF, BASIC, VERBOSE }

/**
 * A single structured line. Fields are an allowlist chosen by call sites; nothing here
 * accepts a cookie, token, account identifier, title or raw page.
 */
data class DiagnosticRecord(
    val timestamp: Long,
    val level: DiagnosticLevel,
    val event: String,
    val trace: String?,
    val fields: Map<String, String>
) {
    fun render(): String = buildString {
        append('{')
        append("\"ts\":").append(timestamp)
        append(",\"lvl\":\"").append(if (level == DiagnosticLevel.VERBOSE) "V" else "B").append('"')
        append(",\"ev\":\"").append(escape(DiagnosticRedaction.value(event))).append('"')
        if (!trace.isNullOrBlank()) append(",\"trace\":\"").append(escape(DiagnosticRedaction.value(trace))).append('"')
        fields.forEach { (key, value) ->
            // Unknown or reserved keys are dropped, never written. A call site cannot enlarge
            // the schema at runtime, so an accidental cookie/title/URL field stays on device.
            if (key !in DiagnosticFields.allowed) return@forEach
            append(",\"").append(key).append("\":\"").append(escape(DiagnosticRedaction.value(value))).append('"')
        }
        append('}')
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append(' ') else append(character)
            }
        }
    }
}

/**
 * Closed set of field keys. Every value still passes [DiagnosticRedaction], but the key itself
 * is the second gate: a typo, a new call site or a future refactor cannot add a field that was
 * never privacy-reviewed. Reserved JSON names (`ts`, `lvl`, `ev`, `trace`) are absent on purpose,
 * so a field can never duplicate a structural member.
 */
object DiagnosticFields {
    val allowed: Set<String> = setOf(
        // Envelope and device context.
        "version", "channel", "sdk", "model", "level",
        // Navigation.
        "from", "to",
        // Request outcomes.
        "endpoint", "status", "bytes", "type", "ms", "stop", "retryAt",
        // Public gate, cache and chart.
        "offset", "items", "result", "waitMs", "budgetLeft", "strikes",
        // Account lifecycle and synchronization.
        "phase", "snapshot", "resumable", "error", "identity", "retainedSnapshot",
        "continue", "resume", "doneShelves", "shelf", "page", "pageItems", "staged",
        "total", "pages", "complete", "subjects", "checked", "rated"
    )
}

/** Redaction is a safety net; call sites must still avoid passing secrets at all. */
object DiagnosticRedaction {
    private val secret = Regex("(?i)[\"']?(cookie|authorization|token|password)[\"']?\\s*[=:]\\s*[\"']?([^&;\\s,\"']+)")
    private val cookie = Regex("(?i)\\b(dbcl2|bid)\\s*=\\s*[^&;\\s]+")
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*")

    fun value(raw: String): String {
        val masked = raw
            .replace(secret, "$1=[已隐藏]")
            .replace(cookie, "$1=[已隐藏]")
            .replace(bearer, "Bearer [已隐藏]")
        return if (masked.length <= 160) masked else masked.take(157) + "..."
    }

    /** Logical endpoint keys only; full URLs and query strings never reach the log. */
    fun endpoint(path: String): String {
        val clean = path.substringBefore('?').substringBefore('#')
        return when {
            clean == "/top250" -> "top250"
            clean == "/j/new_search_subjects" -> "browse"
            clean == "/j/search_subjects" -> "hot"
            clean.startsWith("/doulist/") -> "doulist"
            clean == "/mine/" -> "account.mine"
            clean.startsWith("/people/") && clean.endsWith("/collect") -> "account.collect"
            clean.startsWith("/people/") && clean.endsWith("/wish") -> "account.wish"
            clean.startsWith("/people/") && clean.endsWith("/do") -> "account.do"
            clean.startsWith("/subject/") -> "subject"
            else -> "other"
        }
    }

    fun levelName(level: DiagnosticLevel): String =
        level.name.lowercase(Locale.ROOT)
}
