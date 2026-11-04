package dev.cinematv.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Provider state is explicit: absence of licensed data never becomes a fake rating. */
data class MetadataCatalog(
    val items: List<MediaItem> = emptyList(),
    val provider: String = "",
    val attribution: String = "",
    val available: Boolean = false,
    val nextOffset: Int? = null,
    val acquisition: CatalogAcquisition? = null,
    val licenseExpiresAt: Long = 0L,
    val licenseRejected: Boolean = false
)

enum class CatalogAcquisitionState {
    FRESH, REFRESHING, PARTIAL, RATE_LIMITED, ACCESS_BLOCKED, DISABLED, UNAVAILABLE
}

/** Snapshot totals describe the provider's whole chart, not the filtered response page. */
data class CatalogAcquisition(
    val source: String? = null,
    val fetchedAt: Long? = null,
    val stale: Boolean = false,
    val complete: Boolean = false,
    val expectedTotal: Int = 250,
    val availableTotal: Int = 0,
    val warnings: List<String> = emptyList(),
    val blocked: Boolean = false,
    val retryAt: Long? = null,
    val snapshotId: String? = null,
    val refreshing: Boolean = false,
    val state: CatalogAcquisitionState = CatalogAcquisitionState.UNAVAILABLE
)

data class CatalogChartSnapshot(
    val catalog: MetadataCatalog = MetadataCatalog(),
    val items: List<MediaItem> = emptyList(),
    val pagingComplete: Boolean = false,
    val changedDuringPaging: Boolean = false
)

/** No retries for denial/rate limiting. The caller owns cancellation and generation checks. */
object CatalogRefreshPolicy {
    const val WINDOW_MS = 90_000L
    const val INTERVAL_MS = 5_000L

    fun nextDelay(catalog: MetadataCatalog, nowMs: Long, deadlineMs: Long): Long? {
        if (catalog.licenseRejected || (catalog.licenseExpiresAt > 0 && catalog.licenseExpiresAt <= nowMs)) return null
        return nextDelay(catalog.acquisition, nowMs, deadlineMs)
    }

    fun nextDelay(acquisition: CatalogAcquisition?, nowMs: Long, deadlineMs: Long): Long? {
        if (acquisition == null || !acquisition.refreshing || acquisition.blocked ||
            acquisition.state in setOf(CatalogAcquisitionState.ACCESS_BLOCKED,
                CatalogAcquisitionState.DISABLED, CatalogAcquisitionState.RATE_LIMITED)) return null
        val delay = maxOf(INTERVAL_MS, (acquisition.retryAt ?: nowMs) - nowMs)
        return delay.takeIf { deadlineMs - nowMs >= it }
    }
}

