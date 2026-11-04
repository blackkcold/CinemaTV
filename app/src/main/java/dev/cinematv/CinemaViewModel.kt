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
    private var channelsJob: Job? = null
    var failoverBusy by mutableStateOf(false)
        private set
    var failoverMessage by mutableStateOf("")
        private set
    private val failedSources = mutableSetOf<String>()
    var playingEpisode by mutableStateOf(0)
        private set

    init { if (repo.isConnected) refreshConnection() }

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

    private fun loadHome() {
        viewModelScope.launch {
            runCatching { repo.recommend(false) }.onSuccess {
                featured = it
                if (focused == null) focused = it.firstOrNull()
                val first = it.firstOrNull()
                if (first != null) {
                    runCatching { repo.enrichMetadata(first) }.onSuccess { enhanced ->
                        featured = featured.map { original ->
                            if (original.id == first.id) enhanced else original
                        }
                        if (focused?.id == first.id) focused = enhanced
                    }
                }
            }.onFailure { message = "推荐加载失败：${it.message}" }
            runCatching { repo.recommend(true) }.onSuccess { tvShows = it }
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
        page = Page.DETAIL
        selected = item
        alternatives = emptyList()
        busy = true
        detailJob = viewModelScope.launch {
            try {
                val detailed = repo.detail(item)
                if (page != Page.DETAIL) return@launch
                selected = detailed
                busy = false
                runCatching { repo.enrichMetadata(detailed) }.onSuccess { enhanced ->
                    if (selected?.presentationKey == detailed.presentationKey && page == Page.DETAIL) selected = enhanced
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                connectionError(error)
            } finally { busy = false }
        }
    }

    fun findAlternatives() {
        val item = selected ?: return
        viewModelScope.launch {
            busy = true
            runCatching { repo.search(item.title) }
                .onSuccess { items ->
                    alternatives = items.filter { MediaIdentity.isSameWork(item.title, item.year, it.title, it.year) }
                        .distinctBy { it.source?.url }
                }
                .onFailure { message = "换源失败：${it.message}" }
            busy = false
        }
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

    fun search() {
        val wd = query.trim()
        if (wd.isBlank()) return
        navigate(Page.SEARCH)
        searchJob?.cancel()
        repo.rememberSearch(wd)
        searchHistory = repo.searchHistory()
        searchJob = viewModelScope.launch {
            busy = true
            results = emptyList()
            runCatching {
                repo.searchProgressively(wd).collect { partial ->
                    results = partial
                }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@launch
                runCatching { repo.search(wd) }
                    .onSuccess { results = SearchAggregator.group(it) }
                    .onFailure { message = it.message ?: "搜索失败" }
            }
            if (results.isEmpty() && message.isBlank()) message = "没有找到相关影片"
            busy = false
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
        if (repo.libraryStatus(item) == null) repo.setLibraryStatus(item, LibraryStatus.WATCHING)
        playbackUrl = url
        playbackTitle = item.title + if (item.episodes.size > 1) " · 第${index + 1}集" else ""
        playbackPosition = if (index == item.lastEpisode) item.positionMs else 0L
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
                        MediaIdentity.isSameWork(current.title, current.year, candidate.title, candidate.year) &&
                        candidate.source != null && candidate.source.url !in failedSources
                    }.take(12)
                var found: MediaItem? = null
                for (candidate in alternatives) {
                    val sourceUrl = candidate.source?.url ?: continue
                    failedSources.add(sourceUrl)
                    try {
                        val detailed = repo.detail(candidate)
                        if (detailed.episodes.getOrNull(playingEpisode)?.startsWith("http") == true) {
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
        if (playerReturnPage == Page.DETAIL) selected?.let {
            repo.saveProgress(it, position, episodeIndex, durationMs)
            if (episodeIndex == playingEpisode) {
                selected = it.copy(positionMs = position.coerceAtLeast(0), lastEpisode = episodeIndex, durationMs = durationMs.takeIf { value -> value > 0 } ?: it.durationMs)
            }
            libraryVersion++
        }
    }

    fun playerEnded() {
        val entry = selected ?: return
        if (playerReturnPage == Page.DETAIL) {
            if (playingEpisode + 1 < entry.episodes.size) playEpisode(playingEpisode + 1)
            else { repo.setLibraryStatus(entry, LibraryStatus.WATCHED); libraryVersion++ }
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
        selected?.let { repo.toggleFavorite(it); libraryVersion++; message = "收藏已更新" }
    }

    fun setLibraryStatus(status: LibraryStatus) {
        selected?.let {
            repo.setLibraryStatus(it, status)
            libraryVersion++
            message = "已加入：${status.title}"
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
