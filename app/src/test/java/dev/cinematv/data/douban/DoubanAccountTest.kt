package dev.cinematv.data.douban

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoubanAccountTest {
    private val identity = """<html><div class="nav-user-account"><a class="bn-more"><span>测试用户的账号</span></a><a href="https://www.douban.com/people/test_user/">个人主页</a></div></html>"""
    private fun collection(id: String = "123", stars: Int = 4, next: String = "", shelf: DoubanShelf = DoubanShelf.WATCHED, total: Int = 1) = """<html>$identity<h1>测试用户${shelf.label}的电影($total)</h1><div class="grid-view"><div class="item"><li class="title"><a href="https://movie.douban.com/subject/$id/">测试作品</a></li><span class="rating$stars-t"></span><span class="date">2026-11-01</span></div></div>$next</html>"""
    private class MemoryStore : DoubanStorage {
        var value: JSONObject? = null
        var rejectWrites = false
        override fun read() = value?.let { JSONObject(it.toString()) }
        override fun write(value: JSONObject) { check(!rejectWrites) { "Synthetic disk error" }; this.value = JSONObject(value.toString()) }
        override fun clear() { value = null }
    }
    @Test fun personalStarsNeverBecomeCommunityScore() {
        val item = DoubanHtmlParser.collection(collection(), "test_user", DoubanShelf.WATCHED, 0).entries.single()
        assertEquals(4, item.personalStars); assertNull(item.communityScore)
    }
    @Test fun acceptsValidatedEmptyCollection() {
        val page = DoubanHtmlParser.collection("$identity<h1>测试用户想看的电影(0)</h1><div class='grid-view'></div>", "test_user", DoubanShelf.WANT, 0)
        assertTrue(page.entries.isEmpty()); assertNull(page.nextStart)
    }
    @Test(expected = DoubanAccessException::class) fun rejectsUnknownEmptyMarkup() { DoubanHtmlParser.collection("<h1>empty</h1>", "test_user", DoubanShelf.WATCHED, 0) }
    @Test(expected = DoubanAccessException::class) fun detects200Challenge() { DoubanHtmlParser.collection("<title>安全验证</title><div class='grid-view'></div>", "test_user", DoubanShelf.WATCHED, 0) }
    @Test(expected = IllegalArgumentException::class) fun rejectsForeignNextPage() {
        DoubanHtmlParser.collection(collection(next = "<div class='paginator'><span class='next'><a href='https://evil.example/people/test_user/collect?start=15'>下一页</a></span></div>"), "test_user", DoubanShelf.WATCHED, 0)
    }
    @Test fun gateRetainsRetryDeadlineAcrossReauthentication() {
        var clock = 10_000L; val gate = DoubanRequestGate { clock }; gate.stop(DoubanPhase.RATE_LIMITED, 50_000)
        gate.userReauthenticated(); assertEquals(50_000L, gate.notBefore)
        assertThrows(DoubanAccessException::class.java) { gate.requireAllowed() }
        clock = 50_000; gate.requireAllowed()
    }
    @Test fun challengeCannotBeRetriedByRefresh() {
        val gate = DoubanRequestGate { 99_000 }; gate.stop(DoubanPhase.CHALLENGE)
        assertThrows(DoubanAccessException::class.java) { gate.requireAllowed() }
    }
    @Test fun retryAfterHonorsLongServerWait() {
        assertEquals(999_001_000L, OfficialDoubanTransport.retryAfter("999000", 1000))
        assertEquals(Long.MAX_VALUE, OfficialDoubanTransport.retryAfter(Long.MAX_VALUE.toString(), 1000))
    }
    @Test fun conflictPolicyKeepsLocalMark() {
        assertEquals(DoubanImportDecision.CONFLICT, DoubanImportPolicy.decision("WATCHED", DoubanShelf.WANT))
        assertEquals(DoubanImportDecision.ADD, DoubanImportPolicy.decision(null, DoubanShelf.WANT))
    }
    @Test fun syncAuthenticatesAndStoresOnlyAfterExplicitCall() = runBlocking {
        var clock = 1_000L; val store = MemoryStore(); val requests = mutableListOf<String>()
        val transport = object : DoubanTransport { override suspend fun get(path: String, cookie: String, beforeRequest: suspend () -> Unit): String { beforeRequest(); requests += path; return if(path == "/mine/") identity else collection(shelf = DoubanShelf.entries.first { path.contains("/${it.path}?") }) } }
        val service = DoubanAccountService(store, transport, { clock }, { clock += it })
        service.initialize(); assertTrue(requests.isEmpty()); assertNull(store.value)
        service.acceptLocalSession("synthetic-test-cookie"); assertEquals(DoubanPhase.CONNECTED, service.state.value.phase)
        service.sync(); assertEquals(4, requests.size); assertTrue(service.state.value.snapshot!!.complete)
        assertEquals(3, service.state.value.snapshot!!.entries.size); assertTrue(clock >= 13_000)
        service.disconnect(); assertNull(store.value); assertEquals(DoubanPhase.DISCONNECTED, service.state.value.phase)
    }
    @Test fun accessDenialPreservesPreviousSnapshotAndBlocksRetry() = runBlocking {
        var clock = 1_000L; var deny = false; var calls = 0
        val transport = object : DoubanTransport { override suspend fun get(path: String, cookie: String, beforeRequest: suspend () -> Unit): String { beforeRequest(); calls++; if(deny) throw DoubanAccessException(DoubanPhase.CHALLENGE, "验证"); return if(path == "/mine/") identity else collection(shelf = DoubanShelf.entries.first { path.contains("/${it.path}?") }) } }
        val service = DoubanAccountService(MemoryStore(), transport, { clock }, { clock += it })
        service.acceptLocalSession("synthetic"); service.sync(); val old = service.state.value.snapshot
        deny = true; service.sync(); val deniedCalls = calls; assertEquals(DoubanPhase.CHALLENGE, service.state.value.phase)
        assertEquals(old, service.state.value.snapshot); service.sync(); assertEquals(deniedCalls, calls)
    }
    @Test fun pageBudgetStopsAndCanContinue() = runBlocking {
        var clock = 1_000L
        val transport = object : DoubanTransport { override suspend fun get(path: String, cookie: String, beforeRequest: suspend () -> Unit): String { beforeRequest();
            if(path == "/mine/") return identity
            val start = path.substringAfter("start=").substringBefore('&').toInt()
            val shelf = path.substringAfter("test_user/").substringBefore('?')
            val next = if(start < 30) "<div class='paginator'><span class='next'><a href='?start=${start + 1}'>下一页</a></span></div>" else ""
            return collection((start + 1).toString(), next = next, shelf = DoubanShelf.entries.first { it.path == shelf }, total = 31)
        } }
        val service = DoubanAccountService(MemoryStore(), transport, { clock }, { clock += it })
        service.acceptLocalSession("synthetic"); service.sync()
        assertEquals(20, service.state.value.snapshot!!.entries.size); assertFalse(service.state.value.snapshot!!.complete)
        service.sync(true); assertEquals(40, service.state.value.snapshot!!.entries.size)
    }
    @Test fun failedReplacementCannotClearPreviousChallenge() = runBlocking {
        var clock = 1_000L; var fail = false; var calls = 0
        val transport = object : DoubanTransport { override suspend fun get(path: String, cookie: String, beforeRequest: suspend () -> Unit): String {
            beforeRequest(); calls++
            if(fail && path == "/mine/") error("temporary failure")
            if(fail) throw DoubanAccessException(DoubanPhase.CHALLENGE, "验证")
            return if(path == "/mine/") identity else collection(shelf = DoubanShelf.entries.first { path.contains("/${it.path}?") })
        } }
        val service = DoubanAccountService(MemoryStore(), transport, { clock }, { clock += it })
        service.acceptLocalSession("synthetic"); fail = true; service.sync()
        service.acceptLocalSession("candidate"); val afterReplacement = calls
        service.sync(); assertEquals(afterReplacement, calls)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingNextPageWithMoreExpected() {
        DoubanHtmlParser.collection(collection(total = 20), "test_user", DoubanShelf.WATCHED, 0)
    }

    @Test fun communityScoreRequiresExactSubjectAndRemainsSeparate() {
        val html = "<meta property='og:url' content='https://movie.douban.com/subject/123/'><div id='content'><h1>作品</h1><strong property='v:average'>8.7</strong></div>"
        assertEquals(8.7, DoubanHtmlParser.communityScore(html, "123")!!, 0.001)
        assertThrows(IllegalArgumentException::class.java) { DoubanHtmlParser.communityScore(html, "456") }
    }

    @Test fun scoreCacheSurvivesPartialListContinuation() = runBlocking {
        var clock = 1_000L
        val transport = object : DoubanTransport { override suspend fun get(path: String, cookie: String, beforeRequest: suspend () -> Unit): String {
            beforeRequest()
            if(path == "/mine/") return identity
            if(path.startsWith("/subject/")) return "<meta property='og:url' content='https://movie.douban.com$path'><div id='content'><h1>作品</h1><strong property='v:average'>8.7</strong></div>"
            val start = path.substringAfter("start=").substringBefore('&').toInt()
            val next = if(start < 30) "<div class='paginator'><span class='next'><a href='?start=${start + 1}'>下一页</a></span></div>" else ""
            return collection((start + 1).toString(), next = next, shelf = DoubanShelf.entries.first { path.contains("/${it.path}?") }, total = 31)
        } }
        val service = DoubanAccountService(MemoryStore(), transport, { clock }, { clock += it })
        service.acceptLocalSession("synthetic"); service.sync(); service.syncScores()
        assertEquals(10, service.state.value.snapshot!!.entries.count { it.communityScoreCheckedAt > 0 })
        service.sync(true)
        assertEquals(8.7, service.state.value.snapshot!!.entries.first { it.subjectId == "1" }.communityScore!!, 0.001)
    }

    @Test fun failedSessionPersistenceCannotReportConnectedOrRetainCandidate() = runBlocking {
        var clock = 1_000L; val store = MemoryStore().apply { rejectWrites = true }
        val transport = object : DoubanTransport { override suspend fun get(path: String, cookie: String, beforeRequest: suspend () -> Unit): String { beforeRequest(); return identity } }
        val service = DoubanAccountService(store, transport, { clock }, { clock += it })
        service.acceptLocalSession("synthetic-candidate")
        assertEquals(DoubanPhase.ERROR, service.state.value.phase)
        assertNull(service.state.value.owner); assertNull(store.value)
        service.sync(); assertNull(service.state.value.snapshot)
    }

}
