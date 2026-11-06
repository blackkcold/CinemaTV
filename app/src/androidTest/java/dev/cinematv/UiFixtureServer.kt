package dev.cinematv

import android.content.Context
import android.net.Uri
import dev.cinematv.data.MediaItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** In-process, offline LibreTV contract fixture. No public streams or credentials. */
class UiFixtureServer(private val context: Context) : AutoCloseable {
    private val server = ServerSocket(0)
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var running = true
    val base = "http://127.0.0.1:${server.localPort}"
    val poster = "android.resource://dev.cinematv/${R.drawable.qa_poster}"
    fun backdrop(index: Int) = "android.resource://dev.cinematv/${if (index % 2 == 0) R.drawable.qa_scene_warm else R.drawable.qa_scene_cool}"
    private val names = listOf("旷野之声", "蓝色回响", "远行的人", "深海来信", "山间一日", "光的方向")
    fun title(index: Int, tv: Boolean = false): String =
        (if (tv) "夜航计划" else names[index % names.size]) + if (index >= (if (tv) 1 else 6)) " · ${index + 1}" else ""
    // Opt-in overrides only. Existing smoke/golden fixtures retain their exact responses.
    private val details = ConcurrentHashMap<String, String>()
    @Volatile var catalogPartial = false
    @Volatile var catalogExpired = false
    @Volatile var pagedCatalog = false
    @Volatile var catalogFilteredPages = false
    @Volatile var homeCatalog = false
    @Volatile var duplicateLiveIds = false
    val requests = CopyOnWriteArrayList<String>()
    val catalogRequests = CopyOnWriteArrayList<String>()

    /** Exact source + ID lookup; never infer a season from a different cached entry. */
    fun registerDetail(item: MediaItem) {
        val source = requireNotNull(item.source)
        val info = JSONObject().put("title", item.title).put("isTv", item.isTv)
            .put("sourceKey", source.key).put("sourceUrl", source.url)
            .put("cover", item.poster).put("episodeNames", JSONArray(item.episodeNames))
            .put("episodeIds", JSONArray(item.episodeIds))
        item.seasonNumber?.let { info.put("season", it) }
        details["${source.key}|${source.url}|${item.id}"] = JSONObject()
            .put("videoInfo", info).put("episodes", JSONArray(item.episodes)).toString()
    }

    private fun catalog(uri: Uri): String {
        if (!pagedCatalog) return if (homeCatalog) homeCatalog(uri) else "{}"
        catalogRequests += uri.toString()
        val offset = uri.getQueryParameter("offset")?.toIntOrNull() ?: 0
        val tv = uri.getQueryParameter("type") == "tv"
        return JSONObject().put("status", "available").put("provider", "synthetic")
            .put("attribution", "Offline synthetic QA catalog")
            .put("license", JSONObject().put("expiresAt", if (catalogExpired) "2000-01-01T00:00:00Z" else "2099-01-01T00:00:00Z"))
            .put("acquisition", JSONObject().put("source", "cache").put("snapshotId", "fixture-snapshot")
                .put("complete", !catalogPartial).put("stale", catalogPartial).put("state", if (catalogPartial) "partial" else "fresh"))
            .put("nextOffset", if (offset == 0) 2 else JSONObject.NULL)
            .put("items", JSONArray((offset until offset + 2).map { i ->
                JSONObject().put("id", "${if (tv) "tv" else "movie"}-$i")
                    .put("title", "Paging fixture ${if (tv) "series" else "movie"} $i")
                    .put("type", if (tv) "tv" else "movie").put("poster", poster).put("backdrop", backdrop(i))
                    .apply { if (catalogFilteredPages) {
                        put("year", if (i == 0) "2025" else "2026")
                        put("category", if (i == 1) "纪录" else "剧情")
                    } }
            })).toString()
    }

    /** Explicit synthetic BFF replacement for smoke tests; no real provider or rights claims. */
    private fun homeCatalog(uri: Uri): String {
        val tv = uri.getQueryParameter("type") == "tv"
        val query = uri.getQueryParameter("query").orEmpty()
        return JSONObject().put("status", "available").put("provider", "synthetic")
            .put("nextOffset", JSONObject.NULL)
            .put("items", JSONArray((0 until 24).filter { title(it, tv).contains(query) }.map { i ->
                JSONObject().put("id", "${if (tv) "tv" else "movie"}-$i").put("title", title(i, tv))
                    .put("type", if (tv) "tv" else "movie").put("poster", poster).put("backdrop", backdrop(i))
                    .put("rating", "${8 + i % 2}.${i % 10}").put("ratingScale", 10).put("ratingSource", "Synthetic")
                    .put("year", if (i % 3 == 0) "2025" else "2026").put("category", if (i % 2 == 0) "剧情" else "冒险")
                    .put("overview", "穿过辽阔的原野，听见风与时间的回响。一段关于远行、重逢与自我发现的故事。")
            })).toString()
    }

