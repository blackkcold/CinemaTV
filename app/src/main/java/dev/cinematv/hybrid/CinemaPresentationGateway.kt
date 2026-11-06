package dev.cinematv.hybrid

import dev.cinematv.BuildConfig
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.data.LibraryStatus
import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.RatingPolicy
import dev.cinematv.data.SearchFilters
import dev.cinematv.data.TvChannel
import dev.cinematv.data.LiveSource
import dev.cinematv.data.usableMetadata
import dev.cinematv.ui.shared.CatalogFilter
import dev.cinematv.ui.shared.WorkNavigationProjection
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.design.GlassQuality
import org.json.JSONArray
import org.json.JSONObject

/** A bounded projection of application state, never a serialization of the ViewModel or provider responses. */
class CinemaPresentationGateway(
    private val model: CinemaViewModel,
    private val session: HybridPresentationState,
    val images: HybridImages,
    private val effects: HybridDeviceEffects,
    private val exit: () -> Unit,
) : AutoCloseable {
    private val items = mutableMapOf<String, MediaItem>()
    private val channels = mutableMapOf<String, TvChannel>()
    private val liveSources = mutableMapOf<String, LiveSource>()
    private val episodes = mutableMapOf<String, Pair<String, Int>>()
    private var lastPayload = ""
    private var revision = 0L

    fun routeKey(): String = session.ids.key("route", model.focusRoute)
    private fun routeState() = session.route(routeKey())
    private fun mediaKey(item: MediaItem) = session.ids.key("media", item.presentationKey)
    private fun channelKey(channel: TvChannel) = session.ids.key("channel", "${model.activeLiveSource?.key}\u0000${channel.id}\u0000${channel.url}")
    private fun sourceKey(source: LiveSource) = session.ids.key("live", "${source.key}\u0000${source.url}")
    private fun episodeKey(item: MediaItem, index: Int) = session.ids.key("episode",
        "${item.presentationKey}\u0000${MediaDomainMapper.episodeId(item, index)}\u0000$index\u0000${item.episodes[index]}")

    private fun text(value: String, limit: Int = 120): String = value
        .replace(Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE), "[链接]")
        .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
        .take(limit)

    private fun message(value: String): String {
        var safe = value
        listOf(model.password, model.metadataAccessToken).filter { it.isNotBlank() }.forEach { safe = safe.replace(it, "[已隐藏]") }
        return text(safe, 360)
    }

    private fun card(raw: MediaItem, fullDescription: Boolean = false): JSONObject {
        val item = model.repo.library.snapshot.applyProgress(raw)
        val key = mediaKey(item)
        items[key] = item
        val rating = RatingPolicy.score(item)
        return JSONObject().put("key", key).put("title", text(item.title)).put("year", text(item.year, 16))
            .put("category", text(item.category, 80)).put("description", text(item.description, if (fullDescription) 1200 else 360))
            .put("poster", images.register(item.poster, expiresAt = item.catalogLicenseExpiresAt))
            .put("backdrop", images.register(item.backdrop, landscape = true, expiresAt = item.catalogLicenseExpiresAt))
            .put("rating", if (rating != null) text(item.rating, 12) else "")
            .put("ratingSource", if (rating != null) text(item.ratingSource.ifBlank { "来源评分" }, 40) else "")
            .put("isTv", item.isTv).put("favorite", model.repo.isFavorite(item))
            .put("status", model.repo.libraryStatus(item)?.name.orEmpty())
            .put("positionMs", item.positionMs.coerceAtLeast(0)).put("durationMs", item.durationMs.coerceAtLeast(0))
            .put("lastEpisode", item.lastEpisode.coerceAtLeast(0)).put("sourceName", text(item.source?.name.orEmpty(), 80))
            .put("attribution", text(item.catalogAttribution, 300))
    }

    private fun episode(item: MediaItem, index: Int): JSONObject {
        val key = episodeKey(item, index)
        episodes[key] = mediaKey(item) to index
        val progress = model.repo.library.snapshot.progress(item, index)
        return JSONObject().put("id", key).put("index", index).put("label", text(model.episodeLabel(index), 120))
            .put("progress", if (progress != null && progress.durationMs > 0)
                (progress.positionMs.toDouble() / progress.durationMs).coerceIn(0.0, 1.0) else JSONObject.NULL)
    }

    private fun library(filter: String): List<MediaItem> = when (filter) {
        "HISTORY" -> model.history()
        "FAVORITES" -> model.favorites()
        "ALL" -> WorkNavigationProjection.deduplicate(model.repo.library.snapshot.records
            .filter { it.favorite || it.status != null || it.historyAt != null }.map { it.item }, associations = model.workAssociations)
        else -> model.libraryByStatus(LibraryStatus.valueOf(filter))
    }.mapNotNull { it.usableMetadata() }

    private fun baseCatalog(): List<MediaItem> = when (model.page) {
        Page.MOVIES -> model.browseMovies
        Page.SERIES -> model.browseSeries
        Page.EXPLORE -> (model.browseMovies + model.browseSeries).distinctBy { it.presentationKey }
        Page.SEARCH -> model.results
        Page.LIBRARY -> library(routeState().libraryFilter)
        else -> emptyList()
    }.mapNotNull { it.usableMetadata() }

    private fun filteredCatalog(): List<MediaItem> {
        val all = baseCatalog()
        if (model.page !in setOf(Page.MOVIES, Page.SERIES, Page.EXPLORE)) return all
        val filters = routeState().catalogFilter
        return CatalogFilter(filters.category.ifBlank { null }, filters.year.ifBlank { null }, filters.rated).apply(all)
    }

    /** Called on the UI thread from snapshotFlow; all reads are immutable in-memory values. */
    fun payload(): JSONObject {
        @Suppress("UNUSED_VARIABLE") val observe = model.libraryVersion to session.change
        val state = routeState()
        items.clear(); channels.clear(); liveSources.clear(); episodes.clear()
        val all = baseCatalog()
        val visible = filteredCatalog()
        val total = if (model.page == Page.LIVE) model.channels.size else visible.size
        val page = state.page.coerceIn(0, ((total - 1).coerceAtLeast(0) / HybridProtocol.PAGE_SIZE))
        val sliced = visible.drop(page * HybridProtocol.PAGE_SIZE).take(HybridProtocol.PAGE_SIZE)
        val selected = model.selected?.takeIf { model.page in setOf(Page.DETAIL, Page.PLAYER) }?.usableMetadata()
            ?.let(model.repo.library.snapshot::applyProgress)
        val scene = if (model.page == Page.DETAIL) selected else model.focused?.usableMetadata()
            ?: (sliced.firstOrNull() ?: model.featured.firstOrNull()?.usableMetadata())
        val result = JSONObject().put("protocol", 1).put("route", model.page.name).put("routeKey", routeKey())
            .put("section", model.section.name).put("busy", model.busy || (model.page == Page.SEARCH && (model.chartLoading || model.searchCatalogLoading)) ||
                (model.page == Page.MOVIES && model.movieBrowse.loading) || (model.page == Page.SERIES && model.seriesBrowse.loading))
            .put("message", message(model.message)).put("quality", model.glassQuality.name)
            .put("reducedMotion", model.reducedMotion).put("lowMemory", effects.lowRam)
            .put("effectiveQuality", effects.effective(model.glassQuality)).put("effectsLimited", effects.constrained)
            .put("connected", model.repo.isConnected).put("authorized", model.authorized)
            .put("version", BuildConfig.VERSION_NAME).put("channel", BuildConfig.UPDATE_CHANNEL)
            .put("scene", scene?.let { card(it, true) } ?: JSONObject.NULL)
            .put("selected", selected?.let { card(it, true) } ?: JSONObject.NULL)
            .put("items", JSONArray(sliced.map { card(it) })).put("total", total).put("pageIndex", page)
            .put("pageSize", HybridProtocol.PAGE_SIZE).put("canLoadMore", when (model.page) {
                Page.MOVIES -> model.movieBrowse.canLoadMore
                Page.SERIES -> model.seriesBrowse.canLoadMore
                Page.EXPLORE -> model.movieBrowse.canLoadMore || model.seriesBrowse.canLoadMore
                Page.SEARCH -> model.canLoadSearchCatalog
                else -> false
            })
            .put("notice", text(when (model.page) {
                Page.DETAIL -> model.resolutionStatus
                Page.MOVIES -> model.movieBrowse.description()
                Page.SERIES -> model.seriesBrowse.description()
                Page.SEARCH -> listOf(model.searchStage, model.catalogStatus).filter(String::isNotBlank).joinToString(" · ")
                else -> model.catalogStatus
            }, 600))
            .put("attribution", text(model.catalogAttribution, 1000))
            .put("query", text(model.query, 120)).put("history", JSONArray(model.searchHistory.take(20).map { text(it, 120) }))
            .put("filters", JSONObject().put("type", when (model.searchFilters.isTv) { true -> "SERIES"; false -> "MOVIES"; null -> "" })
                .put("year", model.searchFilters.year.orEmpty()).put("minRating", model.searchFilters.minRating ?: 0)
                .put("topOnly", model.searchFilters.doubanTopOnly))
            .put("years", JSONArray(model.searchYears.take(100)))
            .put("catalogFilters", JSONObject().put("category", state.catalogFilter.category).put("year", state.catalogFilter.year).put("rated", state.catalogFilter.rated))
            // The displayed filter value must be the exact value the native projection can match.
            .put("categories", JSONArray(all.map { it.category }.filter { it.isNotBlank() && it.length <= 80 && text(it, 80) == it }.distinct().take(24)))
            .put("catalogYears", JSONArray(all.map { it.year }.filter { it.matches(Regex("\\d{4}")) }.distinct().sortedDescending().take(100)))
            .put("libraryFilter", state.libraryFilter).put("bookmark", state.bookmark ?: JSONObject.NULL)
            .put("hasHistory", model.history().isNotEmpty()).put("quarantinedProgressCount", model.quarantinedProgressCount)
            .put("qualityOptions", JSONArray(listOf("AUTO" to "自动", "BASIC" to "清晰", "BACKDROP" to "柔和玻璃", "ENHANCED" to "增强玻璃")
                .map { (id, title) -> JSONObject().put("id", id).put("title", title) }))
        val shelves = JSONArray()
        if (model.page == Page.HOME) {
            fun shelf(id: String, title: String, values: List<MediaItem>, limit: Int) {
                val allowed = values.mapNotNull { it.usableMetadata() }.distinctBy { it.presentationKey }.take(limit)
                if (allowed.isNotEmpty()) shelves.put(JSONObject().put("id", id).put("title", title).put("items", JSONArray(allowed.map { card(it) })))
            }
            shelf("continue", "继续观看", model.history(), 40)
            shelf("movies", "为你精选", model.featured, 24)
            shelf("series", "值得追的故事", model.tvShows, 24)
        }
        result.put("shelves", shelves)
        val episodeTotal = selected?.episodes?.size ?: 0
        val episodePage = state.episodePage.coerceIn(0, (episodeTotal - 1).coerceAtLeast(0) / HybridProtocol.EPISODE_PAGE_SIZE)
        result.put("episodeTotal", episodeTotal).put("episodePage", episodePage)
            .put("episodes", JSONArray(selected?.let { item -> item.episodes.indices.drop(episodePage * HybridProtocol.EPISODE_PAGE_SIZE)
                .take(HybridProtocol.EPISODE_PAGE_SIZE).map { episode(item, it) } }.orEmpty()))
            .put("resumeEpisode", selected?.takeIf { it.episodes.isNotEmpty() }?.let {
                episode(it, it.lastEpisode.coerceIn(it.episodes.indices))
            } ?: JSONObject.NULL)
            .put("alternatives", JSONArray(if (model.page == Page.DETAIL) model.alternatives.take(16).mapNotNull { it.usableMetadata() }.map { card(it) } else emptyList<JSONObject>()))
        val shownChannels = if (model.page == Page.LIVE) model.channels.drop(page * HybridProtocol.PAGE_SIZE).take(HybridProtocol.PAGE_SIZE) else emptyList()
        result.put("channels", JSONArray(shownChannels.map { channel ->
            val key = channelKey(channel); channels[key] = channel
            JSONObject().put("id", key).put("name", text(channel.name, 120)).put("group", text(channel.group, 80))
                .put("logo", images.register(channel.logo))
        })).put("activeChannel", model.activeChannel?.let(::channelKey).orEmpty())
            .put("liveSources", JSONArray(model.liveSources.take(32).map { source ->
                val key = sourceKey(source); liveSources[key] = source
                JSONObject().put("id", key).put("name", text(source.name, 80))
            })).put("activeLiveSource", model.activeLiveSource?.let(::sourceKey).orEmpty())
            .put("programs", JSONArray(if (model.page == Page.LIVE) model.epgPrograms.take(48).map {
                JSONObject().put("title", text(it.title, 140)).put("start", it.start).put("stop", it.stop)
            } else emptyList<JSONObject>()))
        return result
    }

    fun snapshot(): JSONObject = stamp(payload())

    fun stamp(payload: JSONObject): JSONObject {
        val plain = payload.toString()
        if (plain != lastPayload) { revision++; lastPayload = plain }
        return JSONObject(plain).put("revision", revision)
    }

    fun back() {
        if (model.page == Page.HOME) exit() else model.back()
    }

    fun submitSearch(query: String) {
        model.query = query.trim().take(120)
        if (model.query.isBlank()) return
        model.search()
        routeState().page = 0
        routeState().bookmark = null
        session.changed()
    }

    private fun selected(params: JSONObject): MediaItem {
        val key = HybridProtocol.string(params, "key", 64)
        val selected = model.selected?.usableMetadata() ?: throw HybridRequestFailure("CONFLICT")
        if (model.page != Page.DETAIL || mediaKey(selected) != key) throw HybridRequestFailure("CONFLICT")
        return selected
    }

    private fun item(params: JSONObject): MediaItem = items[HybridProtocol.string(params, "key", 64)]
        ?.usableMetadata() ?: throw HybridRequestFailure("CONFLICT")

    private fun channel(params: JSONObject): TvChannel {
        if (model.page != Page.LIVE) throw HybridRequestFailure("CONFLICT")
        val known = channels[HybridProtocol.string(params, "id", 64)] ?: throw HybridRequestFailure("CONFLICT")
        return model.channels.firstOrNull { channelKey(it) == channelKey(known) } ?: throw HybridRequestFailure("CONFLICT")
    }

    /** Invoked only by the guarded top-frame bridge, on the UI dispatcher. */
    suspend fun dispatch(request: HybridRequest): JSONObject {
        val p = request.params
        when (request.method) {
            "app.ready" -> if (HybridProtocol.integer(p, "protocol", 100) != 1) throw HybridRequestFailure("PROTOCOL_UNSUPPORTED")
            "app.fatal" -> session.fail("界面暂时无法继续显示，可以重新打开界面。")
            "navigation.go" -> {
                val route = Page.entries.firstOrNull { it.name == HybridProtocol.string(p, "route", 16) && it !in setOf(Page.DETAIL, Page.PLAYER) }
                    ?: throw HybridRequestFailure("BAD_PARAMS")
                model.navigate(route)
            }
            "navigation.back" -> back()
            "navigation.bookmark" -> {
                routeState().bookmark = HybridProtocol.bookmark(p.optJSONObject("bookmark") ?: throw HybridRequestFailure("BAD_PARAMS"), routeKey())
                return JSONObject().put("accepted", true)
            }
            "media.open" -> { model.open(item(p)); routeState().episodePage = 0 }
            "media.focus" -> { model.onMediaFocus(item(p), "hybrid"); return JSONObject().put("accepted", true) }
            "catalog.page" -> {
                val page = HybridProtocol.integer(p, "page", 10000)
                val total = if (model.page == Page.LIVE) model.channels.size else filteredCatalog().size
                if (page > (total - 1).coerceAtLeast(0) / HybridProtocol.PAGE_SIZE) throw HybridRequestFailure("CONFLICT")
                routeState().page = page; routeState().bookmark = null; session.changed()
            }
            "catalog.filter" -> {
                if (model.page !in setOf(Page.MOVIES, Page.SERIES, Page.EXPLORE)) throw HybridRequestFailure("CONFLICT")
                routeState().catalogFilter = HybridCatalogFilter(HybridProtocol.string(p, "category", 80, true),
                    HybridProtocol.string(p, "year", 4, true), HybridProtocol.boolean(p, "rated"))
                routeState().page = 0; routeState().bookmark = null; session.changed()
            }
            "catalog.more" -> when (model.page) {
                Page.MOVIES -> model.loadMoreCatalog(false)
                Page.SERIES -> model.loadMoreCatalog(true)
                Page.EXPLORE -> { model.loadMoreCatalog(false); model.loadMoreCatalog(true) }
                Page.SEARCH -> model.loadMoreSearchCatalog()
                else -> throw HybridRequestFailure("CONFLICT")
            }
            "catalog.refresh" -> model.refreshCatalog()
            "search.input" -> { if (model.page != Page.SEARCH) throw HybridRequestFailure("CONFLICT"); session.searchInput = true }
            "search.submit" -> submitSearch(HybridProtocol.string(p, "query", 120))
            "search.filter" -> {
                val type = HybridProtocol.string(p, "type", 8, true)
                if (type !in setOf("", "MOVIES", "SERIES")) throw HybridRequestFailure("BAD_PARAMS")
                val year = HybridProtocol.string(p, "year", 4, true)
                if (year.isNotEmpty() && !year.matches(Regex("\\d{4}"))) throw HybridRequestFailure("BAD_PARAMS")
                val rating = (p.opt("minRating") as? Number)?.toDouble() ?: throw HybridRequestFailure("BAD_PARAMS")
                if (!rating.isFinite() || rating !in 0.0..10.0) throw HybridRequestFailure("BAD_PARAMS")
                model.updateSearchFilters(SearchFilters(isTv = when (type) { "MOVIES" -> false; "SERIES" -> true; else -> null },
                    year = year.ifBlank { null }, minRating = rating.takeIf { it > 0 }, doubanTopOnly = HybridProtocol.boolean(p, "topOnly")))
                routeState().page = 0; routeState().bookmark = null; session.changed()
            }
            "search.year" -> { model.cycleSearchYear(); routeState().page = 0; routeState().bookmark = null; session.changed() }
            "detail.sources" -> { if (model.page != Page.DETAIL) throw HybridRequestFailure("CONFLICT"); model.findAlternatives() }
            "detail.episodes" -> {
                val page = HybridProtocol.integer(p, "page", 10000)
                val count = model.selected?.episodes?.size ?: 0
                if (model.page != Page.DETAIL || page > (count - 1).coerceAtLeast(0) / HybridProtocol.EPISODE_PAGE_SIZE) throw HybridRequestFailure("CONFLICT")
                routeState().episodePage = page; routeState().bookmark = null; session.changed()
            }
            "playback.open" -> {
                val item = selected(p)
                val token = HybridProtocol.string(p, "episodeId", 64)
                val choice = episodes[token] ?: throw HybridRequestFailure("CONFLICT")
                if (choice.first != mediaKey(item) || choice.second !in item.episodes.indices ||
                    episodeKey(item, choice.second) != token) throw HybridRequestFailure("CONFLICT")
                model.playEpisode(choice.second)
            }
            "library.favorite" -> model.repo.setFavorite(selected(p), HybridProtocol.boolean(p, "favorite"))
            "library.status" -> {
                val item = selected(p)
                val value = HybridProtocol.string(p, "status", 24, true)
                val status = LibraryStatus.entries.firstOrNull { it.name == value && it != LibraryStatus.FAVORITE }
                if (value.isNotEmpty() && status == null) throw HybridRequestFailure("BAD_PARAMS")
                model.repo.setLibraryStatus(item, status)
            }
            "library.filter" -> {
                val value = HybridProtocol.string(p, "filter", 24)
                if (model.page != Page.LIBRARY || value !in setOf("ALL", "HISTORY", "FAVORITES", "WANT_TO_WATCH", "WATCHING", "WATCHED", "DROPPED")) throw HybridRequestFailure("BAD_PARAMS")
                routeState().libraryFilter = value; routeState().page = 0; routeState().bookmark = null; session.changed()
            }
            "library.clearHistory" -> { if (model.page != Page.LIBRARY) throw HybridRequestFailure("CONFLICT"); session.clearHistoryConfirmation = true }
            "live.source" -> {
                if (model.page != Page.LIVE) throw HybridRequestFailure("CONFLICT")
                val source = liveSources[HybridProtocol.string(p, "id", 64)] ?: throw HybridRequestFailure("CONFLICT")
                if (model.liveSources.none { sourceKey(it) == sourceKey(source) }) throw HybridRequestFailure("CONFLICT")
                routeState().page = 0; routeState().bookmark = null; model.loadChannels(source); session.changed()
            }
            "live.focus" -> { model.focusChannel(channel(p)); return JSONObject().put("accepted", true) }
            "live.play" -> model.playChannel(channel(p))
            "settings.motion" -> model.updateReducedMotion(HybridProtocol.boolean(p, "reduced"))
            "settings.quality" -> {
                val value = HybridProtocol.string(p, "quality", 16)
                model.updateGlassQuality(GlassQuality.entries.firstOrNull { it.name == value } ?: throw HybridRequestFailure("BAD_PARAMS"))
            }
            "native.open" -> {
                val target = HybridProtocol.string(p, "target", 16)
                if (target !in setOf("SETTINGS", "DETAIL") || (target == "DETAIL" && model.page != Page.DETAIL)) throw HybridRequestFailure("BAD_PARAMS")
                val section = if (p.has("section")) HybridProtocol.string(p, "section", 16) else "connection"
                session.nativeSettingsSection = when (section) { "connection" -> 0; "sources" -> 1; "updates", "advanced" -> 3; else -> throw HybridRequestFailure("BAD_PARAMS") }
                session.nativeReturnPage = model.page
                if (target == "SETTINGS") {
                    model.focusCoordinator.remember(Page.SETTINGS.name, "settings-menu", session.nativeSettingsSection.toString())
                    model.navigate(Page.SETTINGS)
                }
                session.nativePanel = if (target == "DETAIL") Page.DETAIL else Page.SETTINGS
            }
            "message.dismiss" -> model.dismissMessage()
            else -> throw HybridRequestFailure("METHOD_NOT_ALLOWED")
        }
        return JSONObject().put("accepted", true).put("state", snapshot())
    }

    fun closeNativePanel() {
        val target = session.nativePanel ?: return
        session.nativePanel = null
        if (target == Page.SETTINGS && session.nativeReturnPage !in setOf(Page.SETTINGS, Page.DETAIL, Page.PLAYER))
            model.navigate(session.nativeReturnPage)
    }

    override fun close() { images.close(); items.clear(); channels.clear(); liveSources.clear(); episodes.clear() }
}
