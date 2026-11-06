package dev.cinematv.data

import android.content.Context
import dev.cinematv.data.library.LocalMediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Only this layer knows LibreTV's upstream API schema.
 *
 * LibreTV search: POST /api/search { wd, sources, filterAdult }
 * LibreTV detail: GET /api/detail?id=...&source=<json>
 * Metadata catalog: explicitly configured BFF /v1/catalog (never LibreTV Douban proxy)
 * LibreTV live: GET /api/live/playlist?url=...
 *
 * IMPORTANT: supply sources you are authorized to use. The app does not ship any feeds.
 */
class LibreTvRepository(context: Context, val library: LocalMediaLibrary = LocalMediaLibrary(context)) {
    @Volatile var persistenceWarning: String = ""
        private set
    private val prefs = context.getSharedPreferences("cinema_settings", Context.MODE_PRIVATE)
    @Volatile private var sessionCookie: String? = null
    private val jsonClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .build()
    var server: String
        get() = prefs.getString("server", "") ?: ""
        private set(value) { prefs.edit().putString("server", value).apply() }

    val opticalGlassEnabled get() = prefs.getBoolean("optical_glass", false)
    val reducedMotion get() = prefs.getBoolean("reduced_motion", false)
    val glassQuality get() = prefs.getString("glass_quality", if (opticalGlassEnabled) "ENHANCED" else "AUTO") ?: "AUTO"

    fun setReducedMotion(enabled: Boolean) { prefs.edit().putBoolean("reduced_motion", enabled).apply() }
    fun setGlassQuality(quality: String) { prefs.edit().putString("glass_quality", quality).apply() }

    fun setOpticalGlass(enabled: Boolean) {
        prefs.edit().putBoolean("optical_glass", enabled).apply()
    }

    val isConnected get() = server.isNotBlank()
    private data class MetadataArt(val backdrop: String, val poster: String, val overview: String, val expiresAt: Long)
    private val metadataCache = ConcurrentHashMap<String, MetadataArt>()
    private val metadataMutex = Mutex()
    var metadataServer: String
        get() = prefs.getString("metadata_server", "") ?: ""
        private set(value) { prefs.edit().putString("metadata_server", value).apply() }
    @Volatile private var metadataAccessToken: String = ""
    val hasMetadataCatalog: Boolean get() = metadataServer.isNotBlank() && metadataAccessToken.isNotBlank()

