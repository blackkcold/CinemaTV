package dev.cinematv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cinematv.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.shared.presentationKey

enum class Page { HOME, EXPLORE, MOVIES, SERIES, SEARCH, LIVE, LIBRARY, SETTINGS, DETAIL, PLAYER }

class CinemaViewModel(application: Application) : AndroidViewModel(application) {
    val repo = LibreTvRepository(application)
    val focusCoordinator = FocusCoordinator()
    var reducedMotion by mutableStateOf(repo.reducedMotion)
        private set
    var glassQuality by mutableStateOf(runCatching { GlassQuality.valueOf(repo.glassQuality) }.getOrDefault(GlassQuality.AUTO))
        private set

    fun updateReducedMotion(enabled: Boolean) { repo.setReducedMotion(enabled); reducedMotion = enabled }
    fun updateGlassQuality(quality: GlassQuality) {
        repo.setGlassQuality(quality.name)
        glassQuality = quality
        setOpticalGlass(quality == GlassQuality.ENHANCED)
    }
    var opticalGlassEnabled by mutableStateOf(repo.opticalGlassEnabled)
        private set

    fun setOpticalGlass(enabled: Boolean) {
        repo.setOpticalGlass(enabled)
        opticalGlassEnabled = enabled
    }
    var page by mutableStateOf(if (repo.isConnected) Page.HOME else Page.SETTINGS)
        private set
    var section by mutableStateOf(Page.HOME)
        private set
    private var detailSessionKey by mutableStateOf("")
    val focusRoute: String get() = if (page == Page.DETAIL) "DETAIL:$detailSessionKey" else page.name
    var featured by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var tvShows by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var results by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var focused by mutableStateOf<MediaItem?>(null)
        private set
    var selected by mutableStateOf<MediaItem?>(null)
        private set
    var playbackUrl by mutableStateOf("")
        private set
    var playbackTitle by mutableStateOf("")
        private set
    var playbackPosition by mutableStateOf(0L)
        private set
    var playerReturnPage by mutableStateOf(Page.DETAIL)
        private set
    var query by mutableStateOf("")
    var searchHistory by mutableStateOf(repo.searchHistory())
        private set
    var serverAddress by mutableStateOf(repo.server)
    var metadataAddress by mutableStateOf(repo.metadataServer)
    var metadataAccessToken by mutableStateOf("")
    var password by mutableStateOf("")
    var passwordRequired by mutableStateOf(false)
        private set
    var authorized by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var sourceName by mutableStateOf("")
    var sourceAddress by mutableStateOf("")
    var liveName by mutableStateOf("")
    var liveAddress by mutableStateOf("")
    var liveEpg by mutableStateOf("")
    var sources by mutableStateOf<List<TvSource>>(emptyList())
        private set
    var sourceProbes by mutableStateOf<Map<String, SourceProbe>>(emptyMap())
        private set
    var liveSources by mutableStateOf<List<LiveSource>>(emptyList())
        private set
    var channels by mutableStateOf<List<TvChannel>>(emptyList())
        private set
    var activeLiveSource by mutableStateOf<LiveSource?>(null)
        private set
    var libraryVersion by mutableStateOf(0)
        private set
    var alternatives by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var activeChannel by mutableStateOf<TvChannel?>(null)
        private set
    var epgPrograms by mutableStateOf<List<TvProgram>>(emptyList())
        private set
    private var epgJob: Job? = null
    private var searchJob: Job? = null
    private var backdropJob: Job? = null
    private var detailJob: Job? = null
    private var detailGeneration = 0L
    var selectedRecommendationReason by mutableStateOf("")
        private set
    private var channelsJob: Job? = null
    private var homeJob: Job? = null
    private var catalogMovies by mutableStateOf<List<MediaItem>>(emptyList())
    private var catalogSeries by mutableStateOf<List<MediaItem>>(emptyList())
    val browseMovies get() = catalogMovies.ifEmpty { featured }.mapNotNull { it.usableMetadata() }
    val browseSeries get() = catalogSeries.ifEmpty { tvShows }.mapNotNull { it.usableMetadata() }
    private var remoteSearch: List<MediaItem> = emptyList()
    private var queryCatalog: List<MediaItem> = emptyList()
    var searchFilters by mutableStateOf(SearchFilters())
        private set
    var searchStage by mutableStateOf("")
        private set
    var catalogAttribution by mutableStateOf("")
        private set
    var recommendationReasons by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    private var completedSearchQuery = ""
    private var searchGeneration = 0L
    val searchYears: List<String> get() = (catalogMovies + catalogSeries + queryCatalog + remoteSearch +
        repo.library.snapshot.records.map { it.item }).map { it.year }.filter { it.matches(Regex("\\d{4}")) }.distinct().sortedDescending()

