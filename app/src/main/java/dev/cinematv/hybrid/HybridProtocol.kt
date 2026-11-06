package dev.cinematv.hybrid

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

class HybridRequestFailure(val code: String, val requestId: String? = null) : IllegalArgumentException(code)

data class HybridRequest(val id: String, val method: String, val params: JSONObject)

/** No provider schema, URL dispatcher, file operation or credential API crosses this boundary. */
object HybridProtocol {
    const val MAX_REQUEST_BYTES = 32 * 1024
    const val MAX_REPLY_BYTES = 512 * 1024
    const val MAX_IN_FLIGHT = 8
    const val PAGE_SIZE = 48
    const val EPISODE_PAGE_SIZE = 40

    private val arguments = mapOf(
        "app.ready" to setOf("protocol"), "app.fatal" to emptySet(),
        "navigation.go" to setOf("route"), "navigation.back" to setOf("token"),
        "navigation.bookmark" to setOf("bookmark"),
        "media.open" to setOf("key"), "media.focus" to setOf("key"),
        "catalog.page" to setOf("page"), "catalog.more" to emptySet(),
        "catalog.refresh" to emptySet(), "catalog.filter" to setOf("category", "year", "rated"),
        "search.input" to emptySet(), "search.submit" to setOf("query"),
        "search.filter" to setOf("type", "year", "minRating", "ratingSource", "topOnly"), "search.year" to emptySet(),
        "detail.sources" to emptySet(), "detail.episodes" to setOf("page"),
        "playback.open" to setOf("key", "episodeId", "action"),
        "library.favorite" to setOf("key", "favorite"), "library.status" to setOf("key", "status"),
        "library.filter" to setOf("filter"), "library.clearHistory" to emptySet(),
        "live.source" to setOf("id"), "live.group" to setOf("id"), "live.focus" to setOf("id"), "live.play" to setOf("id"),
        "settings.motion" to setOf("reduced"), "settings.quality" to setOf("quality"),
        "native.open" to setOf("target", "section"), "message.dismiss" to emptySet(),
    )

    fun trustedOrigin(origin: String, mainFrame: Boolean): Boolean {
        if (!mainFrame) return false
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")
    }

    fun decode(raw: String): HybridRequest {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) throw HybridRequestFailure("REQUEST_TOO_LARGE")
        var depth = 0
        var quoted = false
        var escaped = false
        raw.forEach { character ->
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 12) throw HybridRequestFailure("INVALID_MESSAGE") }
                '}', ']' -> depth--
            }
        }
        val root = try { JSONObject(raw) } catch (_: Exception) { throw HybridRequestFailure("INVALID_MESSAGE") }
        if (root.keys().asSequence().any { it !in setOf("v", "id", "method", "params") }) throw HybridRequestFailure("INVALID_MESSAGE")
        if ((root.opt("v") as? Number)?.toDouble() != 1.0) throw HybridRequestFailure("PROTOCOL_UNSUPPORTED")
        val id = string(root, "id", 96)
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,96}"))) throw HybridRequestFailure("INVALID_MESSAGE")
        val method = string(root, "method", 64)
        val allowed = arguments[method] ?: throw HybridRequestFailure("METHOD_NOT_ALLOWED", id)
        val params = root.optJSONObject("params") ?: throw HybridRequestFailure("BAD_PARAMS", id)
        if (params.keys().asSequence().any { it !in allowed }) throw HybridRequestFailure("BAD_PARAMS", id)
        return HybridRequest(id, method, params)
    }

    fun string(params: JSONObject, key: String, max: Int, allowBlank: Boolean = false): String {
        val value = params.opt(key) as? String ?: throw HybridRequestFailure("BAD_PARAMS")
        if (value.length > max || (!allowBlank && value.isBlank()) || value.any { it == '\u0000' }) throw HybridRequestFailure("BAD_PARAMS")
        return value
    }

    fun boolean(params: JSONObject, key: String): Boolean =
        params.opt(key) as? Boolean ?: throw HybridRequestFailure("BAD_PARAMS")

    fun integer(params: JSONObject, key: String, max: Int): Int {
        val number = (params.opt(key) as? Number)?.toDouble() ?: throw HybridRequestFailure("BAD_PARAMS")
        if (!number.isFinite() || number < 0 || number > max || number % 1.0 != 0.0) throw HybridRequestFailure("BAD_PARAMS")
        return number.toInt()
    }

    /** Bookmark keys are opaque UI identities, and offsets are bounded CSS pixels. */
    fun bookmark(value: JSONObject, expectedRoute: String): JSONObject {
        if (value.keys().asSequence().any { it !in setOf("route", "focus", "scrolls") }) throw HybridRequestFailure("BAD_PARAMS")
        if (string(value, "route", 80) != expectedRoute) throw HybridRequestFailure("CONFLICT")
        val focus = string(value, "focus", 256, allowBlank = true)
        val offsets = value.optJSONObject("scrolls") ?: throw HybridRequestFailure("BAD_PARAMS")
        if (offsets.length() > 24) throw HybridRequestFailure("BAD_PARAMS")
        val cleaned = JSONObject()
        offsets.keys().forEach { key ->
            if (!key.matches(Regex("[A-Za-z0-9_:.-]{1,96}")) || key in setOf("__proto__", "constructor", "prototype")) throw HybridRequestFailure("BAD_PARAMS")
            val pair = offsets.optJSONArray(key) ?: throw HybridRequestFailure("BAD_PARAMS")
            if (pair.length() != 2) throw HybridRequestFailure("BAD_PARAMS")
            val safe = JSONArray()
            for (index in 0..1) {
                val offset = (pair.opt(index) as? Number)?.toDouble() ?: throw HybridRequestFailure("BAD_PARAMS")
                if (!offset.isFinite() || offset !in 0.0..2_000_000.0) throw HybridRequestFailure("BAD_PARAMS")
                safe.put(offset.toInt())
            }
            cleaned.put(key, safe)
        }
        return JSONObject().put("route", expectedRoute).put("focus", focus).put("scrolls", cleaned)
    }
}

/** Deterministic only within one activity ViewModel session; no raw source identities are exposed. */
class HybridOpaqueIds(private val salt: String = UUID.randomUUID().toString()) {
    fun key(kind: String, identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt\u0000$kind\u0000$identity".toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        val encoded = CharArray(32)
        for (index in 0 until 16) {
            val value = digest[index].toInt() and 0xff
            encoded[index * 2] = digits[value ushr 4]
            encoded[index * 2 + 1] = digits[value and 0x0f]
        }
        return kind + "_" + String(encoded)
    }
}

/** UI-thread admission guard: duplicates cannot execute a mutation twice. */
class HybridRequestGate(private val now: () -> Long = System::nanoTime) {
    private val seen = LinkedHashSet<String>()
    private var windowStarted = now()
    private var windowCount = 0
    var inFlight: Int = 0
        private set

    fun enter(id: String) {
        if (id in seen) throw HybridRequestFailure("DUPLICATE_REQUEST")
        val time = now()
        if (time - windowStarted >= 1_000_000_000L) { windowStarted = time; windowCount = 0 }
        if (inFlight >= HybridProtocol.MAX_IN_FLIGHT || windowCount >= 60) throw HybridRequestFailure("BRIDGE_BUSY")
        seen.add(id)
        while (seen.size > 256) seen.remove(seen.first())
        windowCount++
        inFlight++
    }

    fun leave() { inFlight = (inFlight - 1).coerceAtLeast(0) }
}
