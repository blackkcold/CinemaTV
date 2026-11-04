package dev.cinematv.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CatalogStatusTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private fun message(acquisition: CatalogAcquisition, count: Int = 1) = CatalogStatus.message(
        CatalogChartSnapshot(MetadataCatalog(acquisition = acquisition),
            List(count) { MediaItem("$it", "Synthetic $it") }, pagingComplete = true), now, utc)

    @Test fun partialSnapshotNeverClaimsCompleteOrUsesFilteredCountAsSnapshotTotal() {
        val text = message(CatalogAcquisition(availableTotal = 50, state = CatalogAcquisitionState.PARTIAL))
        assertTrue(text.contains("快照未完整 50/250"))
        assertTrue(text.contains("已载入 1 条匹配记录"))
        assertFalse(text.contains("快照完整"))
    }

    @Test fun completeSnapshotSeparatesFullCountFromQueryMatches() {
        val text = message(CatalogAcquisition(complete = true, availableTotal = 250,
            state = CatalogAcquisitionState.FRESH), 3)
        assertTrue(text.contains("快照完整 250/250"))
        assertTrue(text.contains("已载入 3 条匹配记录"))
    }

    @Test fun inconsistentCompleteFlagCannotClaimFullChart() {
        assertTrue(message(CatalogAcquisition(complete = true, availableTotal = 50)).contains("快照未完整 50/250"))
        val malformed = message(CatalogAcquisition(complete = true, expectedTotal = 50, availableTotal = 50))
        assertTrue(malformed.contains("快照未完整"))
        assertFalse(malformed.contains("快照完整"))
    }

    @Test fun staleCacheKeepsAsOfTimeAndNeverClaimsLatest() {
        val text = message(CatalogAcquisition(source = "stale-cache", stale = true,
            fetchedAt = now - 86_400_000, availableTotal = 25))
        assertTrue(text.contains("旧缓存"))
        assertTrue(text.contains("截至 2026-11-03 12:00 UTC"))
        assertFalse(text.contains("最新"))
    }

    @Test fun readableRetryTimeAndReasonNeverExposeProviderWarning() {
        val text = message(CatalogAcquisition(state = CatalogAcquisitionState.RATE_LIMITED,
            retryAt = now + 60_000, warnings = listOf("internal-token-and-stack-trace")))
        assertTrue(text.contains("访问频率受限"))
        assertTrue(text.contains("2026-11-04 12:01 UTC 后可重试"))
        assertFalse(text.contains("internal-token"))
    }

    @Test fun oldBffDoesNotInventFreshnessOrCompleteness() {
        val text = CatalogStatus.message(CatalogChartSnapshot(items = listOf(MediaItem("1", "Synthetic"))), now, utc)
        assertTrue(text.contains("已载入 1 条"))
        assertTrue(text.contains("未提供快照完整性与更新时间"))
        assertFalse(text.contains("250/250"))
    }

    @Test fun knownRightsWarningsBecomeReadableActivationReasonsOnly() {
        mapOf("written_permission_required" to "需服务端确认适用书面授权",
            "invalid_rights_declaration" to "服务端授权声明无效",
            "rights_expired" to "适用授权已过期").forEach { (code, reason) ->
            val text = message(CatalogAcquisition(warnings = listOf(code, "private-internal-details")))
            assertTrue(text.contains(reason))
            assertFalse(text.contains(code))
            assertFalse(text.contains("private-internal-details"))
        }
    }

    @Test fun expirySupersedesAnyFreshCompleteClaim() {
        val catalog = MetadataCatalog(acquisition = CatalogAcquisition(complete = true,
            availableTotal = 250, state = CatalogAcquisitionState.FRESH), licenseExpiresAt = now)
        assertTrue(CatalogStatus.message(CatalogChartSnapshot(catalog), now, utc).contains("授权已过期"))
        assertFalse(CatalogStatus.message(CatalogChartSnapshot(catalog), now, utc).contains("完整"))
        assertTrue(CatalogStatus.message(CatalogChartSnapshot(MetadataCatalog(licenseRejected = true)), now, utc)
            .contains("授权已过期或无效"))
    }

    @Test fun onlyRefreshingSnapshotsMayPoll() {
        assertNull(CatalogRefreshPolicy.nextDelay(null, now, now + 90_000))
        assertNull(CatalogRefreshPolicy.nextDelay(CatalogAcquisition(), now, now + 90_000))
        assertEquals(5_000L, CatalogRefreshPolicy.nextDelay(CatalogAcquisition(refreshing = true), now, now + 90_000))
    }

    @Test fun stoppedStatesNeverPollEvenIfRefreshingAndRetryHasPassed() {
        listOf(CatalogAcquisitionState.ACCESS_BLOCKED, CatalogAcquisitionState.DISABLED,
            CatalogAcquisitionState.RATE_LIMITED).forEach { state ->
            assertNull(CatalogRefreshPolicy.nextDelay(CatalogAcquisition(state = state,
                refreshing = true, retryAt = now - 1), now, now + 90_000))
        }
        assertNull(CatalogRefreshPolicy.nextDelay(CatalogAcquisition(refreshing = true, blocked = true), now, now + 90_000))
        assertTrue(message(CatalogAcquisition(refreshing = true, blocked = true,
            state = CatalogAcquisitionState.FRESH)).contains("来源限制访问"))
    }

    @Test fun pollingHonorsRetryAtAndNinetySecondWindow() {
        val acquisition = CatalogAcquisition(refreshing = true, retryAt = now + 30_000)
        assertEquals(30_000L, CatalogRefreshPolicy.nextDelay(acquisition, now, now + 90_000))
        assertNull(CatalogRefreshPolicy.nextDelay(acquisition, now, now + 29_999))
        assertNull(CatalogRefreshPolicy.nextDelay(CatalogAcquisition(refreshing = true), now, now + 4_999))
        assertEquals(90_000L, CatalogRefreshPolicy.WINDOW_MS)
    }

    @Test fun rejectedOrExpiredLicenseStopsPollingAtBoundary() {
        val catalog = MetadataCatalog(acquisition = CatalogAcquisition(refreshing = true), licenseExpiresAt = now)
        assertNull(CatalogRefreshPolicy.nextDelay(catalog, now, now + 90_000))
        assertNull(CatalogRefreshPolicy.nextDelay(catalog.copy(licenseExpiresAt = 0, licenseRejected = true), now, now + 90_000))
        assertEquals(5_000L, CatalogRefreshPolicy.nextDelay(catalog.copy(licenseExpiresAt = now + 1), now, now + 90_000))
    }
}