/** User-visible status deliberately never prints raw provider warnings or transport errors. */
object CatalogStatus {
    fun message(snapshot: CatalogChartSnapshot, nowMs: Long = System.currentTimeMillis(),
                zone: ZoneId = ZoneId.systemDefault()): String {
        val catalog = snapshot.catalog
        if (catalog.licenseRejected || (catalog.licenseExpiresAt > 0 && catalog.licenseExpiresAt <= nowMs)) {
            return "目录授权已过期或无效，需更新授权后查看。"
        }
        if (snapshot.changedDuringPaging) return "榜单快照在加载时发生变化，已停止本次加载，请重新打开榜单。"
        val acquisition = catalog.acquisition
        if (acquisition == null) return if (snapshot.items.isNotEmpty())
            "已载入 ${snapshot.items.size} 条匹配记录 · 服务端未提供快照完整性与更新时间" else
            "授权榜单暂无匹配记录，或目录暂不可用。"
        fun date(value: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(zone)
            .format(Instant.ofEpochMilli(value))
        val parts = mutableListOf<String>()
        val state = if (acquisition.blocked && acquisition.state !in setOf(CatalogAcquisitionState.DISABLED,
                CatalogAcquisitionState.RATE_LIMITED)) CatalogAcquisitionState.ACCESS_BLOCKED else acquisition.state
        val rightsReason = when {
            "rights_expired" in acquisition.warnings -> "适用授权已过期，需服务端更新授权"
            "invalid_rights_declaration" in acquisition.warnings -> "服务端授权声明无效，请核对适用书面授权"
            "written_permission_required" in acquisition.warnings -> "需服务端确认适用书面授权后启用"
            else -> null
        }
        parts += rightsReason ?: when (state) {
            CatalogAcquisitionState.FRESH -> "榜单快照"
            CatalogAcquisitionState.REFRESHING -> "榜单后台更新中"
            CatalogAcquisitionState.PARTIAL -> "榜单尚未完整获取"
            CatalogAcquisitionState.RATE_LIMITED -> "访问频率受限，已暂停更新"
            CatalogAcquisitionState.ACCESS_BLOCKED -> "来源限制访问，需服务端处理后重试"
            CatalogAcquisitionState.DISABLED -> "豆瓣获取未启用，请检查服务端授权配置"
            CatalogAcquisitionState.UNAVAILABLE -> "榜单暂不可用"
        }
        if (acquisition.refreshing && acquisition.state != CatalogAcquisitionState.REFRESHING && !acquisition.blocked &&
            acquisition.state !in setOf(CatalogAcquisitionState.RATE_LIMITED, CatalogAcquisitionState.ACCESS_BLOCKED,
                CatalogAcquisitionState.DISABLED)) parts += "后台更新中"
        val complete = acquisition.complete && acquisition.availableTotal == 250 && acquisition.expectedTotal == 250
        parts += "快照${if (complete) "完整" else "未完整"} ${acquisition.availableTotal}/${acquisition.expectedTotal}"
        parts += "已载入 ${snapshot.items.count { CatalogLicensePolicy.isAvailable(it, nowMs) }} 条匹配记录"
        if (acquisition.stale || acquisition.source == "stale-cache") parts += "旧缓存"
        else if (acquisition.source == "cache") parts += "缓存"
        acquisition.fetchedAt?.let { parts += "截至 ${date(it)}" }
        acquisition.retryAt?.takeIf { it > nowMs }?.let { parts += "${date(it)} 后可重试" }
        if (!snapshot.pagingComplete && snapshot.items.isNotEmpty()) parts += "匹配记录尚未载完"
        return parts.joinToString(" · ")
    }
}

/**
 * Publishes a first page immediately, then replaces that snapshot as pages arrive.
 * A changed snapshot is discarded and restarted once; pages from different rankings
 * are never combined. Old BFF responses without acquisition retain bounded paging.
 */
object CatalogChartPaging {
    suspend fun load(
        fetch: suspend (offset: Int) -> MetadataCatalog,
        onSnapshot: (CatalogChartSnapshot) -> Unit
    ): CatalogChartSnapshot {
        repeat(2) { attempt ->
            var offset = 0
            var first: MetadataCatalog? = null
            var items = emptyList<MediaItem>()
            for (pageIndex in 0 until 5) {
                val page = fetch(offset)
                val origin = first
                if (origin != null && (origin.provider != page.provider ||
                    origin.acquisition?.snapshotId != page.acquisition?.snapshotId)) {
                    val discarded = CatalogChartSnapshot(changedDuringPaging = true)
                    onSnapshot(discarded)
                    if (attempt == 1) return discarded
                    break
                }
                if (first == null) first = page
                items = if (page.licenseRejected) emptyList() else (items + page.items).distinctBy { it.id }.take(250)
                val next = page.nextOffset?.takeIf { it > offset && it < 250 && page.items.isNotEmpty() }
                val snapshot = CatalogChartSnapshot(page, items,
                    pagingComplete = page.nextOffset == null)
                onSnapshot(snapshot)
                if (next == null || pageIndex == 4 || page.licenseRejected) return snapshot
                offset = next
            }
        }
        error("Unreachable chart paging state")
    }
}
