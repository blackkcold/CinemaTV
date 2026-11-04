package dev.cinematv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Only this layer knows LibreTV's upstream API schema.
 *
 * LibreTV search: POST /api/search { wd, sources, filterAdult }
 * LibreTV detail: GET /api/detail?id=...&source=<json>
 * LibreTV metadata: GET /api/douban?type=movie|tv
 * LibreTV live: GET /api/live/playlist?url=...
 *
 * IMPORTANT: supply sources you are authorized to use. The app does not ship any feeds.
 */
class LibreTvRepository(context: Context) {
    private val prefs = context.getSharedPreferences("cinema_settings", Context.MODE_PRIVATE)
    private var sessionCookie: String? = null
    var server: String
        get() = prefs.getString("server", "") ?: ""
        private set(value) { prefs.edit().putString("server", value).apply() }

    val isConnected get() = server.isNotBlank()
    var defaultSources: List<TvSource> = emptyList()
        private set
    var defaultLiveSources: List<LiveSource> = emptyList()
        private set

    fun setServer(address: String) {
        val cleaned = address.trim().trimEnd('/')
        require(cleaned.startsWith("https://") || cleaned.startsWith("http://")) { "服务器地址必须以 https:// 或 http:// 开头" }
        if (cleaned != server) sessionCookie = null
        server = cleaned
    }

