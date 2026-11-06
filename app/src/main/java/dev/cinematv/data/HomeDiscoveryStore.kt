package dev.cinematv.data

import android.content.Context
import dev.cinematv.data.library.MediaItemCodec
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * LibreTV has no browse endpoint, so a install that only knows a LibreTV server can discover
 * content through exactly one thing: a search. This store keeps that search bounded:
 *
 * - one query at a time, chosen from a small rotating list of title-ish terms (LibreTV matches
 *   names, never categories, so "动作片" would return nothing);
 * - a six-hour cache keyed by server, connection generation, query and the enabled source set;
 * - a hard cap on files and items so discovery can never grow without a bound.
 *
 * Nothing here is triggered by focus or navigation; callers decide when to rotate.
 */
class HomeDiscoveryStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val directory = File(context.cacheDir, "home-discovery")

    data class Cached(val query: String, val fetchedAt: Long, val items: List<MediaItem>)

    fun read(key: String): Cached? = runCatching {
        val file = File(directory, "$key.json")
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        val fetchedAt = root.optLong("fetchedAt")
        val age = now() - fetchedAt
        if (age !in 0..TTL_MS) {
            runCatching { file.delete() }
            return null
        }
        val items = root.optJSONArray("items") ?: JSONArray()
        Cached(
            query = root.optString("query"),
            fetchedAt = fetchedAt,
            items = (0 until items.length()).mapNotNull { index ->
                runCatching { MediaItemCodec.decode(items.getJSONObject(index)) }.getOrNull()
            }
        )
    }.getOrNull()?.takeIf { it.items.isNotEmpty() }

    fun write(query: String, fetchedAt: Long, items: List<MediaItem>, key: String) {
        runCatching {
            if (items.isEmpty()) return
            directory.mkdirs()
            val array = JSONArray(items.take(MAX_ITEMS).map { MediaItemCodec.json(it) })
            File(directory, "$key.json").writeText(
                JSONObject().put("query", query).put("fetchedAt", fetchedAt).put("items", array).toString()
            )
            directory.listFiles()?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)
                ?.forEach { file -> runCatching { file.delete() } }
        }
    }

    companion object {
        const val TTL_MS = 6L * 60 * 60 * 1_000
        const val MAX_FILES = 8
        const val MAX_ITEMS = 60

        /**
         * Two-character, title-ish terms. A category name would be a lie: LibreTV forwards the
         * keyword to each source's search, which matches titles, aliases and actors only.
         */
        val QUERIES = listOf("星际", "悬疑", "犯罪", "时空", "江湖", "少年", "战争", "秘密", "城市", "远方")

        fun queryAt(seed: Int, rotation: Int): String = QUERIES[Math.floorMod(seed + rotation, QUERIES.size)]

        /** Cache identity: server, session generation, query and the exact enabled source set. */
        fun keyFor(server: String, generation: Long, query: String, sources: List<TvSource>): String {
            val raw = "$server|$generation|$query|" + sources.filter { it.enabled }
                .map { "${it.key}@${it.url}" }.sorted().joinToString(",")
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }.take(32)
        }
    }
}