    fun cycleSearchYear() {
        val choices = listOf<String?>(null) + searchYears
        updateSearchFilters(searchFilters.copy(year = choices[(choices.indexOf(searchFilters.year) + 1) % choices.size]))
    }

    var failoverBusy by mutableStateOf(false)
        private set
    var failoverMessage by mutableStateOf("")
        private set
    private val failedSources = mutableSetOf<String>()
    var playingEpisode by mutableStateOf(0)
        private set

    init {
        viewModelScope.launch {
            repo.library.snapshots.collect {
                libraryVersion++
                rebuildRecommendations()
                if (completedSearchQuery.isNotBlank()) rebuildSearch()
            }
        }
        viewModelScope.launch {
            runCatching { repo.library.initialize() }
                .onFailure { message = "片库迁移未完成，旧记录已保留：${it.message}" }
            if (repo.isConnected) refreshConnection() else rebuildRecommendations()
        }
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val expiry = (catalogMovies + catalogSeries + queryCatalog + repo.localItems() + listOfNotNull(selected))
                    .map { it.catalogLicenseExpiresAt }.filter { it > now }.minOrNull()
                delay(((expiry ?: now + 60000) - now + 1).coerceIn(1000, 60000))
                rebuildRecommendations()
                if (completedSearchQuery.isNotBlank()) rebuildSearch()
                focused = focused?.usableMetadata()
                selected?.takeIf { it.catalogLicenseExpiresAt > 0 && it.catalogLicenseExpiresAt <= System.currentTimeMillis() }?.let {
                    selected = it.usableMetadata() ?: it.copy(title = "目录授权已过期", poster = "", backdrop = "", description = "本地片库记录已保留，请刷新元数据目录。", rating = "", aliases = emptyList(), actors = emptyList())
                }
                libraryVersion++
            }
        }
    }

    fun dismissMessage() { message = "" }

    private fun connectionError(error: Throwable) {
        message = ConnectionErrors.message(error)
        if (ConnectionErrors.classify(error) == ConnectionFailure.AUTHENTICATION) {
            authorized = false
            passwordRequired = true
            if (page != Page.PLAYER) page = Page.SETTINGS
        }
    }

    fun refreshConnection() {
        viewModelScope.launch {
            busy = true
            runCatching { repo.status() }
                .onSuccess { status ->
                    passwordRequired = status.passwordRequired
                    authorized = !status.passwordRequired || status.verified
                    sources = status.sources
                    liveSources = status.liveSources
                    message = if (authorized) "" else "此服务器要求密码，请在设置中登录。"
                    if (authorized) loadHome()
                }.onFailure { authorized = false; connectionError(it) }
            busy = false
        }
    }

    fun connect() {
        runCatching {
            repo.configureServer(serverAddress)
            page = Page.SETTINGS
            refreshConnection()
        }.onFailure { message = it.message ?: "服务器地址无效" }
    }

    fun configureMetadata() {
        runCatching { repo.configureMetadata(metadataAddress, metadataAccessToken) }
            .onSuccess {
                metadataAccessToken = ""
                message = "元数据服务配置已保存（令牌仅保存在本次应用进程）"
                if (authorized) loadHome()
            }
            .onFailure { message = it.message ?: "元数据地址配置失败" }
    }

    fun login() {
        viewModelScope.launch {
            busy = true
            runCatching { repo.login(password) }.onSuccess {
                password = ""
                authorized = it
                if (it) { message = "连接成功"; refreshConnection() }
                else message = "密码错误"
            }.onFailure { connectionError(it) }
            busy = false
        }
    }

    fun addSource(live: Boolean = false) {
        runCatching {
            if (live) {
                repo.addLiveSource(liveName.ifBlank { "自定义直播" }, liveAddress, liveEpg)
                liveName = ""; liveAddress = ""; liveEpg = ""
            } else {
                repo.addSource(sourceName.ifBlank { "自定义片源" }, sourceAddress)
                sourceName = ""; sourceAddress = ""
            }
            sources = repo.sources()
            liveSources = repo.liveSources()
            message = "添加成功"
        }.onFailure { message = it.message ?: "添加失败" }
    }

    fun toggleSource(source: TvSource) {
        repo.setSourceEnabled(source.url, !source.enabled)
        sources = repo.sources()
    }

    fun probeSource(source: TvSource) {
        viewModelScope.launch {
            sourceProbes = sourceProbes + (source.url to SourceProbe(false, 0, "检测中…"))
            runCatching { repo.probeSource(source) }
                .onSuccess { sourceProbes = sourceProbes + (source.url to it) }
                .onFailure { sourceProbes = sourceProbes + (
                    source.url to SourceProbe(false, 0, it.message ?: "无法连接")
                ) }
        }
    }

    fun removeSource(url: String, live: Boolean = false) {
        repo.removeSource(url, live)
        sources = repo.sources()
        liveSources = repo.liveSources()
    }

    private fun rebuildRecommendations() {
        val records = repo.library.snapshot.records
        val signals = records.filter { it.status != null || it.favorite || it.historyAt != null }.map {
            RecommendationSignal(it.item, it.status ?: if (it.historyAt != null) LibraryStatus.WATCHING else null,
                favorite = it.favorite, notInterested = it.status == LibraryStatus.DROPPED)
        }
        val offline = repo.localItems()
        val movies = RecommendationEngine.recommend(catalogMovies.ifEmpty { offline.filter { !it.isTv } }, signals, 50)
        val series = RecommendationEngine.recommend(catalogSeries.ifEmpty { offline.filter { it.isTv } }, signals, 50)
        featured = movies.map { it.item }
        tvShows = series.map { it.item }
        recommendationReasons = (movies + series).associate { it.item.presentationKey to it.reason }
        catalogAttribution = (featured + tvShows + queryCatalog).mapNotNull { it.usableMetadata() }
            .map { it.catalogAttribution }.filter(String::isNotBlank).distinct().joinToString("\n")
    }

    private fun loadHome() {
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            suspend fun load(tv: Boolean): List<MediaItem>? {
                val catalog = try { repo.catalog(isTv = tv) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { MetadataCatalog() }
                if (catalog.available && catalog.items.isNotEmpty()) {
                    return catalog.items
                }
                return try { repo.recommend(tv) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
            }
            val movies = load(false)
            ensureActive()
            if (movies != null) catalogMovies = movies
            rebuildRecommendations()
            if (focused == null) focused = featured.firstOrNull()
            val series = load(true)
            ensureActive()
            if (series != null) catalogSeries = series
            rebuildRecommendations()
            if (movies == null && series == null) message = "推荐目录暂不可用，已保留本地片库与搜索。"
            featured.firstOrNull()?.let { first ->
                try {
                    val enhanced = repo.enrichMetadata(first)
                    ensureActive()
                    catalogMovies = catalogMovies.map { if (it.presentationKey == first.presentationKey) enhanced else it }
                    rebuildRecommendations()
                    if (focused?.presentationKey == first.presentationKey) focused = enhanced
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Keep local/source artwork. */ }
            }
        }
    }

    fun navigate(destination: Page) {
        if (destination == Page.PLAYER || destination == Page.DETAIL) return
        page = destination
        section = destination
        when (destination) {
            Page.HOME, Page.EXPLORE, Page.MOVIES, Page.SERIES -> {
                if (featured.isEmpty() && authorized) loadHome()
            }
            Page.LIVE -> if (channels.isEmpty() && liveSources.isNotEmpty()) loadChannels(liveSources.first())
            Page.LIBRARY -> libraryVersion++
            else -> Unit
        }
    }

    fun onMediaFocus(item: MediaItem, region: String = "general") {
        val route = page
        focusCoordinator.remember(route.name, region, item.presentationKey)
        backdropJob?.cancel()
        backdropJob = viewModelScope.launch {
            delay(if (reducedMotion) 0 else 210)
            if (page != route) return@launch
            focused = item
            if (item.backdrop.isBlank()) {
                runCatching { repo.enrichMetadata(item) }.onSuccess { enriched ->
                    if (page == route && focused?.presentationKey == item.presentationKey) {
                        focused = enriched
                        featured = featured.map { if (it.presentationKey == enriched.presentationKey) enriched else it }
                        tvShows = tvShows.map { if (it.presentationKey == enriched.presentationKey) enriched else it }
                    }
                }
            }
        }
    }

    fun open(item: MediaItem) {
        backdropJob?.cancel()
        detailJob?.cancel()
        val generation = ++detailGeneration
        selectedRecommendationReason = recommendationReasons[item.presentationKey].orEmpty()
        detailSessionKey = item.presentationKey
        page = Page.DETAIL
        selected = item
        alternatives = emptyList()
        busy = true
        detailJob = viewModelScope.launch {
            try {
                val detailed = repo.detail(item)
                if (page != Page.DETAIL || detailGeneration != generation) return@launch
                selected = detailed
                if (repo.persistenceWarning.isNotBlank()) message = repo.persistenceWarning
                busy = false
                runCatching { repo.enrichMetadata(detailed) }.onSuccess { enhanced ->
                    if (selected?.presentationKey == detailed.presentationKey && page == Page.DETAIL) selected = enhanced
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                connectionError(error)
            } finally { if (detailGeneration == generation) busy = false }
        }
    }

    fun findAlternatives() {
        val item = selected ?: return
        viewModelScope.launch {
            busy = true
            runCatching { repo.search(item.title) }
                .onSuccess { items ->
                    if (selected?.presentationKey != item.presentationKey) return@onSuccess
                    alternatives = items.filter { sameSourceCandidate(item, it) }
                        .distinctBy { it.source?.url }
                }
                .onFailure { message = "换源失败：${it.message}" }
            busy = false
        }
    }

    private fun sameSourceCandidate(item: MediaItem, candidate: MediaItem): Boolean {
        if (item.source?.url == candidate.source?.url && item.id == candidate.id) return true
        if (item.year.isBlank() || candidate.year.isBlank() || item.year != candidate.year) return false
        return MediaIdentity.normalizedTitle(item.title) == MediaIdentity.normalizedTitle(candidate.title) &&
            (!item.mediaTypeKnown || !candidate.mediaTypeKnown || item.isTv == candidate.isTv) &&
            (item.seasonNumber == null || candidate.seasonNumber == null || item.seasonNumber == candidate.seasonNumber)
    }

    fun focusChannel(channel: TvChannel) {
        if (activeChannel?.id == channel.id) return
        activeChannel = channel
        epgPrograms = emptyList()
        epgJob?.cancel()
        val source = activeLiveSource ?: return
        epgJob = viewModelScope.launch {
            delay(350)
            runCatching { repo.epg(source, channel) }.onSuccess { epgPrograms = it }
        }
    }

    fun updateSearchFilters(filters: SearchFilters) {
        searchFilters = filters
        rebuildSearch()
    }

    private fun rebuildSearch() {
        searchHits = SearchIndex.search(completedSearchQuery,
            library = repo.localItems(),
            catalog = catalogMovies + catalogSeries + queryCatalog,
            remote = remoteSearch, filters = searchFilters)
        results = searchHits.map { it.item }
        catalogAttribution = (featured + tvShows + results).map { it.catalogAttribution }
            .filter(String::isNotBlank).distinct().joinToString("\n")
    }

    fun search() {
        val wd = query.trim()
        if (wd.isBlank()) return
        navigate(Page.SEARCH)
        searchJob?.cancel()
        val generation = ++searchGeneration
        completedSearchQuery = wd
        remoteSearch = emptyList()
        queryCatalog = emptyList()
        message = ""
        repo.rememberSearch(wd)
        searchHistory = repo.searchHistory()
        rebuildSearch() // Local matches appear immediately, including while offline.
        searchStage = "本地片库与目录"
        searchJob = viewModelScope.launch {
            busy = true
            try {
                coroutineScope {
                    launch {
                        if (repo.hasMetadataCatalog) {
                            for (tv in listOf(false, true)) {
                                try {
                                    val catalog = repo.catalog(wd, tv)
                                    ensureActive()
                                    queryCatalog = queryCatalog + catalog.items
                                    rebuildSearch()
                                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Independent provider. */ }
                            }
                        }
                    }
                    launch {
                        if (!repo.isConnected || sources.none { it.enabled }) return@launch
                        searchStage = "本地 → 元数据目录 → LibreTV 片源"
                        try {
                            repo.searchProgressively(wd).collect { partial ->
                                ensureActive()
                                remoteSearch = partial
                                rebuildSearch()
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            try {
                                val fallback = repo.search(wd)
                                ensureActive()
                                remoteSearch = fallback
                                rebuildSearch()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { searchStage = "片源暂不可用 · 已保留本地和目录结果" }
                        }
                    }
                }
                if (results.isEmpty()) message = "没有符合当前关键词和筛选条件的作品"
            } finally {
                // A cancelled older search must not reset the newer search's loading state.
                if (searchGeneration == generation) busy = false
            }
        }
    }

    fun episodeProgress(index: Int) = selected?.let { repo.library.snapshot.progress(it, index) }

    fun episodeLabel(index: Int): String {
        val item = selected ?: return "第 ${index + 1} 集"
        val name = item.episodeNames.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: if (item.episodes.size == 1) "播放" else "第 ${index + 1} 集"
        val progress = episodeProgress(index) ?: return name
        return when {
            progress.durationMs > 0 && progress.positionMs.toDouble() / progress.durationMs >= .95 -> "$name ✓"
            progress.positionMs > 0 -> "$name · ${progress.positionMs / 60000}分"
            else -> name
        }
    }

    fun playEpisode(index: Int = 0) {
        val item = selected ?: return
        val url = item.episodes.getOrNull(index)
        if (url == null || !url.startsWith("http")) {
            message = "没有可播放的直链；请检查片源与剧集"
            return
        }
        playerReturnPage = Page.DETAIL
        if (repo.libraryStatus(item) == null) viewModelScope.launch {
            runCatching { repo.setLibraryStatus(item, LibraryStatus.WATCHING) }
                .onFailure { message = "片库保存失败：${it.message}" }
        }
        playbackUrl = url
        playbackTitle = item.title + if (item.episodes.size > 1) " · 第${index + 1}集" else ""
        val progress = repo.library.snapshot.progress(item, index)
        playbackPosition = progress?.positionMs ?: 0L
        playingEpisode = index
        failedSources.clear()
        item.source?.url?.let { failedSources.add(it) }
        failoverBusy = false
        failoverMessage = ""
        page = Page.PLAYER
    }

    fun playChannel(channel: TvChannel) {
        playerReturnPage = Page.LIVE
        failoverBusy = false
        failoverMessage = ""
        playbackUrl = channel.url
        playbackTitle = channel.name
        playbackPosition = 0L
        page = Page.PLAYER
    }

    /**
     * Fail over only to the exact same title and, when known, the same year.
     * Preserve episode number and position; never switch across mismatched episodes.
     * At most 2 alternative origins per playback session.
     */
    fun recoverPlayback(cause: String, lastPosition: Long) {
        val current = selected
        if (playerReturnPage != Page.DETAIL || current == null || failedSources.size >= 3) {
            failoverMessage = cause
            return
        }
        if (failoverBusy) return
        failoverBusy = true
        failoverMessage = "播放失败，正在搜索同名备用片源..."
        viewModelScope.launch {
            runCatching {
                val alternatives = repo.search(current.title)
                    .filter { candidate ->
                        sameSourceCandidate(current, candidate) &&
                        candidate.source != null && candidate.source.url !in failedSources
                    }.take(12)
                var found: MediaItem? = null
                for (candidate in alternatives) {
                    val sourceUrl = candidate.source?.url ?: continue
                    failedSources.add(sourceUrl)
                    try {
                        val detailed = repo.detail(candidate)
                        val labelsMatch = current.episodeNames.getOrNull(playingEpisode)?.takeIf(String::isNotBlank)?.let { label ->
                            detailed.episodeNames.getOrNull(playingEpisode)?.let { MediaIdentity.normalizedTitle(it) == MediaIdentity.normalizedTitle(label) }
                        } ?: (detailed.episodes.size == current.episodes.size)
                        if (sameSourceCandidate(current, detailed) && labelsMatch && detailed.episodes.getOrNull(playingEpisode)?.startsWith("http") == true) {
                            found = detailed
                            break
                        }
                    } catch (_: Exception) { /* independently failed source */ }
                    if (failedSources.size >= 3) break
                }
                found ?: throw IllegalStateException("未找到同作品、同集数的备用片源")
            }.onSuccess { resolved ->
                selected = resolved
                playbackPosition = lastPosition.coerceAtLeast(0)
                val newUrl = resolved.episodes[playingEpisode]
                if (newUrl == playbackUrl) {
                    failoverMessage = cause
                } else {
                    playbackUrl = newUrl
                    failoverMessage = "已切换备用片源：${resolved.source?.name ?: "未知"}"
                }
            }.onFailure { failoverMessage = "无法自动换源：${it.message ?: cause}" }
            failoverBusy = false
        }
    }

    fun savePlaybackPosition(position: Long, episodeIndex: Int = playingEpisode, durationMs: Long = 0L) {
        val item = selected ?: return
        if (playerReturnPage != Page.DETAIL) return
        viewModelScope.launch {
            runCatching { repo.saveProgress(item, position, episodeIndex, durationMs) }
                .onSuccess {
                    if (episodeIndex == playingEpisode && selected?.presentationKey == item.presentationKey) {
                        selected = repo.library.snapshot.applyProgress(item)
                    }
                }.onFailure { message = "观看进度未能保存：${it.message}" }
        }
    }

    fun playerEnded(position: Long = 0, duration: Long = 0, episodeIndex: Int = playingEpisode) {
        val entry = selected ?: return
        if (playerReturnPage == Page.DETAIL && episodeIndex == playingEpisode) {
            val endingUrl = playbackUrl
            viewModelScope.launch {
                runCatching { repo.saveProgress(entry, if (duration > 0) duration else position, episodeIndex, duration) }
                    .onFailure { message = "最终观看进度未能保存：${it.message}" }
                if (page != Page.PLAYER || playbackUrl != endingUrl || playingEpisode != episodeIndex) return@launch
                if (episodeIndex + 1 < entry.episodes.size) playEpisode(episodeIndex + 1)
                else runCatching { repo.setLibraryStatus(entry, LibraryStatus.WATCHED) }
                    .onFailure { message = "片库保存失败：${it.message}" }
            }
        }
    }

    fun loadChannels(source: LiveSource) {
        channelsJob?.cancel()
        epgJob?.cancel()
        activeLiveSource = source
        activeChannel = null
        channels = emptyList()
        epgPrograms = emptyList()
        channelsJob = viewModelScope.launch {
            busy = true
            try {
                val loaded = repo.channels(source)
                if (activeLiveSource?.url == source.url) channels = loaded
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) { connectionError(error) }
            finally { busy = false }
        }
    }

    fun toggleFavorite() {
        val item = selected ?: return
        viewModelScope.launch {
            runCatching { repo.toggleFavorite(item) }.onSuccess { message = "收藏已更新" }
                .onFailure { message = "收藏未能保存：${it.message}" }
        }
    }

    fun setLibraryStatus(status: LibraryStatus) {
        val item = selected ?: return
        viewModelScope.launch {
            runCatching { repo.setLibraryStatus(item, status) }
                .onSuccess { message = "已加入：${status.title}" }
                .onFailure { message = "片库保存失败：${it.message}" }
        }
    }

    fun libraryByStatus(status: LibraryStatus): List<MediaItem> = repo.libraryByStatus(status)

    fun favorites() = repo.favorites()
    fun history() = repo.history()

    fun back() {
        when (page) {
            Page.PLAYER -> page = playerReturnPage
            Page.DETAIL -> page = section
            Page.HOME -> Unit
            else -> navigate(Page.HOME)
        }
    }
}
