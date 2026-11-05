package dev.cinematv.data.douban

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoubanAccountTest {
    private val identity = """<html><div class="nav-user-account"><a class="bn-more"><span>测试用户的账号</span></a><a href="https://www.douban.com/people/test_user/">个人主页</a></div></html>"""
    private fun collection(id: String = "123", stars: Int = 4, next: String = "") = """<html><div class="grid-view"><div class="item"><li class="title"><a href="https://movie.douban.com/subject/$id/">测试作品</a></li><span class="rating$stars-t"></span><span class="date">2026-11-01</span></div></div>$next</html>"""
    private class MemoryStore : DoubanStorage {
        var value: JSONObject? = null
        override fun read() = value?.let { JSONObject(it.toString()) }
        override fun write(value: JSONObject) { this.value = JSONObject(value.toString()) }
        override fun clear() { value = null }
    }
    @Test fun personalStarsNeverBecomeCommunityScore() {
        val item = DoubanHtmlParser.collection(collection(), "test_user", DoubanShelf.WATCHED, 0).entries.single()
        assertEquals(4, item.personalStars); assertNull(item.communityScore)
    }
    @Test fun acceptsValidatedEmptyCollection() {
        val page = DoubanHtmlParser.collection("<div class='grid-view'></div>", "test_user", DoubanShelf.WANT, 0)
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
        val transport = object : DoubanTransport { override suspend fun get(path: String, cookie: String): String { requests += path; return if(path == "/mine/") identity else collection() } }
        val service = DoubanAccountService(store, transport, { clock }, { clock += it })
        service.initialize(); assertTrue(requests.isEmpty()); assertNull(store.value)
        service.acceptLocalSession("synthetic-test-cookie"); assertEquals(DoubanPhase.CONNECTED, service.state.value.phase)
        service.sync(); assertEquals(4, requests.size); assertTrue(service.state.value.snapshot!!.complete)
        assertEquals(3, service.state.value.snapshot!!.entries.size); assertTrue(clock >= 13_000)
        service.disconnect(); assertNull(store.value); assertEquals(DoubanPhase.DISCONNECTED, service.state.value.phase)
    }
    @Test fun accessDenialPreservesPreviousSnapshotAndBlocksRetry() = runBlocking {
        var clock = 1_000L; var deny = false; var calls = 0
        val transport = object : DoubanTransport { override suspend fun get(path: String, cookie: String): String { calls++; if(deny) throw DoubanAccessException(DoubanPhase.CHALLENGE, "验证"); return if(path == "/mine/") identity else collection() } }
        val service = DoubanAccountService(MemoryStore(), transport, { clock }, { clock += it })
        service.acceptLocalSession("synthetic"); service.sync(); val old = service.state.value.snapshot
        deny = true; service.sync(); val deniedCalls = calls; assertEquals(DoubanPhase.CHALLENGE, service.state.value.phase)
        assertEquals(old, service.state.value.snapshot); service.sync(); assertEquals(deniedCalls, calls)
    }
    @Test fun pageBudgetStopsAndCanContinue() = runBlocking {
        var clock = 1_000L
        val transport = object : DoubanTransport { override suspend fun get(path: String, cookie: String): String {
            if(path == "/mine/") return identity
            val start = path.substringAfter("start=").substringBefore('&').toInt()
            val shelf = path.substringAfter("test_user/").substringBefore('?')
            val next = if(start < 30) "<div class='paginator'><span class='next'><a href='?start=${start + 1}'>下一页</a></span></div>" else ""
            return collection((start + 1).toString(), next = next)
        } }
        val service = DoubanAccountService(MemoryStore(), transport, { clock }, { clock += it })
        service.acceptLocalSession("synthetic"); service.sync()
        assertEquals(20, service.state.value.snapshot!!.entries.size); assertFalse(service.state.value.snapshot!!.complete)
        service.sync(true); assertEquals(40, service.state.value.snapshot!!.entries.size)
    }
}
