package dev.cinematv.data.douban.public

import dev.cinematv.data.CatalogAcquisition
import dev.cinematv.data.CatalogAcquisitionState
import dev.cinematv.data.MediaItem
import dev.cinematv.data.MetadataCatalog
import dev.cinematv.diagnostics.DiagnosticLog
import java.time.Instant
import java.time.ZoneOffset

/**
 * Adapts the anonymous Top 250 to the catalog contract the paging layer already expects.
 * One 50-item page costs two 25-item Douban reads; the pager loads at most five pages.
 */
class DoubanPublicChart(
    private val repository: DoubanPublicRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun top250(offset: Int): MetadataCatalog {
        if (offset !in 0..249 || offset % 50 != 0) {
            throw DoubanPublicException(DoubanPublicStop.PARSING, "榜单页起点无效")
        }
        val first = repository.top250(offset)
        val second = runCatching { repository.top250(offset + 25) }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (error is DoubanPublicException && error.stop in setOf(
                        DoubanPublicStop.CHALLENGE, DoubanPublicStop.PARSING)) throw error
                DiagnosticLog.event("douban.public.chart.partial", fields = mapOf(
                    "offset" to offset.toString(), "items" to first.items.size.toString(),
                    "stop" to ((error as? DoubanPublicException)?.stop?.name?.lowercase() ?: "unknown")
                ))
                return catalog(first.items, nextOffset = null, offset = offset, stale = first.stale,
                    source = first.source, retryAt = (error as? DoubanPublicException)?.retryAt ?: 0L)
            }
        val items = (first.items + second.items).distinctBy { it.subjectId }
        val next = second.nextStart?.takeIf { it > offset && it < 250 }
        return catalog(items, next, offset, first.stale || second.stale, first.source)
    }

    private fun catalog(items: List<DoubanPublicItem>, nextOffset: Int?, offset: Int,
                        stale: Boolean, source: String, retryAt: Long = 0L): MetadataCatalog {
        val available = offset + items.size
        val complete = nextOffset == null && available >= 250
        return MetadataCatalog(
            items = items.map(::mediaItem),
            provider = PROVIDER,
            attribution = ATTRIBUTION,
            available = items.isNotEmpty(),
            nextOffset = nextOffset,
            acquisition = CatalogAcquisition(
                source = source,
                fetchedAt = now(),
                stale = stale,
                complete = complete,
                expectedTotal = 250,
                availableTotal = available.coerceAtMost(250),
                warnings = if (stale) listOf("stale_public_cache") else emptyList(),
                blocked = false,
                retryAt = retryAt.takeIf { it > now() },
                snapshotId = snapshotId(),
                refreshing = false,
                state = when {
                    complete -> CatalogAcquisitionState.FRESH
                    stale -> CatalogAcquisitionState.PARTIAL
                    else -> CatalogAcquisitionState.PARTIAL
                }
            )
        )
    }

    private fun mediaItem(item: DoubanPublicItem): MediaItem = item.toMediaItem()

    /** Stable for a UTC day so every page of one load belongs to the same snapshot. */
    private fun snapshotId(): String {
        val day = Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC).toLocalDate()
        return "douban-public-top250-$day"
    }

    companion object {
        const val PROVIDER = "douban-public"
        const val ATTRIBUTION = "豆瓣公开页面 · 个人使用 · 不再分发"
    }
}

/** Shared by the chart and tag lists so provenance cannot drift between them. */
fun DoubanPublicItem.toMediaItem(): MediaItem = MediaItem(
    id = "douban:$subjectId",
    title = title,
    poster = cover,
    year = year,
    rating = rating,
    ratingSource = "豆瓣",
    ratingPublic = true,
    doubanTopRank = rank,
    isTv = kind == DoubanPublicKind.TV,
    catalogProvider = DoubanPublicChart.PROVIDER,
    catalogAttribution = DoubanPublicChart.ATTRIBUTION,
    metadataIds = mapOf("douban" to subjectId)
)
