package dev.cinematv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cinematv.data.*
import dev.cinematv.data.douban.public.toMediaItem
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

class CinemaViewModel(application: Application, val repo: LibreTvRepository) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, LibreTvRepository(application))
    /** Anonymous public lists; no account, cookie or licence declaration involved. */
    private val publicDouban by lazy {
        dev.cinematv.data.douban.public.DoubanPublicChannel.chart(application)
    }
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
    /** One bounded LibreTV search; a LibreTV-only install has no other browse source. */
    var discovery by mutableStateOf(DiscoveryState())
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
    /** Single connection truth for every surface; see [ConnectionState]. */
    var connectionState by mutableStateOf<ConnectionState>(
        if (repo.isConnected) ConnectionState.Connecting else ConnectionState.Unconfigured
    )
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
    var defaultSourceSubscriptions by mutableStateOf<List<SourceSubscription>>(emptyList())
        private set
    var sourceSubscriptions by mutableStateOf(repo.sourceSubscriptions())
        private set
    var sourceSubscriptionAddress by mutableStateOf("")
    var sourceSubscriptionName by mutableStateOf("")
    var sourceSubscriptionPreview by mutableStateOf<SourceSubscriptionPreview?>(null)
        private set
    var sourceSubscriptionBusy by mutableStateOf(false)
        private set
    var sourceSubscriptionNotice by mutableStateOf(repo.subscriptionStorageError)
        private set
    private var sourceSubscriptionJob: Job? = null
    private var sourceSubscriptionGeneration = 0L
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
    private var epgRequestedAt = 0L
    private var connectionJob: Job? = null
    private var connectionAttempt = 0L
    private var defaultLoginAttempted = false
    private var alternativesJob: Job? = null
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
    private var discoveryJob: Job? = null
    private var discoveryRotation = 0
    private val discoveryStore by lazy { HomeDiscoveryStore(application) }
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
    val searchCatalogRequested get() = completedSearchQuery.isNotBlank()
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
        publish(initial.copy(loading = true, notice = "", retryableFailure = false))
        try {
            val response = repo.catalog(if (search) completedSearchQuery else "", series, offset)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (current()) publish(CatalogBrowsePaging.append(initial, response, offset))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(initial.copy(loading = false, retryableFailure = true,
            notice = "目录加载失败，已保留结果；可重试。")) }
        finally { if (current() && state().loading) publish(state().copy(loading = false)) }
    }

    private var remoteSearch: List<MediaItem> = emptyList()
    private var queryCatalog: List<MediaItem> = emptyList()
    var searchFilters by mutableStateOf(SearchFilters())
        private set
    var searchStage by mutableStateOf("")
    /** A total search failure, distinct from a successful empty result. */
    var searchError by mutableStateOf("")
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
    var doubanTagLoading by mutableStateOf(false)
        private set
    var doubanTagNotice by mutableStateOf("")
        private set
    private var doubanList by mutableStateOf<List<MediaItem>>(emptyList())
    private var doubanTagJob: Job? = null
    val searchYears: List<String> get() = (catalogMovies + catalogSeries + queryCatalog + movieChart.items + seriesChart.items + remoteSearch +
        repo.library.snapshot.records.map { it.item }).map { it.year }.filter { it.matches(Regex("\\d{4}")) }.distinct().sortedDescending()

    fun cycleSearchYear() {
        val choices = listOf<String?>(null) + searchYears
        updateSearchFilters(searchFilters.copy(year = choices[(choices.indexOf(searchFilters.year) + 1) % choices.size]))
    }

    /** Select or clear a curated public tag. No account, cookie or BFF is involved. */
    fun selectDoubanTag(tag: String?) {
        updateSearchFilters(searchFilters.copy(
            doubanTag = tag,
            doubanTopOnly = false,
            ratingSource = "Douban",
            minRating = null
        ))
    }

    private fun loadDoubanTag(tag: String) {
        doubanTagJob?.cancel()
        doubanTagJob = viewModelScope.launch {
            doubanTagLoading = true
            doubanTagNotice = ""
            try {
                val kind = if (dev.cinematv.data.douban.public.DoubanPublicCatalog.isTvTag(tag))
                    dev.cinematv.data.douban.public.DoubanPublicKind.TV
                else dev.cinematv.data.douban.public.DoubanPublicKind.MOVIE
                val page = dev.cinematv.data.douban.public.DoubanPublicChannel
                    .repository(getApplication()).browse(tag, kind = kind)
                doubanList = page.items.map { it.toMediaItem() }
                val asOf = page.fetchedAt.takeIf { it > 0 }?.let { at ->
                    " · 截至 " + java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.ofEpochMilli(at))
                }.orEmpty()
                doubanTagNotice = when {
                    page.stale -> "豆瓣「$tag」暂时不可用，显示上次结果$asOf"
                    page.items.isEmpty() -> "豆瓣「$tag」暂无可用条目"
                    else -> "豆瓣「$tag」· ${page.items.size} 部$asOf"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (stop: dev.cinematv.data.douban.public.DoubanPublicException) {
                doubanTagNotice = stop.message
            } catch (_: Exception) {
                doubanTagNotice = "豆瓣标签暂时不可用，请稍后再试"
            } finally {
                doubanTagLoading = false
                rebuildSearch()
            }
        }
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

    private fun connectionError(error: Throwable, connection: LibreTvConnection = repo.connectionContext()) {
        if (error is CancellationException || !repo.isCurrentConnectionError(error, connection)) return
        message = ConnectionErrors.message(error)
        connectionState = connectionStateForFailure(repo.server, error)
        if (ConnectionErrors.classify(error) == ConnectionFailure.AUTHENTICATION) {
            authorized = false
            passwordRequired = true
            discoveryJob?.cancel()
            discovery = DiscoveryState()
            if (page != Page.PLAYER) page = Page.SETTINGS
        }
    }

    fun refreshConnection() {
        connectionJob?.cancel()
        val attempt = ++connectionAttempt
        val connection = repo.connectionContext()
        if (!connectionState.isUsable) connectionState = ConnectionState.Connecting
        connectionJob = viewModelScope.launch {
            busy = true
            try { readConnection(connection, attempt) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (attempt == connectionAttempt && repo.isCurrentConnectionError(error, connection)) {
                    authorized = false
                    connectionError(error, connection)
                }
            } finally { if (attempt == connectionAttempt && repo.isCurrentConnection(connection)) busy = false }
        }
    }

    private suspend fun readConnection(connection: LibreTvConnection, attempt: Long) {
        val status = repo.status(connection)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (attempt != connectionAttempt || !repo.isCurrentStatus(status, connection)) return
        passwordRequired = status.passwordRequired
        authorized = status.verified
        connectionState = connectionStateFor(repo.server, status)
        sources = status.sources
        liveSources = status.liveSources
        defaultSourceSubscriptions = status.defaultSubscriptions
        sourceSubscriptions = repo.sourceSubscriptions()
        message = if (authorized) "" else if (status.passwordRequired) "此服务器要求密码，请在设置中登录。"
            else "服务器未配置访问密码（PASSWORD），请联系管理员完成配置。"
        if (authorized) { loadHome(); loadDiscovery(); return }
        // A personal local build may carry the endpoint password. Try it once per connection
        // attempt; never loop, and never override an explicit user login or a rejected password.
        val defaultPassword = BuildConfig.LIBRETV_DEFAULT_PASSWORD
        if (status.passwordRequired && !defaultLoginAttempted && defaultPassword.isNotBlank()) {
            defaultLoginAttempted = true
            connectionState = ConnectionState.Authenticating
            val verified = repo.login(defaultPassword, connection)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (attempt != connectionAttempt || !repo.isCurrentConnection(connection)) return
            if (verified) readConnection(connection, attempt)
            else {
                authorized = false; passwordRequired = true
                connectionState = ConnectionState.AuthRejected
                message = "默认密码未通过，请在设置中登录。"
            }
        }
    }

    /** Replacing a server cannot retain any in-flight response's UI ownership. Local library stays intact. */
    private fun cancelConnectionWork() {
        connectionAttempt++
        connectionJob?.cancel()
        searchGeneration++; detailGeneration++; browseGeneration++; chartGeneration++; sourceSubscriptionGeneration++
        listOf(searchJob, searchMoreJob, detailJob, alternativesJob, channelsJob, epgJob, backdropJob,
            homeJob, chartJob, recommendationJob, sourceSubscriptionJob, discoveryJob).forEach { it?.cancel() }
        discovery = DiscoveryState()
        browseJobs.values.forEach { it.cancel() }
        browseJobs.clear()
        busy = false
        chartLoading = false
        authorized = false
        passwordRequired = false
        connectionState = if (repo.isConnected) ConnectionState.Connecting else ConnectionState.Unconfigured
        password = ""
        selected = null
        focused = null
        alternatives = emptyList()
        activeChannel = null
        activeLiveSource = null
        channels = emptyList()
        epgPrograms = emptyList()
        epgRequestedAt = 0L
        sourceProbes = emptyMap()
        defaultSourceSubscriptions = emptyList()
        sourceSubscriptionPreview = null
        sourceSubscriptionBusy = false
        sourceSubscriptionNotice = repo.subscriptionStorageError
        resolutionStatus = ""
        remoteSearch = emptyList()
        sources = repo.sources()
        liveSources = repo.liveSources()
        rebuildSearch()
    }

    fun connect() {
        defaultLoginAttempted = false
        runCatching {
            repo.configureServer(serverAddress)
            cancelConnectionWork()
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
        connectionJob?.cancel()
        listOf(searchJob, detailJob, alternativesJob, channelsJob, epgJob, sourceSubscriptionJob).forEach { it?.cancel() }
        sourceSubscriptionGeneration++
        sourceSubscriptionPreview = null
        sourceSubscriptionBusy = false
        val attempt = ++connectionAttempt
        val connection = repo.beginAuthentication()
        connectionState = ConnectionState.Authenticating
        val submittedPassword = password
        password = ""
        connectionJob = viewModelScope.launch {
            busy = true
            try {
                val verified = repo.login(submittedPassword, connection)
                ensureActive()
                if (attempt != connectionAttempt || !repo.isCurrentConnection(connection)) return@launch
                if (verified) readConnection(connection, attempt)
                else {
                    authorized = false; passwordRequired = true
                    connectionState = ConnectionState.AuthRejected
                    message = "密码错误"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (attempt == connectionAttempt) connectionError(error, connection)
            } finally { if (attempt == connectionAttempt && repo.isCurrentConnection(connection)) busy = false }
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
        val connection = repo.connectionContext()
        viewModelScope.launch {
            sourceProbes = sourceProbes + (source.url to SourceProbe(false, 0, "检测中…"))
            try {
                val result = repo.probeSource(source)
                ensureActive()
                if (repo.isCurrentConnection(connection)) sourceProbes = sourceProbes + (source.url to result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (repo.isCurrentConnection(connection)) sourceProbes = sourceProbes +
                    (source.url to SourceProbe(false, 0, error.message ?: "无法连接"))
            }
        }
    }

    fun removeSource(url: String, live: Boolean = false) {
        if (!repo.isManualSource(url, live)) { message = "此来源由订阅或服务器提供，请在相应设置中管理。"; return }
        val fallbackRemains = repo.hasSourceFallback(url, live)
        repo.removeSource(url, live)
        sources = repo.sources()
        liveSources = repo.liveSources()
        message = if (fallbackRemains) "已移除手动来源；同地址仍由订阅或服务器提供。" else "已移除手动来源；收藏与观看进度已保留。"
    }

    fun canRemoveManualSource(url: String, live: Boolean = false): Boolean = repo.isManualSource(url, live)
    fun sourceRemovalLabel(url: String, live: Boolean = false): String =
        if (repo.hasSourceFallback(url, live)) "移除手动源" else "移除"

    fun previewSourceSubscription(subscription: SourceSubscription? = null) {
        if (!authorized) { sourceSubscriptionNotice = "请先连接 LibreTV 并登录，再读取片源列表订阅。"; return }
        val choice = subscription ?: SourceSubscription(sourceSubscriptionAddress, sourceSubscriptionName)
        sourceSubscriptionJob?.cancel()
        val generation = ++sourceSubscriptionGeneration
        val connection = repo.connectionContext()
        sourceSubscriptionPreview = null
        sourceSubscriptionNotice = ""
        sourceSubscriptionBusy = true
        sourceSubscriptionJob = viewModelScope.launch {
            try {
                val preview = repo.previewSourceSubscription(choice, connection)
                ensureActive()
                if (generation == sourceSubscriptionGeneration && repo.isCurrentConnection(connection)) {
                    sourceSubscriptionPreview = preview
                    sourceSubscriptionNotice = "请查看片源变化与跳过原因，确认后保存。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == sourceSubscriptionGeneration && repo.isCurrentConnectionError(error, connection)) {
                    sourceSubscriptionNotice = ConnectionErrors.message(error) + "；已保留现有订阅与片源。"
                    if (ConnectionErrors.classify(error) == ConnectionFailure.AUTHENTICATION) connectionError(error, connection)
                }
            } finally {
                if (generation == sourceSubscriptionGeneration && repo.isCurrentConnection(connection)) sourceSubscriptionBusy = false
            }
        }
    }

    fun dismissSourceSubscriptionPreview() {
        sourceSubscriptionGeneration++
        sourceSubscriptionJob?.cancel()
        sourceSubscriptionPreview = null
        sourceSubscriptionBusy = false
        sourceSubscriptionNotice = ""
    }

    fun importSourceSubscriptionPreview() {
        val preview = sourceSubscriptionPreview ?: return
        if (sourceSubscriptionBusy) return
        val generation = ++sourceSubscriptionGeneration
        sourceSubscriptionBusy = true
        sourceSubscriptionJob = viewModelScope.launch {
            try {
                repo.importSourceSubscription(preview)
                ensureActive()
                if (generation == sourceSubscriptionGeneration && repo.isCurrentConnection(preview.connection)) {
                    sourceSubscriptions = repo.sourceSubscriptions()
                    sources = repo.sources()
                    liveSources = repo.liveSources()
                    sourceSubscriptionPreview = null
                    sourceSubscriptionAddress = ""
                    sourceSubscriptionName = ""
                    sourceSubscriptionNotice = "已保存：点播 ${preview.snapshot.sources.size} 个、直播 ${preview.snapshot.liveSources.size} 个；已有停用选择保持不变。"
                }
            } catch (cancelled: CancellationException) {
                if (generation == sourceSubscriptionGeneration) {
                    sourceSubscriptionPreview = null
                    sourceSubscriptionNotice = "连接已变化，请重新预览订阅。"
                }
                throw cancelled
            } catch (error: Exception) {
                if (generation == sourceSubscriptionGeneration) sourceSubscriptionNotice = error.message ?: "订阅保存失败，请重试。"
            } finally { if (generation == sourceSubscriptionGeneration) sourceSubscriptionBusy = false }
        }
    }

    fun stopSourceSubscription(url: String) {
        if (sourceSubscriptionBusy) return
        val generation = ++sourceSubscriptionGeneration
        sourceSubscriptionBusy = true
        sourceSubscriptionJob = viewModelScope.launch {
            try {
                repo.stopSourceSubscription(url)
                ensureActive()
                if (generation == sourceSubscriptionGeneration) {
                    sourceSubscriptions = repo.sourceSubscriptions()
                    sources = repo.sources()
                    liveSources = repo.liveSources()
                    sourceSubscriptionPreview = null
                    sourceSubscriptionNotice = "已停止订阅；当前片源已保留为手动源，收藏和进度保持不变。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == sourceSubscriptionGeneration) sourceSubscriptionNotice = error.message ?: "停止订阅失败，请重试。"
            } finally { if (generation == sourceSubscriptionGeneration) sourceSubscriptionBusy = false }
        }
    }

    private fun rebuildRecommendations() {
        recommendationJob?.cancel()
        val records = repo.library.snapshot.records.toList()
        val signals = records.filter { it.status != null || it.favorite || it.historyAt != null }.map {
            RecommendationSignal(it.item, it.status,
                favorite = it.favorite, notInterested = it.status == LibraryStatus.DROPPED)
        }
        // Douban is a separate chain; its public score and rank never decorate a home card.
        val offline = repo.localItems().map { it.withoutDoubanHomeBadges() }
        val movieInput = catalogMovies.ifEmpty { offline.filter { !it.isTv } }.toList()
        val seriesInput = catalogSeries.ifEmpty { offline.filter { it.isTv } }.toList()
        recommendationJob = viewModelScope.launch {
            // Coalesce Room/catalog updates; ranking must never block D-pad or playback input.
            delay(50)
            val now = System.currentTimeMillis()
            val snapshots = withContext(Dispatchers.Default) {
                val checkCancelled: () -> Unit = { ensureActive() }
                val movies = LocalRecommendationPipeline.rank(movieInput, signals, 50, now, checkCancelled = checkCancelled, backend = dev.cinematv.data.recommendation.ranksys.RankSysContentBackend)
                ensureActive()
                val series = LocalRecommendationPipeline.rank(seriesInput, signals, 50, now, checkCancelled = checkCancelled, backend = dev.cinematv.data.recommendation.ranksys.RankSysContentBackend)
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

    /**
     * Discovery is one explicit search, never a background crawl: the previous job is
     * cancelled, a fresh cache short-circuits the network, and a failure only affects this rail.
     */
    fun loadDiscovery(rotating: Boolean = false) {
        discoveryJob?.cancel()
        if (!authorized) return
        if (rotating) discoveryRotation++
        val connection = repo.connectionContext()
        val enabled = repo.sources().filter { it.enabled }
        if (enabled.isEmpty()) {
            discovery = DiscoveryState(message = "没有启用的片源")
            return
        }
        val query = HomeDiscoveryStore.queryAt(connection.server.hashCode(), discoveryRotation)
        val key = HomeDiscoveryStore.keyFor(connection.server, connection.generation, query, enabled)
        discoveryJob = viewModelScope.launch {
            discoveryStore.read(key)?.let { cached ->
                discovery = DiscoveryState(cached.query, cached.items, fromCache = true)
                return@launch
            }
            discovery = discovery.copy(loading = true, message = "")
            try {
                repo.searchSnapshots(query, connection).collect { snapshot ->
                    discovery = discovery.copy(query = query, items = snapshot.items, loading = !snapshot.complete, fromCache = false)
                    if (snapshot.complete) {
                        discoveryStore.write(query, System.currentTimeMillis(), snapshot.items, key)
                        discovery = discovery.copy(loading = false)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Discovery never blanks the page and never rewrites the connection state.
                discovery = discovery.copy(loading = false,
                    message = if (discovery.items.isEmpty()) ConnectionErrors.message(error) else "")
            }
        }
    }

    fun rotateDiscovery() = loadDiscovery(rotating = true)

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
        dev.cinematv.diagnostics.DiagnosticLog.verbose("ui.route", fields = mapOf(
            "from" to page.name.lowercase(), "to" to destination.name.lowercase()
        ))
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

    fun onMediaFocus(item: MediaItem, region: String = "general", position: Int = 0) {
        val route = page
        focusCoordinator.remember(route.name, region, item.presentationKey, position)
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
        alternativesJob?.cancel()
        val generation = ++detailGeneration
        val connection = repo.connectionContext()
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
                ensureActive()
                if (page != Page.DETAIL || detailGeneration != generation || !repo.isCurrentConnection(connection)) return@launch
                selected = detailed
                if (repo.persistenceWarning.isNotBlank()) message = repo.persistenceWarning
                busy = false
                runCatching { repo.enrichMetadata(detailed) }.onSuccess { enhanced ->
                    if (detailGeneration == generation && selected?.presentationKey == detailed.presentationKey && page == Page.DETAIL) selected = enhanced
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (detailGeneration == generation) connectionError(error, connection)
            } finally { if (detailGeneration == generation && repo.isCurrentConnection(connection)) busy = false }
        }
    }

    fun findAlternatives() {
        val item = selected ?: return
        val connection = repo.connectionContext()
        val generation = detailGeneration
        alternativesJob?.cancel()
        alternativesJob = viewModelScope.launch {
            busy = true
            try {
                val items = repo.searchSnapshot(item.title, connection).items
                ensureActive()
                if (generation == detailGeneration && repo.isCurrentConnection(connection) &&
                    selected?.presentationKey == item.presentationKey)
                    alternatives = items.filter { sameSourceCandidate(item, it) }.distinctBy { it.source?.url }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == detailGeneration && repo.isCurrentConnection(connection)) connectionError(error, connection)
            } finally { if (generation == detailGeneration && repo.isCurrentConnection(connection)) busy = false }
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
        if (activeChannel?.let { ChannelIdentity.key(activeLiveSource, it) } == ChannelIdentity.key(activeLiveSource, channel)) {
            refreshEpgIfStale()
            return
        }
        activeChannel = channel
        epgPrograms = emptyList()
        epgRequestedAt = 0L
        requestEpg(channel, debounce = true)
    }

    /** The visible guide invokes this on its lifecycle clock; no background polling or parallel requests. */
    fun refreshEpgIfStale(nowMs: Long = System.currentTimeMillis()) {
        val channel = activeChannel ?: return
        if (epgJob?.isActive == true || activeLiveSource?.epg.isNullOrBlank()) return
        if (epgRequestedAt > 0 && nowMs >= epgRequestedAt && nowMs - epgRequestedAt < 15 * 60_000L) return
        requestEpg(channel, debounce = false, nowMs = nowMs)
    }

    private fun requestEpg(channel: TvChannel, debounce: Boolean, nowMs: Long = System.currentTimeMillis()) {
        epgJob?.cancel()
        val source = activeLiveSource ?: return
        if (source.epg.isBlank()) return
        epgRequestedAt = nowMs
        val connection = repo.connectionContext()
        val key = ChannelIdentity.key(source, channel)
        epgJob = viewModelScope.launch {
            if (debounce) delay(350)
            try {
                val programs = repo.epg(source, channel, connection)
                ensureActive()
                if (repo.isCurrentConnection(connection) && activeChannel?.let { ChannelIdentity.key(activeLiveSource, it) } == key)
                    epgPrograms = programs
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ConnectionErrors.classify(error) == ConnectionFailure.AUTHENTICATION) connectionError(error, connection)
            }
        }
    }

    fun updateSearchFilters(filters: SearchFilters) {
        val wasTop = searchFilters.doubanTopOnly
        val wasDouban = RatingPolicy.normalizeProvider(searchFilters.ratingSource.orEmpty()) == "douban"
        val previousTag = searchFilters.doubanTag
        searchFilters = filters
        val isDouban = RatingPolicy.normalizeProvider(filters.ratingSource.orEmpty()) == "douban"
        if (filters.doubanTag != previousTag) {
            if (filters.doubanTag == null) {
                doubanTagJob?.cancel(); doubanTagJob = null
                doubanList = emptyList(); doubanTagLoading = false; doubanTagNotice = ""
            } else loadDoubanTag(filters.doubanTag)
        }
        rebuildSearch()
        if (filters.doubanTopOnly && !wasTop) loadLicensedChart()
        else if (!isDouban) {
            chartGeneration++; chartJob?.cancel(); chartLoading = false; rebuildSearch()
        } else if (!wasDouban && filters.doubanTag == null && movieChart.items.isEmpty() && !chartLoading) loadLicensedChart()
    }

    private fun loadLicensedChart() {
        chartJob?.cancel()
        val request = ++chartGeneration
        chartNotice = ""
        // No metadata server is a normal configuration, not a failure: the anonymous
        // public channel reads the same chart without BFF, cookie or licence declaration.
        val public = !repo.hasMetadataCatalog
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
                        // The anonymous Top 250 is a movie chart; TV keeps its own path.
                        if (public && tv) continue
                        // Movie acquisition owns its own refresh state. An empty TV
                        // response cannot replace it or trigger movie polling.
                        if (!firstPass && (nextChecks[tv] ?: Long.MAX_VALUE) > System.currentTimeMillis()) continue
                        nextChecks.remove(tv)
                        if (!firstPass && CatalogRefreshPolicy.nextDelay((if (tv) seriesChart else movieChart).catalog,
                                System.currentTimeMillis(), Long.MAX_VALUE) == null) continue
                        try {
                            CatalogChartPaging.load(fetch = { offset ->
                                val page = if (public) publicDouban.top250(offset)
                                    else repo.catalog(keyword, tv, offset, topOnly = true)
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
                        } catch (publicStop: dev.cinematv.data.douban.public.DoubanPublicException) {
                            chartNotice = publicStop.message + publicStop.retryAt
                                .takeIf { it > System.currentTimeMillis() }
                                ?.let { until -> " · " + java.time.format.DateTimeFormatter
                                    .ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
                                    .format(java.time.Instant.ofEpochMilli(until)) + " 后可重试" }
                                .orEmpty()
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
        val doubanSource = RatingPolicy.normalizeProvider(searchFilters.ratingSource.orEmpty()) == "douban"
        searchHits = SearchIndex.search(completedSearchQuery,
            // Saved titles can still contribute aliases/source alternatives, but an
            // old saved rank must not reappear in a newly fetched chart snapshot.
            library = repo.localItems().let { if (chartOnly) it.map { item -> item.copy(doubanTopRank = null) } else it },
            catalog = if (chartOnly) (movieChart.items + seriesChart.items).distinctBy { it.presentationKey }
                else (catalogMovies + catalogSeries + queryCatalog +
                    (if (doubanSource) movieChart.items else emptyList()) +
                    (if (searchFilters.doubanTag != null) doubanList else emptyList()))
                    .distinctBy { it.presentationKey },
            remote = remoteSearch, filters = searchFilters, limit = 1000)
        results = searchHits.map { it.item }
        catalogAttribution = (if (chartOnly) results else featured + tvShows + results).map { it.catalogAttribution }
            .filter(String::isNotBlank).distinct().joinToString("\n")
        catalogStatus = if (!chartOnly) listOf(false, true).filter { searchFilters.isTv == null || searchFilters.isTv == it }
            .map { tv -> searchBrowseState(tv).description().takeIf(String::isNotBlank)?.let { "${if (tv) "剧集" else "电影"}：$it" } }
            .filterNotNull().joinToString("\n") else buildList {
            if (repo.hasMetadataCatalog || movieChart.catalog.acquisition != null || seriesChart.catalog.acquisition != null) {
                add("电影：${CatalogStatus.message(movieChart)}")
                if (seriesChart.items.isNotEmpty()) add("剧集：${CatalogStatus.message(seriesChart)}")
            }
            if (chartNotice.isNotBlank()) add(chartNotice)
        }.joinToString("\n")
        if (!chartOnly && doubanSource && catalogStatus.isBlank() && movieChart.catalog.acquisition != null) {
            catalogStatus = CatalogStatus.message(movieChart)
        }
        if (!chartOnly && searchFilters.doubanTag != null && doubanTagNotice.isNotBlank()) {
            catalogStatus = listOf(catalogStatus, doubanTagNotice).filter(String::isNotBlank).joinToString("\n")
        }
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
        val connection = repo.connectionContext()
        completedSearchQuery = wd
        remoteSearch = emptyList()
        queryCatalog = emptyList()
        movieSearchBrowse = CatalogBrowseState(nextOffset = if (repo.hasMetadataCatalog) 0 else null)
        seriesSearchBrowse = CatalogBrowseState(nextOffset = if (repo.hasMetadataCatalog) 0 else null)
        message = ""
        searchError = ""
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
                            repo.searchSnapshots(wd, connection).collect { partial ->
                                ensureActive()
                                if (generation != searchGeneration || !repo.isCurrentConnection(connection)) return@collect
                                remoteSearch = partial.items
                                val failed = partial.outcomes.count { !it.ok }
                                searchStage = if (failed > 0) "${if (partial.complete) "搜索完成" else "逐源搜索中"} · $failed 个片源失败或超时，已保留成功结果"
                                    else if (partial.complete) "片源搜索完成" else "正在逐源搜索"
                                rebuildSearch()
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (generation == searchGeneration && repo.isCurrentConnectionError(error, connection)) {
                                searchError = ConnectionErrors.message(error)
                                searchStage = ConnectionErrors.message(error) + " · 已保留本地与成功读取的结果"
                                if (ConnectionErrors.classify(error) == ConnectionFailure.AUTHENTICATION)
                                    connectionError(error, connection)
                            }
                        }
                    }
                }
                if (generation == searchGeneration && repo.isCurrentConnection(connection) && results.isEmpty() && message.isBlank())
                    message = "没有符合当前关键词和筛选条件的作品"
            } finally {
                // A cancelled older search must not reset the newer search's loading state.
                if (searchGeneration == generation && repo.isCurrentConnection(connection)) busy = false
            }
        }
        if (searchFilters.doubanTopOnly ||
            RatingPolicy.normalizeProvider(searchFilters.ratingSource.orEmpty()) == "douban") loadLicensedChart()
    }

    fun episodeProgress(index: Int) = selected?.let { repo.library.snapshot.progress(it, index) }

    fun episodeLabel(index: Int): String {
        val item = selected ?: return "第 ${index + 1} 集"
        val name = item.episodeNames.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: if (item.episodes.size == 1) "播放" else "第 ${index + 1} 集"
        val progress = episodeProgress(index) ?: return name
        return when {
            progress.completed -> "$name · 重新播放"
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

    fun playEpisode(index: Int = 0, action: EpisodePlaybackAction = EpisodePlaybackAction.RESUME) {
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
        playbackPosition = EpisodePlaybackPolicy.startPosition(progress, action)
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
            if (episodeIndex + 1 < entry.episodes.size) playEpisode(episodeIndex + 1, EpisodePlaybackAction.RESTART)
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
        epgRequestedAt = 0L
        val connection = repo.connectionContext()
        channelsJob = viewModelScope.launch {
            busy = true
            try {
                val loaded = repo.channels(source, connection)
                ensureActive()
                if (repo.isCurrentConnection(connection) && activeLiveSource?.url == source.url) channels = loaded
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activeLiveSource?.url == source.url) connectionError(error, connection)
            } finally { if (repo.isCurrentConnection(connection) && activeLiveSource?.url == source.url) busy = false }
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
    fun history() = repo.history().map { it.withoutDoubanHomeBadges() }

    fun back() {
        val target = when (page) {
            Page.PLAYER -> playerReturnPage
            Page.DETAIL -> section
            else -> Page.HOME
        }
        dev.cinematv.diagnostics.DiagnosticLog.verbose("ui.back", fields = mapOf(
            "from" to page.name.lowercase(), "to" to target.name.lowercase()
        ))
        when (page) {
            Page.PLAYER -> { invalidatePlayback(returningToDetail = playerReturnPage == Page.DETAIL); page = playerReturnPage }
            Page.DETAIL -> { invalidatePlayback(); page = section }
            Page.HOME -> Unit
            else -> navigate(Page.HOME)
        }
    }
}

/** Home discovery is deliberately small: one query, one list, one message. */
data class DiscoveryState(
    val query: String = "",
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val message: String = "",
    val fromCache: Boolean = false
)