    private fun strings(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).trim().takeIf(String::isNotBlank) }
        is String -> value.split(Regex("[,，/、;；]")).map(String::trim).filter(String::isNotBlank)
        else -> emptyList()
    }.distinct().take(50)

    private fun acquisition(value: JSONObject?): CatalogAcquisition? = value?.let { data ->
        fun text(name: String) = data.optString(name).trim().takeUnless { it.isBlank() || it == "null" }
        fun timestamp(name: String) = text(name)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        CatalogAcquisition(
            source = text("source")?.takeIf { it in setOf("network", "cache", "stale-cache") },
            fetchedAt = timestamp("fetchedAt"), stale = data.optBoolean("stale", false),
            complete = data.optBoolean("complete", false),
            expectedTotal = 250, // This acquisition contract is specifically Douban Top 250.
            availableTotal = data.optInt("availableTotal", 0).coerceIn(0, 250),
            warnings = strings(data.opt("warnings")), blocked = data.optBoolean("blocked", false),
            retryAt = timestamp("retryAt"), snapshotId = text("snapshotId"),
            refreshing = data.optBoolean("refreshing", false),
            state = when (text("state")) {
                "fresh" -> CatalogAcquisitionState.FRESH
                "refreshing" -> CatalogAcquisitionState.REFRESHING
                "partial" -> CatalogAcquisitionState.PARTIAL
                "rate_limited" -> CatalogAcquisitionState.RATE_LIMITED
                "access_blocked" -> CatalogAcquisitionState.ACCESS_BLOCKED
                "disabled" -> CatalogAcquisitionState.DISABLED
                else -> CatalogAcquisitionState.UNAVAILABLE
            }
        )
    }

    /** Only the configured BFF receives the explicit query. Viewing history stays on device. */
    suspend fun catalog(query: String = "", isTv: Boolean = false, offset: Int = 0, topOnly: Boolean = false): MetadataCatalog = withContext(Dispatchers.IO) {
        if (!hasMetadataCatalog) return@withContext MetadataCatalog()
        val conn = (URL(metadataServer + "/v1/catalog?type=${if (isTv) "tv" else "movie"}&limit=50&query=${encode(query.take(100))}" +
            (if (offset > 0) "&offset=$offset" else "") + (if (topOnly) "&chart=douban-top250" else ""))
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 9000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $metadataAccessToken")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) return@withContext MetadataCatalog()
            val body = conn.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_048_576) { "元数据目录响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val root = JSONObject(String(body, Charsets.UTF_8))
            val acquisition = acquisition(root.optJSONObject("acquisition"))
            val provider = root.optString("provider").takeUnless { it == "null" }.orEmpty()
            val attribution = root.optString("attribution").takeUnless { it == "null" }.orEmpty()
            val license = root.optJSONObject("license")
            val expiry = runCatching { Instant.parse(license?.optString("expiresAt")).toEpochMilli() }.getOrDefault(0L)
            val licensed = license != null && expiry > System.currentTimeMillis()
            if (license != null && !licensed) return@withContext MetadataCatalog(provider = provider,
                attribution = attribution, acquisition = acquisition, licenseExpiresAt = expiry, licenseRejected = true)
            val items = parseArray(root.optJSONArray("items")) { item ->
                val title = item.optString("title").trim()
                val provider = item.optString("provider").ifBlank { root.optString("provider") }
                val ratingSource = item.optString("ratingSource").takeUnless { it == "null" }.orEmpty()
                val validRating = item.optDouble("ratingScale", 0.0) == 10.0 &&
                    item.optDouble("rating", -1.0) in 0.0..10.0 &&
                    (RatingPolicy.normalizeProvider(ratingSource) != "douban" || licensed)
                if (title.isBlank()) null else MediaItem(
                    id = "catalog:$provider:${item.optString("id")}", title = title,
                    poster = item.optString("poster"), backdrop = item.optString("backdrop"),
                    year = item.optString("year"), description = item.optString("overview"),
                    category = item.optString("category"), isTv = item.optString("type") == "tv",
                    rating = if (validRating) item.optString("rating") else "",
                    ratingSource = if (validRating) ratingSource else "",
                    aliases = strings(item.opt("aliases")), actors = strings(item.opt("actors")),
                    doubanTopRank = item.optInt("topRank", 0).takeIf {
                        licensed && RatingPolicy.normalizeProvider(item.optString("topSource")) == "douban" &&
                            RatingPolicy.normalizeProvider(ratingSource) == "douban" && it in 1..250
                    },
                    ratingLicenseExpiresAt = if (licensed) expiry else 0L,
                    catalogProvider = provider,
                    catalogAttribution = root.optString("attribution").takeUnless { it == "null" }.orEmpty(),
                    catalogLicenseExpiresAt = if (licensed) expiry else 0L,
                    metadataIds = if (provider.isNotBlank() && item.optString("providerId").isNotBlank())
                        mapOf(provider.lowercase() to item.optString("providerId")) else emptyMap()
                )
            }
            MetadataCatalog(items, provider, attribution, root.optString("status") == "available",
                if (root.isNull("nextOffset")) null else root.optInt("nextOffset", -1).takeIf { it > offset && it <= 4950 },
                acquisition = acquisition, licenseExpiresAt = if (licensed) expiry else 0L)
        } finally { conn.disconnect() }
    }

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
        val art = metadataMutex.withLock {
          metadataCache[cacheKey]?.takeIf { it.expiresAt > System.currentTimeMillis() } ?: run {
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
                val data = root.optJSONObject("item")
                MetadataArt(data?.optString("backdrop").orEmpty(), data?.optString("poster").orEmpty(),
                    data?.optString("overview").orEmpty(), System.currentTimeMillis() + if (data == null) 300000L else 3600000L)
                    .also {
                        if (metadataCache.size >= 96) metadataCache.keys.firstOrNull()?.let(metadataCache::remove)
                        metadataCache[cacheKey] = it
                    }
            } finally { conn.disconnect() }
          }
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

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        if (server.isBlank()) throw IOException("请先配置 LibreTV 服务器")
        val request = Request.Builder().url(server + path)
            .header("Accept", "application/json")
            .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { sessionCookie?.let { header("Cookie", it) } }
            .build()
        return withCancellableCall(jsonClient.newCall(request)) { response ->
            val status = response.code
            response.header("Set-Cookie")?.substringBefore(';')?.takeIf { it.contains('=') }?.let { sessionCookie = it }
            if (status == 401) sessionCookie = null
            val text = response.body?.byteStream()
                ?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (output.length + count > 4_194_304) throw LibreTvProtocolException("LibreTV 响应过大")
                        output.append(buffer, 0, count)
                    }
                    output.toString()
                }.orEmpty()
            if (status !in 200..299) {
                if (status == 401) sessionCookie = null
                val message = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw LibreTvHttpException(status, message)
            }
            JSONObject(text)
        }
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

    /**
     * Compatibility preview for callers without paging. Never use LibreTV's Douban
     * proxy: transport availability is not permission to acquire Douban data.
     * Scores and their provenance come only from the configured catalog decoder.
     * Personalization remains in RecommendationEngine and never leaves the device.
     */
    suspend fun recommend(isTv: Boolean = false, count: Int = 24): List<MediaItem> {
        if (count <= 0) return emptyList()
        val items = if (hasMetadataCatalog) {
            try {
                catalog(isTv = isTv).let { if (it.available) it.items else emptyList() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
        return items.ifEmpty { localItems().filter { it.isTv == isTv } }.take(count)
    }

    private fun mediaType(data: JSONObject): Boolean? = when {
        data.has("isTv") && !data.isNull("isTv") -> data.optBoolean("isTv")
        data.optString("type").lowercase() == "tv" -> true
        data.optString("type").lowercase() == "movie" -> false
        data.optString("typeName").trim() in setOf("电影", "动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "剧情片", "战争片") -> false
        data.optString("typeName").trim() in setOf("电视剧", "连续剧", "国产剧", "欧美剧", "日韩剧", "港台剧", "香港剧", "台湾剧", "日本剧", "韩国剧", "海外剧") -> true
        else -> null // Animation, documentary and variety may be films or series.
    }

    private fun decodeSearchList(items: JSONArray?, active: List<TvSource>): List<MediaItem> =
        parseArray(items) { j ->
            val name = j.optString("name")
            val source = SourceResponseIdentity.resolve(active, j.optString("sourceKey"), j.optString("sourceUrl"))
                ?: return@parseArray null
            if (name.isBlank() || j.optString("vodId").isBlank() ||
                (!source.url.startsWith("https://") && !source.url.startsWith("http://"))) null else MediaItem(
                id = j.optString("vodId"),
                title = name,
                poster = j.optString("pic"),
                year = j.optString("year"),
                category = j.optString("typeName"),
                isTv = mediaType(j) ?: false,
                mediaTypeKnown = mediaType(j) != null,
                aliases = strings(j.opt("aliases")).ifEmpty { strings(j.opt("vod_sub")) },
                actors = strings(j.opt("actors")).ifEmpty { strings(j.opt("vod_actor")) },
                // Search transport supplies no verified score provenance.
                rating = "", ratingSource = "",
                seasonNumber = j.optInt("season", 0).takeIf { it > 0 },
                source = source
            )
        }

    private fun searchPayload(keyword: String, active: List<TvSource>) = JSONObject()
        .put("wd", keyword.trim())
        .put("filterAdult", true)
        .put("sources", JSONArray(active.map(::sourceJson)))

    private fun outcomes(items: JSONArray?): List<SourceSearchOutcome> = parseArray(items) { j ->
        SourceSearchOutcome(j.optString("sourceKey"), j.optBoolean("ok", false),
            j.optString("error"), j.optBoolean("timedOut", false),
            if (j.has("ms") && !j.isNull("ms")) j.optLong("ms").coerceAtLeast(0L) else null)
    }

    suspend fun searchSnapshot(keyword: String): SearchSnapshot = withContext(Dispatchers.IO) {
        val active = sources().filter { it.enabled }.take(50)
        require(active.isNotEmpty()) { "请先在设置中添加点播源" }
        val response = request("/api/search", "POST", searchPayload(keyword, active))
        if (response.optJSONArray("list") == null) throw LibreTvProtocolException("LibreTV 搜索响应缺少列表")
        SearchSnapshot(decodeSearchList(response.optJSONArray("list"), active),
            outcomes(response.optJSONArray("failures")), complete = true)
    }

    suspend fun search(keyword: String): List<MediaItem> = searchSnapshot(keyword).items

    /**
     * LibreTV sends one NDJSON 'source' event per completed origin, then one
     * 'done' event with authoritative order. Every intermediate emission is
     * usable, cancellation closes the network stream immediately.
     */
    fun searchProgressively(keyword: String): Flow<List<MediaItem>> = searchSnapshots(keyword).map { it.items }

    private fun readBoundedLine(reader: java.io.BufferedReader, budget: Int): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return line.toString().takeIf { it.isNotEmpty() }
            if (value == '\n'.code) return line.toString()
            if (line.length >= budget) throw LibreTvProtocolException("LibreTV 搜索响应过大")
            line.append(value.toChar())
        }
    }

    fun searchSnapshots(keyword: String): Flow<SearchSnapshot> = callbackFlow {
        val active = sources().filter { it.enabled }.take(50)
        require(active.isNotEmpty()) { "请先在设置中添加点播源" }
        val call = jsonClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build().newCall(
            Request.Builder().url(server + "/api/search?stream=1")
                .header("Accept", "application/x-ndjson")
                .apply { sessionCookie?.let { header("Cookie", it) } }
                .post(searchPayload(keyword, active).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
        val network = launch(Dispatchers.IO) {
          try {
            call.execute().use { response ->
                val status = response.code
                if (status !in 200..299) {
                    if (status == 401) sessionCookie = null
                    val message = response.body?.byteStream()?.bufferedReader()?.use { reader ->
                        val output = StringBuilder()
                        val buffer = CharArray(1024)
                        while (output.length < 8192) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, 8192 - output.length))
                            if (count < 0) break
                            output.append(buffer, 0, count)
                        }
                        output.toString()
                    }
                        ?.let { runCatching { JSONObject(it).optString("error") }.getOrDefault("") }.orEmpty()
                    throw LibreTvHttpException(status, message)
                }
                var accumulated = emptyList<MediaItem>()
                var completed = false
                var observed = emptyList<SourceSearchOutcome>()
                var receivedChars = 0
                requireNotNull(response.body) { "LibreTV 搜索响应为空" }.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        ensureActive()
                        val line = readBoundedLine(reader, 4_194_304 - receivedChars) ?: break
                        receivedChars += line.length + 1
                        if (receivedChars > 4_194_304) throw IOException("LibreTV 搜索响应过大")
                        if (line.isBlank()) continue
                        val event = JSONObject(line)
                        if (event.optJSONArray("list") == null) throw LibreTvProtocolException("LibreTV 搜索事件缺少列表")
                        val batch = decodeSearchList(event.optJSONArray("list"), active)
                        when (event.optString("type")) {
                            "source" -> {
                                accumulated = (accumulated + batch).distinctBy {
                                    (it.source?.key ?: "") + "/" + it.id
                                }
                                val outcome = outcomes(JSONArray().put(event)).single()
                                observed = observed.filterNot { it.sourceKey == outcome.sourceKey } + outcome
                                send(SearchSnapshot(accumulated, observed))
                            }
                            "done" -> {
                                completed = true
                                val failures = outcomes(event.optJSONArray("failures"))
                                observed = observed.filterNot { old -> failures.any { it.sourceKey == old.sourceKey } } +
                                    failures.map { failure -> failure.copy(ms = observed.firstOrNull { it.sourceKey == failure.sourceKey }?.ms) }
                                send(SearchSnapshot(batch, observed, complete = true))
                                break
                            }
                            else -> {
                                // Older proxies returned one ordinary JSON search response.
                                if (event.optString("type").isBlank() && event.has("list")) {
                                    completed = true
                                    send(SearchSnapshot(batch, outcomes(event.optJSONArray("failures")), complete = true))
                                    break
                                } else throw IOException("LibreTV 搜索事件类型无效")
                            }
                        }
                    }
                }
                if (!completed) throw IOException("LibreTV 搜索流未完成（缺少 done）")
                close()
            }
          } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
          } catch (error: Exception) { close(error) }
          finally { call.cancel() }
        }
        awaitClose { call.cancel(); network.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun detail(requested: MediaItem, onProgress: (ResolutionProgress) -> Unit = {}): MediaItem = withContext(Dispatchers.IO) {
        val item = requireNotNull(requested.usableMetadata()) { "目录授权已过期，请刷新后重试；本地记录仍保留。" }
        val detailed = if (item.source == null) {
            CatalogSourceResolver(::searchSnapshot) { entry -> fetchDetail(entry, item) }.resolve(item, onProgress)
        } else fetchDetail(item, item).also {
            require(SourceResolutionPolicy.matches(item, it)) { "播放详情与所选作品不匹配" }
            require(it.episodes.all(SourceResolutionPolicy::usableUrl)) { "未找到可用的 HTTP(S) 播放地址" }
        }
        try {
            library.cacheItem(detailed)
            persistenceWarning = ""
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { persistenceWarning = "本地片库暂不可写；播放仍可用，旧记录已保留。" }
        library.snapshot.applyProgress(detailed)
    }

    private suspend fun fetchDetail(entry: MediaItem, item: MediaItem): MediaItem {
        val src = requireNotNull(entry.source)
        val path = "/api/detail?id=${encode(entry.id)}&source=${encode(sourceJson(src).toString())}"
        val j = request(path)
        val info = j.optJSONObject("videoInfo") ?: JSONObject()
        val reportedKey = info.optString("sourceKey").takeUnless { it.isBlank() || it == "null" }
        val reportedUrl = info.optString("sourceUrl").takeUnless { it.isBlank() || it == "null" }
        if (!SourceResponseIdentity.matches(src, reportedKey, reportedUrl)) {
            throw LibreTvProtocolException("LibreTV 详情来源与请求不符")
        }
        val episodes = j.optJSONArray("episodes") ?: throw LibreTvProtocolException("LibreTV 详情响应缺少播放列表")
        if ((0 until episodes.length()).any { episodes.opt(it) !is String }) {
            throw LibreTvProtocolException("LibreTV 播放列表格式无效")
        }
        return entry.copy(
            title = info.optString("title").trim().takeUnless { it.isBlank() || it == "null" } ?: entry.title,
            isTv = mediaType(info) ?: if (entry.mediaTypeKnown) entry.isTv else item.isTv,
            mediaTypeKnown = mediaType(info) != null || item.mediaTypeKnown || entry.mediaTypeKnown,
            backdrop = entry.backdrop.ifBlank { item.backdrop },
            description = info.optString("desc"),
            poster = info.optString("cover").ifBlank { entry.poster },
            category = info.optString("typeName").ifBlank { entry.category },
            year = info.optString("year").ifBlank { entry.year },
            aliases = strings(info.opt("aliases")).ifEmpty { entry.aliases.ifEmpty { item.aliases } },
            actors = strings(info.opt("actor")).ifEmpty { strings(info.opt("actors")).ifEmpty {
                strings(info.opt("vod_actor")).ifEmpty { entry.actors.ifEmpty { item.actors } }
            } },
            episodeNames = info.optJSONArray("episodeNames")?.let { names ->
                (0 until names.length()).map { names.optString(it) }
            }.orEmpty(),
            episodeIds = info.optJSONArray("episodeIds")?.let { ids ->
                (0 until ids.length()).map { ids.optString(it) }
            }.orEmpty(),
            // A new authoritative playlist must not inherit a cached/catalog season. Unknown
            // season is meaningful to checkpoint quarantine, even when labels are reused.
            seasonNumber = info.optInt("season", 0).takeIf { it > 0 },
            // Title resolution is not an authoritative cross-provider ID mapping.
            metadataIds = entry.metadataIds,
            rating = item.rating.ifBlank { entry.rating }, ratingSource = item.ratingSource.ifBlank { entry.ratingSource },
            ratingLicenseExpiresAt = if (item.rating.isNotBlank()) item.ratingLicenseExpiresAt else entry.ratingLicenseExpiresAt,
            doubanTopRank = item.doubanTopRank ?: entry.doubanTopRank,
            catalogProvider = item.catalogProvider, catalogAttribution = item.catalogAttribution,
            catalogLicenseExpiresAt = item.catalogLicenseExpiresAt,
            episodes = (0 until episodes.length()).map { episodes.optString(it) },
            playlistKnown = true
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

    suspend fun saveProgress(item: MediaItem, positionMs: Long, episodeIndex: Int = 0, durationMs: Long = 0L) =
        library.saveProgress(item, positionMs, episodeIndex, durationMs)

    fun searchHistory(): List<String> = runCatching {
        val data = JSONArray(prefs.getString("search_history", "[]") ?: "[]")
        (0 until data.length()).mapNotNull { data.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    fun rememberSearch(keyword: String) {
        val term = keyword.trim().take(100)
        if (term.isBlank()) return
        val next = listOf(term) + searchHistory().filterNot { it.equals(term, ignoreCase = true) }
        prefs.edit().putString("search_history", JSONArray(next.take(25)).toString()).apply()
    }

    fun localItems(): List<MediaItem> = library.snapshot.records.mapNotNull { it.item.usableMetadata() }
    fun history(): List<MediaItem> = library.snapshot.history().mapNotNull { it.usableMetadata() }
    fun favorites(): List<MediaItem> = library.snapshot.favorites().mapNotNull { it.usableMetadata() }
    fun libraryStatus(item: MediaItem): LibraryStatus? = library.snapshot.status(item)
    fun isFavorite(item: MediaItem): Boolean = library.snapshot.isFavorite(item)
    fun libraryByStatus(status: LibraryStatus): List<MediaItem> = library.snapshot.byStatus(status).mapNotNull { it.usableMetadata() }
    suspend fun setLibraryStatus(item: MediaItem, status: LibraryStatus?) = library.setStatus(item, status)
    suspend fun toggleFavorite(item: MediaItem) = library.toggleFavorite(item)
    suspend fun setFavorite(item: MediaItem, favorite: Boolean) = library.setFavorite(item, favorite)
}
