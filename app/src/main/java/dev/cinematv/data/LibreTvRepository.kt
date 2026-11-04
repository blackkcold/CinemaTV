package dev.cinematv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

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
    private data class MetadataArt(val backdrop: String, val poster: String, val overview: String)
    private val metadataCache = ConcurrentHashMap<String, MetadataArt>()
    var metadataServer: String
        get() = prefs.getString("metadata_server", "") ?: ""
        private set(value) { prefs.edit().putString("metadata_server", value).apply() }
    @Volatile private var metadataAccessToken: String = ""

    /**
     * Optional operator-owned BFF. Access token remains in memory and must be
     * re-entered after process death rather than being persisted in plain text.
     */
    fun configureMetadata(address: String, token: String) {
        val cleaned = address.trim().trimEnd('/')
        require(cleaned.isBlank() || cleaned.startsWith("https://") ||
            cleaned.startsWith("http://")) { "BFF 地址必须为 HTTP(S)" }
        metadataServer = cleaned
        metadataAccessToken = token.trim()
        metadataCache.clear()
    }

    suspend fun enrichMetadata(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val urlBase = metadataServer
        val token = metadataAccessToken
        if (urlBase.isBlank() || token.isBlank() || item.backdrop.isNotBlank()) {
            return@withContext item
        }
        val cacheKey = listOf(item.title, item.year, if (item.isTv) "tv" else "movie").joinToString("|")
        val art = metadataCache[cacheKey] ?: run {
            val query = "/v1/metadata?title=${encode(item.title)}&year=${encode(item.year)}&type=${if (item.isTv) "tv" else "movie"}"
            val conn = (URL(urlBase + query).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 7500
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) return@withContext item
                val root = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                val data = root.optJSONObject("item") ?: return@withContext item
                MetadataArt(data.optString("backdrop"), data.optString("poster"),
                    data.optString("overview")).also { metadataCache[cacheKey] = it }
            } finally { conn.disconnect() }
        }
        item.copy(
            backdrop = art.backdrop,
            poster = item.poster.ifBlank { art.poster },
            description = item.description.ifBlank { art.overview }
        )
    }

    var defaultSources: List<TvSource> = emptyList()
        private set
    var defaultLiveSources: List<LiveSource> = emptyList()
        private set

    fun configureServer(address: String) {
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

    private fun disabledSourceUrls(): Set<String> =
        prefs.getStringSet("disabled_sources", emptySet()) ?: emptySet()

    fun sources(): List<TvSource> = (storedSources() + defaultSources)
        .distinctBy { it.url }
        .map { it.copy(enabled = it.url !in disabledSourceUrls()) }

    fun setSourceEnabled(url: String, enabled: Boolean) {
        val updated = disabledSourceUrls().toMutableSet()
        if (enabled) updated.remove(url) else updated.add(url)
        prefs.edit().putStringSet("disabled_sources", updated).apply()
    }

    suspend fun probeSource(source: TvSource): SourceProbe = withContext(Dispatchers.IO) {
        val address = source.url + if (source.url.contains("?")) "&ac=list" else "?ac=list"
        val started = System.nanoTime()
        val conn = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 7000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = conn.responseCode
            val ms = (System.nanoTime() - started) / 1_000_000L
            val isJson = conn.contentType.orEmpty().contains("json", ignoreCase = true)
            SourceProbe(status in 200..299 && isJson, ms,
                if (status in 200..299 && isJson) "正常"
                else "HTTP $status / 非 JSON 接口")
        } finally { conn.disconnect() }
    }
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

    private fun decodeSearchList(items: JSONArray?, active: List<TvSource>): List<MediaItem> =
        parseArray(items) { j ->
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

    private fun searchPayload(keyword: String, active: List<TvSource>) = JSONObject()
        .put("wd", keyword.trim())
        .put("filterAdult", true)
        .put("sources", JSONArray(active.map(::sourceJson)))

    suspend fun search(keyword: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val active = sources().filter { it.enabled }
        require(active.isNotEmpty()) { "请先在设置中添加点播源" }
        val response = request("/api/search", "POST", searchPayload(keyword, active))
        decodeSearchList(response.optJSONArray("list"), active)
    }

    /**
     * LibreTV sends one NDJSON 'source' event per completed origin, then one
     * 'done' event with authoritative order. Every intermediate emission is
     * usable, cancellation closes the network stream immediately.
     */
    fun searchProgressively(keyword: String): Flow<List<MediaItem>> = flow {
        val active = sources().filter { it.enabled }
        require(active.isNotEmpty()) { "请先在设置中添加点播源" }
        val conn = (URL(server + "/api/search?stream=1").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 30000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/x-ndjson")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            sessionCookie?.let { setRequestProperty("Cookie", it) }
            doOutput = true
        }
        try {
            conn.outputStream.use {
                it.write(searchPayload(keyword, active).toString().toByteArray(Charsets.UTF_8))
            }
            if (conn.responseCode !in 200..299) {
                throw IOException("LibreTV 流式搜索失败：HTTP ${conn.responseCode}")
            }
            var accumulated = emptyList<MediaItem>()
            var completed = false
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val event = JSONObject(line)
                    val batch = decodeSearchList(event.optJSONArray("list"), active)
                    when (event.optString("type")) {
                        "source" -> {
                            accumulated = (accumulated + batch).distinctBy {
                                (it.source?.key ?: "") + "/" + it.id
                            }
                            emit(SearchAggregator.group(accumulated))
                        }
                        "done" -> {
                            completed = true
                            emit(SearchAggregator.group(batch))
                        }
                        else -> {
                            if (event.has("list")) {
                                completed = true
                                emit(SearchAggregator.group(batch))
                            }
                        }
                    }
                }
            }
            if (!completed) emit(SearchAggregator.group(accumulated))
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun detail(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val entry = if (item.source == null) {
            search(item.title).firstOrNull {
                MediaIdentity.isSameWork(item.title, item.year, it.title, it.year)
            } ?: throw IOException("未找到可确认与《${item.title}》匹配的播放来源")
        } else item
        val src = requireNotNull(entry.source)
        val path = "/api/detail?id=${encode(entry.id)}&source=${encode(sourceJson(src).toString())}"
        val j = request(path)
        val info = j.optJSONObject("videoInfo") ?: JSONObject()
        val episodes = j.optJSONArray("episodes") ?: JSONArray()
        val progress = history().firstOrNull { saved ->
            (saved.source?.url == entry.source?.url && saved.id == entry.id) ||
                (saved.source == null && MediaIdentity.isSameWork(saved.title, saved.year, entry.title, entry.year))
        }
        entry.copy(
            isTv = item.isTv || entry.isTv,
            positionMs = progress?.positionMs ?: 0L,
            lastEpisode = progress?.lastEpisode ?: 0,
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

    fun saveProgress(item: MediaItem, positionMs: Long, episodeIndex: Int = 0) {
        if (item.id.isBlank()) return
        val updated = item.copy(positionMs = positionMs, lastEpisode = episodeIndex.coerceAtLeast(0))
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
                positionMs = j.optLong("positionMs"), lastEpisode = j.optInt("lastEpisode", 0)
            )
        }
    }.getOrDefault(emptyList())

    private fun itemJson(it: MediaItem) = JSONObject()
        .put("id", it.id).put("title", it.title).put("poster", it.poster)
        .put("year", it.year).put("source", it.source?.let(::sourceJson))
        .put("positionMs", it.positionMs).put("lastEpisode", it.lastEpisode)

    private fun libraryEntries(): List<Pair<LibraryStatus, MediaItem>> = runCatching {
        parseArray(JSONArray(prefs.getString("library_entries", "[]") ?: "[]")) { obj ->
            val status = runCatching { LibraryStatus.valueOf(obj.optString("state")) }.getOrNull()
                ?: return@parseArray null
            val media = MediaItem(
                id = obj.optString("id"), title = obj.optString("title"),
                poster = obj.optString("poster"), year = obj.optString("year"),
                source = obj.optJSONObject("source")?.let(::sourceFrom),
                positionMs = obj.optLong("positionMs"),
                lastEpisode = obj.optInt("lastEpisode", 0)
            )
            status to media
        }
    }.getOrDefault(emptyList())

    fun libraryStatus(item: MediaItem): LibraryStatus? = libraryEntries()
        .firstOrNull { LibraryIdentity.key(it.second) == LibraryIdentity.key(item) }?.first

    fun setLibraryStatus(item: MediaItem, status: LibraryStatus?) {
        require(item.id.isNotBlank())
        val entries = libraryEntries().filterNot { LibraryIdentity.key(it.second) == LibraryIdentity.key(item) }
        val updated = if (status == null) entries else listOf(status to item) + entries
        val json = JSONArray(updated.take(500).map { (state, media) ->
            itemJson(media).put("state", state.name)
        })
        prefs.edit().putString("library_entries", json.toString()).apply()
    }

    fun libraryByStatus(status: LibraryStatus): List<MediaItem> =
        libraryEntries().filter { it.first == status }.map { it.second }

    fun toggleFavorite(item: MediaItem) {
        val current = libraryStatus(item)
        if (current == LibraryStatus.FAVORITE) setLibraryStatus(item, null)
        else setLibraryStatus(item, LibraryStatus.FAVORITE)
    }

    fun favorites(): List<MediaItem> = (libraryByStatus(LibraryStatus.FAVORITE) + legacyFavorites())
        .distinctBy(LibraryIdentity::key)

    private fun legacyFavorites(): List<MediaItem> = runCatching {
        parseArray(JSONArray(prefs.getString("favorites", "[]") ?: "[]")) { j ->
            MediaItem(
                id = j.optString("id"), title = j.optString("title"), poster = j.optString("poster"),
                year = j.optString("year"), source = j.optJSONObject("source")?.let(::sourceFrom)
            )
        }
    }.getOrDefault(emptyList())
}
