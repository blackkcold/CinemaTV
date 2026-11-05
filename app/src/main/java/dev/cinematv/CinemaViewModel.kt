package dev.cinematv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cinematv.data.*
import dev.cinematv.data.recommendation.LocalRecommendationPipeline
import dev.cinematv.data.recommendation.LocalRecommendationSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.shared.WorkNavigationProjection
import dev.cinematv.playback.PlaybackSession
import dev.cinematv.playback.PlaybackRecoveryPolicy
import dev.cinematv.playback.PlaybackSessionGuard

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
    var resolutionStatus by mutableStateOf("")
        private set
    var selectedRecommendationReason by mutableStateOf("")
        private set
    private var channelsJob: Job? = null
    private var homeJob: Job? = null
    private var catalogMovies by mutableStateOf<List<MediaItem>>(emptyList())
    private var catalogSeries by mutableStateOf<List<MediaItem>>(emptyList())
    val browseMovies get() = catalogMovies.ifEmpty { featured }.mapNotNull { it.usableMetadata() }
    val browseSeries get() = catalogSeries.ifEmpty { tvShows }.mapNotNull { it.usableMetadata() }
    var movieBrowse by mutableStateOf(CatalogBrowseState())
        private set
    var seriesBrowse by mutableStateOf(CatalogBrowseState())
        private set
    var movieSearchBrowse by mutableStateOf(CatalogBrowseState())
        private set
    var seriesSearchBrowse by mutableStateOf(CatalogBrowseState())
        private set
    private val browseJobs = mutableMapOf<Boolean, Job>()
    private var searchMoreJob: Job? = null
    private var browseGeneration = 0L
    fun browseState(series: Boolean) = if (series) seriesBrowse else movieBrowse
    val hasSearchCatalog get() = movieSearchBrowse.pages > 0 || seriesSearchBrowse.pages > 0
    val searchCatalogLoading get() = movieSearchBrowse.loading || seriesSearchBrowse.loading
    val canLoadSearchCatalog get() = !searchFilters.doubanTopOnly && completedSearchQuery.isNotBlank() &&
        listOf(false, true).any { (searchFilters.isTv == null || searchFilters.isTv == it) && searchBrowseState(it).canLoadMore }
    private fun searchBrowseState(series: Boolean) = if (series) seriesSearchBrowse else movieSearchBrowse

    fun refreshCatalog() = loadHome()

    fun loadMoreCatalog(series: Boolean) {
        if (!repo.hasMetadataCatalog || !browseState(series).canLoadMore) return
        val generation = browseGeneration
        browseJobs[series] = viewModelScope.launch {
            fetchBrowsePage(series, false) { generation == browseGeneration }
        }
    }

    fun loadMoreSearchCatalog() {
        if (!canLoadSearchCatalog || searchCatalogLoading) return
        val generation = searchGeneration
        searchMoreJob = viewModelScope.launch {
            for (tv in listOf(false, true)) {
                if (searchFilters.doubanTopOnly || generation != searchGeneration) break
                if (searchFilters.isTv == null || searchFilters.isTv == tv)
                    fetchBrowsePage(tv, true) { generation == searchGeneration }
            }
        }
    }

    private suspend fun fetchBrowsePage(series: Boolean, search: Boolean, current: () -> Boolean) {
        fun state() = if (search) searchBrowseState(series) else browseState(series)
        val initial = state()
        if (!initial.canLoadMore || !current()) return
        val offset = initial.nextOffset ?: return
        fun publish(value: CatalogBrowseState) {
            if (!current()) return
            if (search) {
                if (series) seriesSearchBrowse = value else movieSearchBrowse = value
                queryCatalog = movieSearchBrowse.items + seriesSearchBrowse.items
                rebuildSearch()
            } else {
                if (series) { seriesBrowse = value; catalogSeries = value.items }
                else { movieBrowse = value; catalogMovies = value.items }
                rebuildRecommendations()
            }
        }
        publish(initial.copy(loading = true, notice = ""))
        try {
            val response = repo.catalog(if (search) completedSearchQuery else "", series, offset)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (current()) publish(CatalogBrowsePaging.append(initial, response, offset))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(initial.copy(loading = false, notice = "目录加载失败，已保留结果；可重试。")) }
        finally { if (current() && state().loading) publish(state().copy(loading = false)) }
    }

    private var remoteSearch: List<MediaItem> = emptyList()
    private var queryCatalog: List<MediaItem> = emptyList()
    var searchFilters by mutableStateOf(SearchFilters())
        private set
    var searchStage by mutableStateOf("")
        private set
    var catalogAttribution by mutableStateOf("")
        private set
    var catalogStatus by mutableStateOf("")
        private set
    private var movieChart = CatalogChartSnapshot()
    private var seriesChart = CatalogChartSnapshot()
    private var chartNotice = ""
    var recommendationSnapshots by mutableStateOf<List<Pair<String, LocalRecommendationSnapshot>>>(emptyList())
        private set
    private var recommendationJob: Job? = null
    var recommendationReasons by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    private var completedSearchQuery = ""
    private var searchGeneration = 0L
    private var chartJob: Job? = null
    private var chartGeneration = 0L
    var chartLoading by mutableStateOf(false)
        private set
    val searchYears: List<String> get() = (catalogMovies + catalogSeries + queryCatalog + movieChart.items + seriesChart.items + remoteSearch +
        repo.library.snapshot.records.map { it.item }).map { it.year }.filter { it.matches(Regex("\\d{4}")) }.distinct().sortedDescending()

    fun cycleSearchYear() {
        val choices = listOf<String?>(null) + searchYears
        updateSearchFilters(searchFilters.copy(year = choices[(choices.indexOf(searchFilters.year) + 1) % choices.size]))
    }

    var failoverBusy by mutableStateOf(false)
        private set
    var failoverMessage by mutableStateOf("")
        private set
    private val playbackSessions = PlaybackSessionGuard()
    private var playbackRecoveryJob: Job? = null
    var playbackSession by mutableStateOf<PlaybackSession?>(null)
        private set
    var playingEpisode by mutableStateOf(0)
        private set

    init {
        viewModelScope.launch {
            repo.library.snapshots.collect {
                libraryVersion++
                rebuildRecommendations()
                if (completedSearchQuery.isNotBlank() || searchFilters.doubanTopOnly) rebuildSearch()
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
                val expiry = (catalogMovies + catalogSeries + queryCatalog + movieChart.items + seriesChart.items +
                    repo.localItems() + listOfNotNull(selected))
                    .map { it.catalogLicenseExpiresAt }.filter { it > now }.minOrNull()
                delay(((expiry ?: now + 60000) - now + 1).coerceIn(1000, 60000))
                rebuildRecommendations()
                if (completedSearchQuery.isNotBlank() || searchFilters.doubanTopOnly) rebuildSearch()
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
                    authorized = status.verified
                    sources = status.sources
                    liveSources = status.liveSources
                    message = if (authorized) "" else if (status.passwordRequired) "此服务器要求密码，请在设置中登录。" else "服务器未配置访问密码（PASSWORD），请联系管理员完成配置。"
                    if (authorized) loadHome()
                }.onFailure { authorized = false; connectionError(it) }
            busy = false
        }
    }

    fun connect() {
        runCatching {
            repo.configureServer(serverAddress)
            invalidatePlayback()
            page = Page.SETTINGS
            refreshConnection()
        }.onFailure { message = it.message ?: "服务器地址无效" }
    }

    fun configureMetadata() {
        runCatching { repo.configureMetadata(metadataAddress, metadataAccessToken) }
            .onSuccess {
                searchJob?.cancel()
                searchMoreJob?.cancel()
                chartJob?.cancel()
                searchGeneration++
                chartGeneration++
                chartLoading = false
                busy = false
                queryCatalog = emptyList()
                movieSearchBrowse = CatalogBrowseState(nextOffset = null)
                seriesSearchBrowse = CatalogBrowseState(nextOffset = null)
                movieChart = CatalogChartSnapshot()
                seriesChart = CatalogChartSnapshot()
                rebuildSearch()
                metadataAccessToken = ""
                message = "元数据服务配置已保存（令牌仅保存在本次应用进程）；请重新搜索以更新结果"
                loadHome()
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
        recommendationJob?.cancel()
        val records = repo.library.snapshot.records.toList()
        val signals = records.filter { it.status != null || it.favorite || it.historyAt != null }.map {
            RecommendationSignal(it.item, it.status,
                favorite = it.favorite, notInterested = it.status == LibraryStatus.DROPPED)
        }
        val offline = repo.localItems()
        val movieInput = catalogMovies.ifEmpty { offline.filter { !it.isTv } }.toList()
        val seriesInput = catalogSeries.ifEmpty { offline.filter { it.isTv } }.toList()
        recommendationJob = viewModelScope.launch {
            // Coalesce Room/catalog updates; ranking must never block D-pad or playback input.
            delay(50)
            val now = System.currentTimeMillis()
            val snapshots = withContext(Dispatchers.Default) {
                val checkCancelled: () -> Unit = { ensureActive() }
                val movies = LocalRecommendationPipeline.rank(movieInput, signals, 50, now, checkCancelled = checkCancelled)
                ensureActive()
                val series = LocalRecommendationPipeline.rank(seriesInput, signals, 50, now, checkCancelled = checkCancelled)
                listOf("电影" to movies, "剧集" to series)
            }
            ensureActive()
            val movies = snapshots[0].second.rows
            val series = snapshots[1].second.rows
            featured = movies.map { it.item }
            tvShows = series.map { it.item }
            recommendationReasons = (movies + series).associate { it.item.presentationKey to it.reason }
            // Publish the same immutable snapshots used for the shelves, not a diagnostics re-run.
            recommendationSnapshots = snapshots
            catalogAttribution = (featured + tvShows + queryCatalog).mapNotNull { it.usableMetadata() }
                .map { it.catalogAttribution }.filter(String::isNotBlank).distinct().joinToString("\n")
        }
    }

    private fun loadHome() {
        homeJob?.cancel()
        browseJobs.values.forEach { it.cancel() }
        val generation = ++browseGeneration
        movieBrowse = CatalogBrowseState()
        seriesBrowse = CatalogBrowseState()
        catalogMovies = emptyList()
        catalogSeries = emptyList()
        homeJob = viewModelScope.launch {
            for (tv in listOf(false, true)) {
                if (repo.hasMetadataCatalog) fetchBrowsePage(tv, false) { generation == browseGeneration }
                ensureActive()
                if (generation != browseGeneration) return@launch
                if (!repo.hasMetadataCatalog) {
                    val state = browseState(tv).copy(nextOffset = null,
                        notice = "元数据目录未配置，当前使用本地记录；可搜索 LibreTV 获取更多内容。")
                    if (tv) seriesBrowse = state else movieBrowse = state
                } else if (browseState(tv).catalog == null) {
                    // Keep the catalog's error/license notice and retry policy. The
                    // local ranker supplies saved records without another network source.
                    val state = browseState(tv).copy(notice = browseState(tv).notice +
                        " 当前使用本地记录；可搜索 LibreTV 获取更多内容。")
                    if (tv) seriesBrowse = state else movieBrowse = state
                }
                rebuildRecommendations()
            }
            // A recommendation build is asynchronous. Wait for the latest coalesced build,
            // including any replacement caused by a concurrent Room/catalog update.
            while (true) {
                val latest = recommendationJob ?: break
                latest.join()
                ensureActive()
                if (latest === recommendationJob) break
            }
            if (generation != browseGeneration) return@launch
            if (focused == null) focused = featured.firstOrNull()
            featured.firstOrNull()?.let { first ->
                try {
                    val enhanced = repo.enrichMetadata(first)
                    ensureActive()
                    if (generation != browseGeneration) return@launch
                    catalogMovies = catalogMovies.map { if (it.presentationKey == first.presentationKey) enhanced else it }
                    movieBrowse = movieBrowse.copy(items = movieBrowse.items.map { if (it.presentationKey == first.presentationKey) enhanced else it })
                    rebuildRecommendations()
                    if (focused?.presentationKey == first.presentationKey) focused = enhanced
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep source artwork. */ }
            }
        }
    }

    fun navigate(destination: Page) {
        if (destination == Page.PLAYER || destination == Page.DETAIL) return
        invalidatePlayback()
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

    val workAssociations get() = repo.library.snapshot.associations
    val associationCandidates get() = repo.library.snapshot.records.mapNotNull { it.item.usableMetadata() }

    fun associateWork(source: MediaItem, target: MediaItem, season: Int?, revision: String,
        reviewedMembers: List<MediaItem>) {
        if (page != Page.DETAIL || selected?.let(MediaDomainMapper::entryId) != MediaDomainMapper.entryId(source)) return
        viewModelScope.launch {
            try {
                repo.library.associateWork(source, target, season, revision, reviewedMembers)
                message = "已手动关联；原始片源与观看进度保持不变"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "关联未保存，资料或关联已变化，请重新查看后确认" }
        }
    }

    fun unlinkWork(sourceId: String, revision: String) {
        if (page != Page.DETAIL || selected?.let(MediaDomainMapper::entryId) != sourceId) return
        viewModelScope.launch {
            try { repo.library.unlinkWork(sourceId, revision); message = "已解除手动关联，可撤销" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "关联已变化，请重新查看后再操作" }
        }
    }

    fun undoWorkAssociation(revision: String) {
        viewModelScope.launch {
            try { repo.library.undoWorkAssociation(revision); message = "已撤销上次关联修改" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "无法撤销：关联已变化或没有可撤销的修改" }
        }
    }

    val workNavigation get() = selected?.let { WorkNavigationProjection.build(repo.library.snapshot, it) }

    /** Validate IDs against the latest snapshot; stale/removed choices are harmless. */
    fun selectWorkSource(workId: String, seasonId: String, sourceId: String? = null) {
        if (page != Page.DETAIL) return
        val navigation = workNavigation?.takeIf { it.workId == workId } ?: return
        val item = navigation.source(seasonId, sourceId) ?: return
        if (MediaDomainMapper.entryId(item) == selected?.let(MediaDomainMapper::entryId)) return
        openDetail(item, preserveWorkRoute = true)
    }

    fun open(item: MediaItem) = openDetail(item, preserveWorkRoute = false)

    private fun openDetail(item: MediaItem, preserveWorkRoute: Boolean) {
        invalidatePlayback()
        backdropJob?.cancel()
        detailJob?.cancel()
        val generation = ++detailGeneration
        selectedRecommendationReason = recommendationReasons[item.presentationKey].orEmpty()
        if (!preserveWorkRoute) detailSessionKey = item.presentationKey
        page = Page.DETAIL
        selected = repo.library.snapshot.applyProgress(item)
        resolutionStatus = ""
        alternatives = emptyList()
        busy = true
        detailJob = viewModelScope.launch {
            try {
                val detailed = repo.detail(item) { progress ->
                    if (page == Page.DETAIL && detailGeneration == generation) resolutionStatus = when (progress.phase) {
                        ResolutionPhase.SEARCHING -> "正在匹配片源 · 关键词 ${progress.queryIndex}/${progress.queryCount}"
                        ResolutionPhase.TRYING_SOURCE -> "正在验证片源 · ${progress.attempt}/${progress.maxAttempts} · ${progress.sourceName}"
                        ResolutionPhase.RESOLVED -> "片源已就绪"
                    }
                }
                if (page != Page.DETAIL || detailGeneration != generation) return@launch
                selected = detailed
                if (repo.persistenceWarning.isNotBlank()) message = repo.persistenceWarning
                busy = false
                runCatching { repo.enrichMetadata(detailed) }.onSuccess { enhanced ->
                    if (detailGeneration == generation && selected?.presentationKey == detailed.presentationKey && page == Page.DETAIL) selected = enhanced
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
        val wasTop = searchFilters.doubanTopOnly
        searchFilters = filters
        rebuildSearch()
        if (filters.doubanTopOnly && !wasTop) loadLicensedChart()
        else if (!filters.doubanTopOnly) {
            chartGeneration++; chartJob?.cancel(); chartLoading = false; rebuildSearch()
        }
    }

    private fun loadLicensedChart() {
        chartJob?.cancel()
        val request = ++chartGeneration
        chartNotice = ""
        if (!repo.hasMetadataCatalog) {
            chartLoading = false
            chartNotice = "未配置授权目录；豆瓣筛选不会使用其他来源替代。"
            rebuildSearch()
            return
        }
        val generation = searchGeneration
        val keyword = completedSearchQuery
        chartJob = viewModelScope.launch {
            chartLoading = true
            val deadline = System.currentTimeMillis() + CatalogRefreshPolicy.WINDOW_MS
            try {
                var firstPass = true
                val nextChecks = mutableMapOf<Boolean, Long>()
                while (true) {
                    if (!firstPass && System.currentTimeMillis() >= deadline) break
                    for (tv in listOf(false, true)) {
                        // Movie acquisition owns its own refresh state. An empty TV
                        // response cannot replace it or trigger movie polling.
                        if (!firstPass && (nextChecks[tv] ?: Long.MAX_VALUE) > System.currentTimeMillis()) continue
                        nextChecks.remove(tv)
                        if (!firstPass && CatalogRefreshPolicy.nextDelay((if (tv) seriesChart else movieChart).catalog,
                                System.currentTimeMillis(), Long.MAX_VALUE) == null) continue
                        try {
                            CatalogChartPaging.load(fetch = { offset ->
                                val page = repo.catalog(keyword, tv, offset, topOnly = true)
                                ensureActive()
                                if (generation != searchGeneration || request != chartGeneration)
                                    throw CancellationException("Chart request replaced")
                                page
                            }, onSnapshot = { snapshot ->
                                if (tv) seriesChart = snapshot else movieChart = snapshot
                                rebuildSearch()
                            })
                            val now = System.currentTimeMillis()
                            CatalogRefreshPolicy.nextDelay((if (tv) seriesChart else movieChart).catalog, now, deadline)
                                ?.let { nextChecks[tv] = now + it }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            chartNotice = "${if (tv) "剧集" else "电影"}榜单暂未更新完成，已保留成功读取的结果。"
                        }
                    }
                    firstPass = false
                    val now = System.currentTimeMillis()
                    val nextCheck = nextChecks.values.minOrNull() ?: break
                    if (now >= deadline || nextCheck > deadline) break
                    val wait = (nextCheck - now).coerceAtLeast(0)
                    delay(wait)
                    ensureActive()
                    if (generation != searchGeneration || request != chartGeneration) return@launch
                }
                if (listOf(movieChart, seriesChart).any {
                        CatalogRefreshPolicy.nextDelay(it.catalog, System.currentTimeMillis(), Long.MAX_VALUE) != null
                    }) chartNotice = "后台仍在更新，可稍后重新选择榜单查看进度。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { chartNotice = "榜单更新暂未完成，已保留本次成功读取的结果。" }
            finally {
                if (request == chartGeneration) {
                    chartLoading = false
                    rebuildSearch()
                }
            }
        }
    }

    private fun rebuildSearch() {
        val chartOnly = searchFilters.doubanTopOnly
        searchHits = SearchIndex.search(completedSearchQuery,
            // Saved titles can still contribute aliases/source alternatives, but an
            // old saved rank must not reappear in a newly fetched chart snapshot.
            library = repo.localItems().let { if (chartOnly) it.map { item -> item.copy(doubanTopRank = null) } else it },
            catalog = if (chartOnly) movieChart.items + seriesChart.items else catalogMovies + catalogSeries + queryCatalog,
            remote = remoteSearch, filters = searchFilters, limit = 1000)
        results = searchHits.map { it.item }
        catalogAttribution = (if (chartOnly) results else featured + tvShows + results).map { it.catalogAttribution }
            .filter(String::isNotBlank).distinct().joinToString("\n")
        catalogStatus = if (!chartOnly) listOf(false, true).filter { searchFilters.isTv == null || searchFilters.isTv == it }
            .map { tv -> searchBrowseState(tv).description().takeIf(String::isNotBlank)?.let { "${if (tv) "剧集" else "电影"}：$it" } }
            .filterNotNull().joinToString("\n") else buildList {
            if (repo.hasMetadataCatalog) {
                add("电影：${CatalogStatus.message(movieChart)}")
                if (seriesChart.items.isNotEmpty()) add("剧集：${CatalogStatus.message(seriesChart)}")
            }
            if (chartNotice.isNotBlank()) add(chartNotice)
        }.joinToString("\n")
        if (results.size >= 1000) catalogStatus += "\n搜索展示上限为 1000 条，请缩小关键词或筛选范围。"
    }

    fun search() {
        val wd = query.trim()
        if (wd.isBlank()) return
        navigate(Page.SEARCH)
        searchJob?.cancel()
        searchMoreJob?.cancel()
        chartJob?.cancel()
        chartGeneration++
        chartLoading = false
        movieChart = CatalogChartSnapshot()
        seriesChart = CatalogChartSnapshot()
        chartNotice = ""
        val generation = ++searchGeneration
        completedSearchQuery = wd
        remoteSearch = emptyList()
        queryCatalog = emptyList()
        movieSearchBrowse = CatalogBrowseState(nextOffset = if (repo.hasMetadataCatalog) 0 else null)
        seriesSearchBrowse = CatalogBrowseState(nextOffset = if (repo.hasMetadataCatalog) 0 else null)
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
                                fetchBrowsePage(tv, true) { generation == searchGeneration }
                            }
                        }
                    }
                    launch {
                        if (!repo.isConnected || sources.none { it.enabled }) return@launch
                        searchStage = "本地 → 元数据目录 → LibreTV 片源"
                        try {
                            repo.searchSnapshots(wd).collect { partial ->
                                ensureActive()
                                if (generation != searchGeneration) return@collect
                                remoteSearch = partial.items
                                val failed = partial.outcomes.count { !it.ok }
                                searchStage = if (failed > 0) "${if (partial.complete) "搜索完成" else "逐源搜索中"} · $failed 个片源失败或超时，已保留成功结果"
                                    else if (partial.complete) "片源搜索完成" else "正在逐源搜索"
                                rebuildSearch()
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            if (generation == searchGeneration)
                                searchStage = "片源响应未完整结束 · 已保留收到的结果，可重新搜索重试"
                        }
                    }
                }
                if (results.isEmpty()) message = "没有符合当前关键词和筛选条件的作品"
            } finally {
                // A cancelled older search must not reset the newer search's loading state.
                if (searchGeneration == generation) busy = false
            }
        }
        if (searchFilters.doubanTopOnly) loadLicensedChart()
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

    private fun invalidatePlayback(returningToDetail: Boolean = false) {
        playbackRecoveryJob?.cancel()
        playbackRecoveryJob = null
        playbackSessions.invalidate(returningToDetail)
        playbackSession = null
        failoverBusy = false
        failoverMessage = ""
    }

    private fun isCurrentPlayback(session: PlaybackSession): Boolean =
        page == Page.PLAYER && playbackSessions.isCurrent(session) &&
            playbackUrl == session.url && playingEpisode == session.episodeIndex &&
            if (session.item != null) {
                playerReturnPage == Page.DETAIL && selected?.presentationKey == session.mediaKey
            } else playerReturnPage == Page.LIVE

    fun playEpisode(index: Int = 0) {
        val item = selected ?: return
        val url = item.episodes.getOrNull(index)
        if (url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            message = "没有可播放的直链；请检查片源与剧集"
            return
        }
        invalidatePlayback()
        val session = playbackSessions.start(item, url, index)
        playbackSession = session
        playerReturnPage = Page.DETAIL
        playbackUrl = url
        playbackTitle = item.title + if (item.episodes.size > 1) " · 第${index + 1}集" else ""
        val progress = repo.library.snapshot.progress(item, index)
        playbackPosition = progress?.positionMs ?: 0L
        playingEpisode = index
        page = Page.PLAYER
        if (repo.libraryStatus(item) == null) viewModelScope.launch {
            try {
                repo.setLibraryStatus(item, LibraryStatus.WATCHING)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (isCurrentPlayback(session)) message = "片库保存失败：${error.message}"
            }
        }
    }

    fun playChannel(channel: TvChannel) {
        invalidatePlayback()
        playbackSession = playbackSessions.start(null, channel.url)
        playerReturnPage = Page.LIVE
        playbackUrl = channel.url
        playbackTitle = channel.name
        playbackPosition = 0L
        playingEpisode = 0
        page = Page.PLAYER
    }

    /** Refresh only a verified episode in its own source namespace. Other sources require a choice. */
    fun recoverPlayback(cause: String, lastPosition: Long, session: PlaybackSession? = playbackSession) {
        session ?: return
        if (!isCurrentPlayback(session) || failoverBusy) return
        val current = session.item
        val choiceMessage = "无法确认同一集的备用链接。请选择“返回换源”，确认片源和剧集；仅续播所选片源的已存进度，无记录则从头播放。"
        if (current == null) {
            failoverMessage = cause
            return
        }
        if (!playbackSessions.tryRefresh(session)) {
            failoverMessage = choiceMessage
            return
        }
        failoverBusy = true
        failoverMessage = "播放失败，正在验证当前片源的同一集链接..."
        playbackRecoveryJob = viewModelScope.launch {
            try {
                val detailed = PlaybackRecoveryPolicy.refreshCurrent(session, playbackSessions) {
                    repo.detail(it)
                } ?: return@launch
                ensureActive()
                if (!isCurrentPlayback(session)) return@launch
                val episode = session.episodeIndex
                if (!PlaybackRecoveryPolicy.canRetainPosition(current, episode, detailed, episode)) {
                    failoverMessage = choiceMessage
                    return@launch
                }
                val newUrl = detailed.episodes[episode]
                if (!SourceResolutionPolicy.usableUrl(newUrl) || newUrl == session.url) {
                    failoverMessage = choiceMessage
                    return@launch
                }
                val recoveredSession = playbackSessions.switchSource(session, detailed, newUrl) ?: return@launch
                // No suspension between ownership validation and publishing a replacement player.
                selected = detailed
                playbackPosition = lastPosition.coerceAtLeast(0)
                playbackUrl = newUrl
                playbackSession = recoveredSession
                failoverMessage = "已刷新当前片源的同一集链接"
                failoverBusy = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isCurrentPlayback(session)) failoverMessage = choiceMessage
            } finally {
                // A cancelled older job must never clear a newer recovery's loading state.
                if (isCurrentPlayback(session)) failoverBusy = false
            }
        }
    }

    fun savePlaybackPosition(
        position: Long,
        episodeIndex: Int = playingEpisode,
        durationMs: Long = 0L,
        session: PlaybackSession? = playbackSession
    ) {
        val item = session?.item ?: return
        if (episodeIndex != session.episodeIndex) return
        // Disposal may run after navigation. Persist its captured item, never the new selection.
        viewModelScope.launch {
            if (!playbackSessions.canPersistCheckpoint(session)) return@launch
            try {
                repo.saveProgress(item, position, episodeIndex, durationMs)
                ensureActive()
                if (isCurrentPlayback(session) || (page == Page.DETAIL &&
                        selected?.presentationKey == session.mediaKey && playbackSessions.canUpdateReturnedDetail(session))) {
                    selected = repo.library.snapshot.applyProgress(selected ?: item)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (isCurrentPlayback(session)) message = "观看进度未能保存：${error.message}"
            }
        }
    }

    fun playerEnded(
        position: Long = 0,
        duration: Long = 0,
        episodeIndex: Int = playingEpisode,
        session: PlaybackSession? = playbackSession
    ) {
        val entry = session?.item ?: return
        if (!isCurrentPlayback(session) || episodeIndex != session.episodeIndex) return
        viewModelScope.launch {
            if (!isCurrentPlayback(session) || !playbackSessions.canPersistCheckpoint(session)) return@launch
            try {
                repo.saveProgress(entry, if (duration > 0) duration else position, episodeIndex, duration)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (isCurrentPlayback(session)) message = "最终观看进度未能保存：${error.message}"
            }
            ensureActive()
            if (!isCurrentPlayback(session)) return@launch
            if (episodeIndex + 1 < entry.episodes.size) playEpisode(episodeIndex + 1)
            else try {
                repo.setLibraryStatus(entry, LibraryStatus.WATCHED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (isCurrentPlayback(session)) message = "片库保存失败：${error.message}"
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

    val quarantinedProgressCount: Int get() = repo.library.snapshot.quarantinedProgress.size
    fun hasLibraryStatus(item: MediaItem): Boolean = repo.library.snapshot.records.any {
        MediaDomainMapper.entryId(it.item) == MediaDomainMapper.entryId(item) && it.status != null
    }

    fun removeLibraryStatus() {
        val item = selected ?: return
        viewModelScope.launch {
            try { repo.setLibraryStatus(item, null); message = "已移除片库状态；收藏和观看进度已保留" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "片库状态未能移除，请重试" }
        }
    }

    /** Called only after the UI confirms deletion of local history and resume positions. */
    fun clearWatchHistory() {
        viewModelScope.launch {
            try {
                repo.library.clearHistory()
                selected = selected?.copy(positionMs = 0L, lastEpisode = 0, durationMs = 0L)
                message = "观看记录和续播进度已清空；收藏和片库状态已保留"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "观看记录未能清空，请重试" }
        }
    }

    fun libraryByStatus(status: LibraryStatus): List<MediaItem> = WorkNavigationProjection.deduplicate(repo.libraryByStatus(status), associations = workAssociations)

    fun favorites() = WorkNavigationProjection.deduplicate(repo.favorites(), associations = workAssociations)
    fun history() = repo.history()

    fun back() {
        when (page) {
            Page.PLAYER -> { invalidatePlayback(returningToDetail = playerReturnPage == Page.DETAIL); page = playerReturnPage }
            Page.DETAIL -> { invalidatePlayback(); page = section }
            Page.HOME -> Unit
            else -> navigate(Page.HOME)
        }
    }
}
