package dev.cinematv.data.douban.public

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Bounded on-disk cache. It stores public list fields only, never account data. */
class DoubanPublicCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun read(key: String, ttlMs: Long = TTL_MS, staleMaxMs: Long = STALE_MAX_MS): DoubanPublicPage? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val page = runCatching { decode(text) }.getOrNull() ?: return null
        val age = now() - page.fetchedAt
        if (age < 0 || age > staleMaxMs) return null
        return page.copy(stale = age > ttlMs, source = if (age > ttlMs) "stale-cache" else "cache")
    }

    fun write(key: String, page: DoubanPublicPage): Boolean = runCatching {
        directory.mkdirs()
        val target = fileFor(key)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeText(encode(page))
        if (!temporary.renameTo(target)) {
            target.delete()
            if (!temporary.renameTo(target)) temporary.delete()
        }
        true
    }.getOrDefault(false)

    fun clear() {
        runCatching { directory.listFiles()?.forEach { it.delete() } }
    }

    private fun fileFor(key: String): File = File(directory, sha256(key).take(32) + ".json")

    private fun encode(page: DoubanPublicPage): String = JSONObject()
        .put("version", 1)
        .put("fetchedAt", page.fetchedAt)
        .put("stale", page.stale)
        .put("source", page.source)
        .put("nextStart", page.nextStart ?: JSONObject.NULL)
        .put("items", JSONArray(page.items.map { item ->
            JSONObject().put("id", item.subjectId).put("title", item.title).put("year", item.year)
                .put("rating", item.rating).put("cover", item.cover)
                .put("rank", item.rank ?: JSONObject.NULL).put("kind", item.kind.name)
        }))
        .toString()

    private fun decode(text: String): DoubanPublicPage {
        val root = JSONObject(text)
        val array = root.getJSONArray("items")
        val items = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                DoubanPublicItem(
                    subjectId = item.getString("id"), title = item.getString("title"),
                    year = item.optString("year"), rating = item.optString("rating"), cover = item.optString("cover"),
                    rank = if (item.isNull("rank")) null else item.optInt("rank").takeIf { it in 1..250 },
                    kind = DoubanPublicKind.valueOf(item.optString("kind", DoubanPublicKind.MOVIE.name))
                )
            }.getOrNull()
        }
        return DoubanPublicPage(
            items = items,
            nextStart = if (root.isNull("nextStart")) null else root.optInt("nextStart").takeIf { it >= 0 },
            fetchedAt = root.optLong("fetchedAt"),
            stale = root.optBoolean("stale"),
            source = root.optString("source", "cache")
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val TTL_MS = 7 * 24 * 60 * 60_000L
        const val STALE_MAX_MS = 30 * 24 * 60 * 60_000L
    }
}
