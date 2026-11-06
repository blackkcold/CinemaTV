package dev.cinematv.data

/** One explicit user request loads one page. Never silently cross provider snapshots. */
data class CatalogBrowseState(
    val catalog: MetadataCatalog? = null,
    val items: List<MediaItem> = emptyList(),
    val nextOffset: Int? = 0,
    val pages: Int = 0,
    val loading: Boolean = false,
    val notice: String = "",
    val restartRequired: Boolean = false,
    val retryableFailure: Boolean = false
) {
    val canLoadMore: Boolean get() = nextOffset != null && !loading && !restartRequired
    fun description(nowMs: Long = System.currentTimeMillis()): String = when {
        loading -> "正在加载目录 · 已载入 ${items.size} 条"
        catalog?.licenseExpiresAt?.let { it > 0 && it <= nowMs } == true -> "目录授权已过期，请刷新目录。"
        notice.isNotBlank() -> notice
        catalog == null -> ""
        nextOffset != null -> "已载入 ${items.size} 条 · 还有更多，当前筛选仅适用于已载入内容"
        else -> "已载入 ${items.size} 条 · 匹配目录已载完"
    }
}

object CatalogBrowsePaging {
    const val MAX_PAGES = 100
    const val MAX_ITEMS = 5000

    fun append(state: CatalogBrowseState, page: MetadataCatalog, offset: Int,
               nowMs: Long = System.currentTimeMillis()): CatalogBrowseState {
        require(offset == state.nextOffset) { "Unexpected catalog cursor" }
        if (page.licenseRejected || (page.licenseExpiresAt > 0 && page.licenseExpiresAt <= nowMs))
            return state.copy(items = emptyList(), loading = false, nextOffset = null,
                notice = "目录授权已过期或无效，请刷新目录。", restartRequired = true, retryableFailure = false)
        if (!page.available) return state.copy(loading = false, retryableFailure = true,
            notice = "目录暂不可用，已保留成功读取的结果；可重试。")
        val first = state.catalog
        if (first != null && (first.provider != page.provider ||
                first.acquisition?.snapshotId != page.acquisition?.snapshotId ||
                first.licenseExpiresAt != page.licenseExpiresAt))
            return state.copy(loading = false, nextOffset = null, restartRequired = true, retryableFailure = false,
                notice = "目录快照或授权已变化，已停止加载；请刷新目录。")
        val items = (state.items + page.items).mapNotNull { it.usableMetadata(nowMs) }
            .distinctBy { "${it.catalogProvider}:${it.source?.url.orEmpty()}:${it.id}" }
        val pages = state.pages + 1
        val invalidCursor = page.nextOffset?.let { it <= offset || page.items.isEmpty() } == true
        val bounded = items.size >= MAX_ITEMS || pages >= MAX_PAGES || (page.nextOffset != null && page.nextOffset >= MAX_ITEMS)
        return CatalogBrowseState(page, items.take(MAX_ITEMS),
            nextOffset = if (invalidCursor || bounded) null else page.nextOffset,
            pages = pages, restartRequired = invalidCursor,
            notice = when {
                invalidCursor -> "目录分页游标无效，已停止加载；请刷新目录。"
                bounded -> "已达到本次浏览上限（$MAX_ITEMS 条）；请缩小关键词范围继续查找。"
                page.acquisition?.let { !it.complete || it.stale } == true ->
                    "已载入 ${items.size} 条 · 来源快照尚未完整或为旧缓存${if (page.nextOffset != null) " · 可继续加载" else " · 可稍后刷新"}"
                else -> ""
            })
    }
}