    init {
        workers.submit {
            while (running) {
                try { val socket = server.accept(); workers.submit { runCatching { handle(socket) }; socket.close() } }
                catch (_: Exception) { if (!running) break }
            }
        }
    }
    private fun recommendations(tv: Boolean) = JSONArray((0 until 24).map { i ->
        JSONObject().put("id", "${if (tv) "tv" else "movie"}-$i").put("title", title(i, tv))
            .put("cover", poster).put("backdrop", backdrop(i)).put("rating", "${8 + i % 2}.${i % 10}")
            .put("year", if (i % 3 == 0) "2025" else "2026").put("typeName", if (i % 2 == 0) "剧情" else "冒险")
            .put("description", "穿过辽阔的原野，听见风与时间的回响。一段关于远行、重逢与自我发现的故事。")
    })
    private fun search(query: String): JSONArray {
        val result = mutableListOf<JSONObject>()
        listOf(false, true).forEach { tv ->
            (0 until 24).filter { title(it, tv).contains(query) }.forEach { i ->
                result += JSONObject().put("vodId", "${if (tv) "tv" else "movie"}-$i").put("name", title(i, tv))
                    .put("pic", poster).put("isTv", tv).put("sourceKey", "fixture").put("sourceName", "测试片库")
                    .put("sourceUrl", "$base/provider").put("year", if (i % 3 == 0) "2025" else "2026").put("typeName", "剧情")
            }
        }
        return JSONArray(result)
    }
    private fun line(input: BufferedInputStream): String {
        val result = StringBuilder()
        while (result.length < 32768) {
            val b = input.read()
            if (b == -1 || b == 10) break
            if (b != 13) result.append(b.toChar())
        }
        return result.toString()
    }
    private fun handle(socket: Socket) {
        socket.soTimeout = 15000
        val input = BufferedInputStream(socket.getInputStream())
        val request = line(input).split(' ')
        if (request.size < 2) return
        val uri = Uri.parse(request[1])
        requests += uri.toString()
        var length = 0
        while (true) {
            val header = line(input)
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
        }
        val bytes = ByteArray(length.coerceIn(0, 65536))
        var read = 0
        while (read < bytes.size) { val count = input.read(bytes, read, bytes.size - read); if (count < 0) break; read += count }
        val body = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrDefault(JSONObject())
        var type = "application/json; charset=utf-8"
        val now = System.currentTimeMillis()
        val response: ByteArray = when (uri.path) {
            "/v1/catalog" -> catalog(uri).toByteArray()
            "/api/status" -> JSONObject().put("passwordRequired", false).put("verified", true)
                .put("defaultSources", JSONArray().put(JSONObject().put("key", "fixture").put("name", "测试片库").put("url", "$base/provider")))
                .put("defaultLiveSources", JSONArray().put(JSONObject().put("key", "live").put("name", "电影频道").put("url", "$base/live.m3u").put("epg", "$base/epg.xml")))
                .toString().toByteArray()
            "/api/douban" -> JSONObject().put("items", recommendations(uri.getQueryParameter("type") == "tv")).toString().toByteArray()
            "/api/search" -> {
                val list = search(body.optString("wd"))
                if (uri.getQueryParameter("stream") == "1") {
                    type = "application/x-ndjson"
                    (JSONObject().put("type", "source").put("list", list).toString() + "\n" +
                        JSONObject().put("type", "done").put("list", list).toString() + "\n").toByteArray()
                } else JSONObject().put("list", list).toString().toByteArray()
            }
            "/api/detail" -> {
                val source = runCatching { JSONObject(uri.getQueryParameter("source").orEmpty()) }.getOrDefault(JSONObject())
                val key = "${source.optString("key")}|${source.optString("url")}|${uri.getQueryParameter("id")}"
                (details[key] ?: JSONObject().put("videoInfo", JSONObject().put("desc", "穿过辽阔的原野，听见风与时间的回响。一段关于远行、重逢与自我发现的故事。").put("cover", poster).put("typeName", "剧情"))
                .put("episodes", JSONArray((0 until 36).map { "$base/stream.mp4?episode=$it" })).toString()).toByteArray()
            }
            "/api/live/playlist" -> JSONObject().put("channels", JSONArray((0 until 18).map { i ->
                JSONObject().put("id", if (duplicateLiveIds && i < 2) "shared-epg-id" else "channel-$i")
                    .put("name", if (duplicateLiveIds && i < 2) "同一频道 ${if (i == 0) "HD" else "SD"}"
                        else listOf("电影精选", "世界纪录", "自然之声")[i % 3] + if (i > 2) " ${i + 1}" else "")
                    .put("url", "$base/stream.mp4?live=$i").put("group", if (i < 6) "精选" else "更多")
                    .put("tvgId", if (duplicateLiveIds && i < 2) "shared-epg-id" else "channel-$i")
            })).toString().toByteArray()
            "/api/live/epg" -> JSONObject().put("programs", JSONArray((0 until 12).map { i ->
                JSONObject().put("title", listOf("山海之间", "远方的故事", "光影时刻")[i % 3])
                    .put("start", now - 900000 + i * 1800000L).put("stop", now + 900000 + i * 1800000L)
            })).toString().toByteArray()
            "/stream.mp4" -> { type = "video/mp4"; context.assets.open("playback-fixture.mp4").use { it.readBytes() } }
            else -> "{}".toByteArray()
        }
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(response); output.flush()
    }
    override fun close() { running = false; server.close(); workers.shutdownNow() }
}
