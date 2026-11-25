package dev.cinematv.data.douban.public

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DoubanPublicTest {

    // ---- gate ----

    @Test fun gateSurvivesRestartAndCountsTheWindow() {
        val clock = Clock(1_000L)
        val store = MemoryStore()
        val gate = DoubanPublicGate(store, { 0 }, clock::now)
        gate.attempted(); assertEquals(4_000L, gate.notBefore)
        val restored = DoubanPublicGate(store, { 0 }, clock::now)
        assertEquals(4_000L, restored.notBefore)
        assertEquals(DoubanPublicGate.BUDGET - 1, restored.budgetLeft())
        restored.reset(); assertEquals(0L, restored.notBefore)
    }

    @Test fun longProviderWaitIsReportedInsteadOfSlept() {
        val gate = DoubanPublicGate(now = { 1_000L })
        gate.onThrottled(1_000L + 60 * 60_000L, 1_000L)
        var paused = 0L
        val error = assertThrows(DoubanPublicException::class.java) {
            runBlocking { gate.awaitReady { paused += it } }
        }
        assertEquals(DoubanPublicStop.THROTTLED, error.stop)
        assertTrue(error.retryAt > 1_000L)
        assertEquals(0L, paused)
    }

    @Test fun shortWaitIsSleptAndThenReleased() = runBlocking {
        val clock = Clock(1_000L)
        val gate = DoubanPublicGate(now = clock::now)
        gate.attempted()
        gate.awaitReady { clock.value += it }
        assertTrue(clock.value >= 4_000L)
    }

    @Test fun challengeStopsUntilExplicitReset() {
        val gate = DoubanPublicGate(now = { 5_000L })
        gate.onChallenge()
        assertThrows(DoubanPublicException::class.java) { gate.requireReady() }
        gate.reset()
        gate.requireReady()
    }

    @Test fun gateBlocksTheTwentyFirstRead() {
        val gate = DoubanPublicGate(now = { 1_000L })
        repeat(DoubanPublicGate.BUDGET) { gate.attempted() }
        val error = assertThrows(DoubanPublicException::class.java) { gate.requireReady() }
        assertEquals(DoubanPublicStop.THROTTLED, error.stop)
        assertEquals(0, gate.budgetLeft())
    }

    // ---- parsers ----

    @Test fun top250KeepsRanksAndSkipsDuplicateSubjects() {
        val html = top250Html(0, 25) + top250Html(0, 1)
        val items = DoubanPublicParser.top250(html, 0)
        assertEquals(25, items.size)
        assertEquals(1, items.first().rank)
        assertEquals("9.7", items.first().rating)
        assertEquals("1292053", items.first().subjectId)
        assertEquals(25, items.last().rank)
    }

    @Test fun missingRatingStaysEmpty() {
        val html = "<div class=\"item\"><div class=\"pic\"><a href=\"https://movie.douban.com/subject/42/\">" +
            "<img src=\"https://img.example/p.jpg\"></a></div><div class=\"info\"><span class=\"title\">无名</span></div></div>"
        val items = DoubanPublicParser.top250(html, 0)
        assertEquals(1, items.size)
        assertEquals("", items.first().rating)
    }

    @Test fun challengeBodyIsNotAList() {
        assertTrue(isDoubanChallenge("<html><title>禁止访问</title>豆瓣检测到有异常请求</html>"))
        assertTrue(isDoubanChallenge("<a href=\"https://sec.douban.com/c\">"))
        assertFalse(isDoubanChallenge("<div class=\"item\">正常</div>"))
    }

    @Test fun browseJsonKeepsUnknownFieldsEmpty() {
        val items = DoubanPublicParser.newSearch(
            "{\"data\":[{\"id\":\"7\",\"title\":\"测试\",\"rate\":\"8.7\",\"cover\":\"https://img.example/a.jpg\"}," +
                "{\"id\":\"8\",\"title\":\"无评分\",\"rate\":\"\"}]}",
            0, DoubanPublicKind.MOVIE
        )
        assertEquals(2, items.size)
        assertEquals("8.7", items[0].rating)
        assertEquals("", items[1].rating)
        assertEquals("", items[1].cover)
        assertNull(items[0].rank)
    }

    @Test fun doulistExtractsTitlesAndRatings() {
        val html = "<div class=\"doulist-item\"><div class=\"title\"><a href=\"https://movie.douban.com/subject/11/\">" +
            "  教父  </a></div><img src=\"https://img.example/g.jpg\"><span class=\"rating_nums\">9.3</span></div>"
        val items = DoubanPublicParser.doulist(html)
        assertEquals(1, items.size)
        assertEquals("教父", items.first().title)
        assertEquals("9.3", items.first().rating)
    }

    // ---- chart contract ----

    @Test fun chartBuildsFiftyItemPagesWithOneStableSnapshot() = runBlocking {
        val clock = Clock(0L)
        val transport = FakeTransport { url ->
            val start = Regex("start=([0-9]+)").find(url)!!.groupValues[1].toInt()
            top250Html(start, 25)
        }
        val chart = DoubanPublicChart(repository(transport, clock), clock::now)
        val page = chart.top250(0)
        assertEquals(50, page.items.size)
        assertEquals(50, page.nextOffset)
        assertEquals(1, page.items.first().doubanTopRank)
        assertEquals(50, page.items.last().doubanTopRank)
        assertEquals(DoubanPublicChart.PROVIDER, page.provider)
        assertTrue(page.items.all { it.ratingPublic && it.ratingSource == "豆瓣" })
        val second = chart.top250(50)
        assertEquals(page.acquisition!!.snapshotId, second.acquisition!!.snapshotId)
    }

    @Test fun chartPartialKeepsFirstPageWhenSecondReadFails() = runBlocking {
        val clock = Clock(0L)
        var calls = 0
        val transport = FakeTransport {
            calls++
            if (calls == 2) throw DoubanPublicException(DoubanPublicStop.THROTTLED, "等待", 99_000L)
            top250Html(0, 25)
        }
        val chart = DoubanPublicChart(repository(transport, clock), clock::now)
        val page = chart.top250(0)
        assertEquals(25, page.items.size)
        assertNull(page.nextOffset)
        assertFalse(page.acquisition!!.complete)
        assertEquals(25, page.acquisition!!.availableTotal)
    }

    @Test fun lastPageIsCompleteAndStopsPaging() = runBlocking {
        val clock = Clock(0L)
        val transport = FakeTransport { url ->
            val start = Regex("start=([0-9]+)").find(url)!!.groupValues[1].toInt()
            top250Html(start, 25)
        }
        val chart = DoubanPublicChart(repository(transport, clock), clock::now)
        val page = chart.top250(200)
        assertEquals(50, page.items.size)
        assertNull(page.nextOffset)
        assertTrue(page.acquisition!!.complete)
    }

    // ---- repository ----

    @Test fun freshCacheHitNeverCallsDouban() = runBlocking {
        val clock = Clock(1_000L)
        var calls = 0
        val transport = FakeTransport {
            calls++
            top250Html(0, 25)
        }
        val repo = repository(transport, clock)
        repo.top250(0); assertEquals(1, calls)
        repo.top250(0); assertEquals(1, calls)
    }

    @Test fun staleCacheIsReturnedWhenTheNetworkFails() = runBlocking {
        val clock = Clock(1_000L)
        var fail = false
        val transport = FakeTransport {
            if (fail) throw DoubanPublicException(DoubanPublicStop.STALLED, "超时")
            top250Html(0, 25)
        }
        val repo = repository(transport, clock)
        repo.top250(0)
        clock.value += DoubanPublicCache.TTL_MS + 1
        fail = true
        val page = repo.top250(0)
        assertTrue(page.stale)
        assertEquals("stale-cache", page.source)
        assertEquals(25, page.items.size)
    }

    private fun repository(transport: DoubanPublicTransport, clock: Clock) = DoubanPublicRepository(
        transport, DoubanPublicGate(now = clock::now),
        DoubanPublicCache(File(temp(), "repo-cache"), clock::now), clock::now, clock::pause
    )

    private fun temp(): File = File.createTempFile("douban-public-test", "").apply { delete(); mkdirs() }

    private class Clock(var value: Long = 0L) {
        fun now(): Long = value
        suspend fun pause(milliseconds: Long) { value += milliseconds }
    }

    private class MemoryStore : DoubanPublicStore {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private class FakeTransport(private val body: (String) -> String) : DoubanPublicTransport {
        override suspend fun get(url: String): String = body(url)
    }

    private fun top250Html(start: Int, count: Int): String = buildString {
        repeat(count) { index ->
            val rank = start + index + 1
            val id = 1_292_052 + rank
            append("<div class=\"item\"><div class=\"pic\"><a href=\"https://movie.douban.com/subject/$id/\">")
            append("<img src=\"https://img3.doubanio.com/view/photo/p$id.jpg\"></a></div>")
            append("<div class=\"info\"><span class=\"title\">作品$rank</span><span class=\"title\">/ 别名</span>")
            append("<span class=\"rating_num\">9.7</span></div></div>\n")
        }
        if (start + count < 250) append("<span class=\"next\"><a href=\"?start=${start + count}&amp;filter=\">后页</a></span>")
    }
}