    private fun encode(raw: String) = URLEncoder.encode(raw, "UTF-8")

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        if (server.isBlank()) throw IOException("请先配置 LibreTV 服务器")
        val conn = (URL(server + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 16000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            sessionCookie?.let { setRequestProperty("Cookie", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            conn.getHeaderField("Set-Cookie")?.substringBefore(';')?.takeIf { it.contains('=') }?.let { sessionCookie = it }
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw IOException("HTTP $status: ${message.ifBlank { "服务暂不可用" }}")
            }
            return JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun sourceFrom(j: JSONObject): TvSource? {
        val url = j.optString("url")
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return TvSource(j.optString("key", url), j.optString("name", "未命名"), url, j.optString("detail"))
    }

    private fun liveSourceFrom(j: JSONObject): LiveSource? {
        val url = j.optString("url")
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return LiveSource(j.optString("key", url), j.optString("name", "直播订阅"), url, j.optString("epg"))
    }

    private fun <T> parseArray(array: JSONArray?, parse: (JSONObject) -> T?): List<T> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) array.optJSONObject(i)?.let(parse)?.let { add(it) }
    }

    private fun storedSources(): List<TvSource> {
        val arr = JSONArray(prefs.getString("sources", "[]") ?: "[]")
        return parseArray(arr, ::sourceFrom)
    }

    private fun storedLiveSources(): List<LiveSource> {
        val arr = JSONArray(prefs.getString("live_sources", "[]") ?: "[]")
        return parseArray(arr, ::liveSourceFrom)
    }

    fun sources(): List<TvSource> = (storedSources() + defaultSources).distinctBy { it.url }
    fun liveSources(): List<LiveSource> = (storedLiveSources() + defaultLiveSources).distinctBy { it.url }

    fun addSource(name: String, url: String) {
        val item = TvSource("custom_${System.currentTimeMillis()}", name.trim(), url.trim())
        require(item.url.startsWith("http://") || item.url.startsWith("https://"))
        val stored = storedSources().filterNot { it.url == item.url } + item
        prefs.edit().putString("sources", JSONArray(stored.map { sourceJson(it) }).toString()).apply()
    }

    fun addLiveSource(name: String, url: String, epg: String = "") {
        val item = LiveSource("custom_${System.currentTimeMillis()}", name.trim(), url.trim(), epg.trim())
        require(item.url.startsWith("http://") || item.url.startsWith("https://"))
        val stored = storedLiveSources().filterNot { it.url == item.url } + item
        prefs.edit().putString("live_sources", JSONArray(stored.map {
            JSONObject().put("key", it.key).put("name", it.name).put("url", it.url).put("epg", it.epg)
        }).toString()).apply()
    }

    fun removeSource(url: String, live: Boolean = false) {
        if (live) {
            val arr = JSONArray(storedLiveSources().filterNot { it.url == url }.map {
                JSONObject().put("key", it.key).put("name", it.name).put("url", it.url).put("epg", it.epg)
            })
            prefs.edit().putString("live_sources", arr.toString()).apply()
        } else {
            prefs.edit().putString("sources", JSONArray(storedSources().filterNot { it.url == url }.map(::sourceJson)).toString()).apply()
        }
    }

    private fun sourceJson(it: TvSource) = JSONObject()
        .put("key", it.key).put("name", it.name).put("url", it.url).put("detail", it.detail)

    suspend fun status(): ServerStatus = withContext(Dispatchers.IO) {
        val root = request("/api/status")
        defaultSources = parseArray(root.optJSONArray("defaultSources"), ::sourceFrom)
        defaultLiveSources = parseArray(root.optJSONArray("defaultLiveSources"), ::liveSourceFrom)
        ServerStatus(
            root.optBoolean("passwordRequired"),
            root.optBoolean("verified"),
            sources(),
            liveSources(),
            root.optString("version")
        )
    }

    suspend fun login(password: String): Boolean = withContext(Dispatchers.IO) {
        request("/api/auth", "POST", JSONObject().put("password", password)).optBoolean("success")
    }

    suspend fun recommend(isTv: Boolean = false, count: Int = 24): List<MediaItem> = withContext(Dispatchers.IO) {
        val type = if (isTv) "tv" else "movie"
        val data = request("/api/douban?type=$type&pageSize=$count")
        parseArray(data.optJSONArray("items")) { j ->
            val title = j.optString("title")
            if (title.isBlank()) null else MediaItem(
                id = "recommend:${j.optString("id", title)}",
                title = title,
                poster = j.optString("cover"),
                rating = j.optString("rating"),
                isTv = isTv
            )
        }
    }

    suspend fun search(keyword: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val active = sources()
        require(active.isNotEmpty()) { "请先在设置中添加点播源" }
        val payload = JSONObject().put("wd", keyword.trim())
            .put("filterAdult", true)
            .put("sources", JSONArray(active.map(::sourceJson)))
        val response = request("/api/search", "POST", payload)
        parseArray(response.optJSONArray("list")) { j ->
            val name = j.optString("name")
            val source = active.firstOrNull { it.key == j.optString("sourceKey") }
                ?: TvSource(j.optString("sourceKey"), j.optString("sourceName"), j.optString("sourceUrl"))
            if (name.isBlank() || source.url.isBlank()) null else MediaItem(
                id = j.optString("vodId"),
                title = name,
                poster = j.optString("pic"),
                year = j.optString("year"),
                category = j.optString("typeName"),
                source = source
            )
        }
    }

    suspend fun detail(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val entry = if (item.source == null) {
            search(item.title).firstOrNull { it.title.equals(item.title, ignoreCase = true) }
                ?: search(item.title).firstOrNull()
                ?: throw IOException("在已配置的片源中未找到《${item.title}》")
        } else item
        val src = requireNotNull(entry.source)
        val path = "/api/detail?id=${encode(entry.id)}&source=${encode(sourceJson(src).toString())}"
        val j = request(path)
        val info = j.optJSONObject("videoInfo") ?: JSONObject()
        val episodes = j.optJSONArray("episodes") ?: JSONArray()
        entry.copy(
            description = info.optString("desc"),
            poster = info.optString("cover").ifBlank { entry.poster },
            category = info.optString("typeName").ifBlank { entry.category },
            year = info.optString("year").ifBlank { entry.year },
            episodes = (0 until episodes.length()).mapNotNull { episodes.optString(it).takeIf(String::isNotBlank) }
        )
    }

    suspend fun channels(source: LiveSource): List<TvChannel> = withContext(Dispatchers.IO) {
        val j = request("/api/live/playlist?url=${encode(source.url)}")
        parseArray(j.optJSONArray("channels")) { c ->
            val url = c.optString("url")
            if (url.isBlank()) null else TvChannel(
                c.optString("id", url), c.optString("name", "频道"), url,
                c.optString("logo"), c.optString("group"), c.optString("tvgId")
            )
        }
    }

    suspend fun epg(source: LiveSource, channel: TvChannel): List<TvProgram> = withContext(Dispatchers.IO) {
        if (source.epg.isBlank()) return@withContext emptyList()
        val j = request("/api/live/epg?url=${encode(source.epg)}&channel=${encode(channel.tvgId.ifBlank { channel.id })}")
        parseArray(j.optJSONArray("programs")) { p ->
            TvProgram(p.optString("title"), p.optLong("start"), p.optLong("stop"))
        }
    }

    fun saveProgress(item: MediaItem, positionMs: Long) {
        if (item.id.isBlank()) return
        val updated = item.copy(positionMs = positionMs)
        val existing = history().filterNot { it.id == item.id && it.source?.url == item.source?.url }
        val list = (listOf(updated) + existing).take(60)
        prefs.edit().putString("history", JSONArray(list.map { itemJson(it) }).toString()).apply()
    }

    fun history(): List<MediaItem> = runCatching {
        parseArray(JSONArray(prefs.getString("history", "[]") ?: "[]")) { j ->
            MediaItem(
                id = j.optString("id"), title = j.optString("title"),
                poster = j.optString("poster"), year = j.optString("year"),
                source = j.optJSONObject("source")?.let(::sourceFrom),
                positionMs = j.optLong("positionMs")
            )
        }
    }.getOrDefault(emptyList())

    private fun itemJson(it: MediaItem) = JSONObject()
        .put("id", it.id).put("title", it.title).put("poster", it.poster)
        .put("year", it.year).put("source", it.source?.let(::sourceJson))
        .put("positionMs", it.positionMs)

    fun toggleFavorite(item: MediaItem) {
        val current = favorites()
        val updated = if (current.any { it.id == item.id && it.source?.url == item.source?.url })
            current.filterNot { it.id == item.id && it.source?.url == item.source?.url }
        else listOf(item) + current
        prefs.edit().putString("favorites", JSONArray(updated.map(::itemJson)).toString()).apply()
    }

    fun favorites(): List<MediaItem> = runCatching {
        parseArray(JSONArray(prefs.getString("favorites", "[]") ?: "[]")) { j ->
            MediaItem(
                id = j.optString("id"), title = j.optString("title"), poster = j.optString("poster"),
                year = j.optString("year"), source = j.optJSONObject("source")?.let(::sourceFrom)
            )
        }
    }.getOrDefault(emptyList())
}
