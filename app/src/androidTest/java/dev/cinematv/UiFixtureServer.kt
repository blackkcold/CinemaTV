package dev.cinematv

import android.content.Context
import android.net.Uri
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
    val poster = "android.resource://dev.cinematv/drawable/qa_poster"
    fun backdrop(index: Int) = "android.resource://dev.cinematv/drawable/qa_scene_${if (index % 2 == 0) "warm" else "cool"}"
    private val names = listOf("旷野之声", "蓝色回响", "远行的人", "深海来信", "山间一日", "光的方向")
    fun title(index: Int, tv: Boolean = false): String =
        (if (tv) "夜航计划" else names[index % names.size]) + if (index >= (if (tv) 1 else 6)) " · ${index + 1}" else ""
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
                    .put("pic", poster).put("sourceKey", "fixture").put("sourceName", "测试片库")
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
            "/api/detail" -> JSONObject().put("videoInfo", JSONObject().put("desc", "穿过辽阔的原野，听见风与时间的回响。一段关于远行、重逢与自我发现的故事。").put("cover", poster).put("typeName", "剧情"))
                .put("episodes", JSONArray((0 until 36).map { "$base/stream.mp4?episode=$it" })).toString().toByteArray()
            "/api/live/playlist" -> JSONObject().put("channels", JSONArray((0 until 18).map { i ->
                JSONObject().put("id", "channel-$i").put("name", listOf("电影精选", "世界纪录", "自然之声")[i % 3] + if (i > 2) " ${i + 1}" else "")
                    .put("url", "$base/stream.mp4?live=$i").put("group", if (i < 6) "精选" else "更多").put("tvgId", "channel-$i")
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
