package dev.cinematv.data.douban

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoubanFeatureAccessTest {
    private val identity = """<html><div class="nav-user-account"><a class="bn-more"><span>测试用户的账号</span></a><a href="https://www.douban.com/people/test_user/">个人主页</a></div></html>"""
    private class Store : DoubanStorage {
        var reads = 0; var writes = 0; var clears = 0
        override fun read(): JSONObject? { reads++; return null }
        override fun write(value: JSONObject) { writes++ }
        override fun clear() { clears++ }
    }
    private fun transport(body: suspend (suspend () -> Unit) -> String) = object : DoubanTransport {
        override suspend fun get(path: String, cookie: String, beforeRequest: suspend () -> Unit) = body(beforeRequest)
    }
    private suspend fun denied(block: suspend () -> Unit) {
        val result = runCatching { block() }
        assertTrue("Expected revoked feature cancellation, got ${result.exceptionOrNull()}", result.exceptionOrNull() is DoubanFeatureDisabledException)
    }
    @Test fun onlyExactDevChannelCanEnableAndDefaultIsOff() {
        listOf("stable", "preview", "Dev", "", "unknown").forEach { channel ->
            val access = DoubanFeatureAccess(channel, true)
            access.enable(); assertFalse(access.enabled.value)
            assertThrows(DoubanFeatureDisabledException::class.java) { access.capture() }
        }
        val dev = DoubanFeatureAccess("dev")
        assertFalse(dev.isEnabled); dev.enable(); assertTrue(dev.isEnabled)
    }
    @Test fun revokeAndReenableNeverRestoreAnOldTicket() {
        val access = DoubanFeatureAccess("dev", true)
        val old = access.capture(); var callbacks = 0
        val subscription = access.onRevoked { callbacks++ }
        access.revoke(); access.enable()
        assertThrows(DoubanFeatureDisabledException::class.java) { access.guard(old) { fail("Old operation ran") } }
        access.check(access.capture()); assertEquals(1, callbacks)
        subscription.close(); access.revoke(); assertEquals(1, callbacks)
    }
    @Test fun disabledServiceHasNoVaultReadsWritesOrRequestsButCanClear() = runBlocking {
        val store = Store(); var calls = 0
        val service = DoubanAccountService(store, transport { calls++; identity })
        service.initialize()
        denied { service.acceptLocalSession("synthetic") }
        denied { service.sync() }; denied { service.syncScores() }
        assertEquals(0, store.reads); assertEquals(0, store.writes); assertEquals(0, calls)
        service.disconnect(); assertEquals(1, store.clears)
    }
    @Test fun revokedResponseCannotPersistOrPublishEvenAfterReenable() = runBlocking {
        val access = DoubanFeatureAccess("dev", true); val store = Store()
        val service = DoubanAccountService(store, transport { before ->
            before(); access.revoke(); access.enable(); identity
        }, access = access)
        denied { service.acceptLocalSession("synthetic") }
        assertEquals(0, store.writes); assertEquals(DoubanPhase.DISCONNECTED, service.state.value.phase)
        assertNull(service.state.value.owner)
    }
    @Test fun revokeAtResponseToPersistenceBoundaryCannotSaveCookie() = runBlocking {
        val access = DoubanFeatureAccess("dev", true); val store = Store(); var clockReads = 0
        val service = DoubanAccountService(store, transport { before -> before(); identity }, now = {
            // beforeRequest checks clock, attempted() checks clock, then session savedAt checks clock.
            clockReads++
            if (clockReads == 3) { access.revoke(); access.enable() }
            1_000L
        }, access = access)
        denied { service.acceptLocalSession("synthetic") }
        assertEquals(0, store.writes); assertNull(service.state.value.owner)
    }
    @Test fun revocationWhileRateLimitedPreventsNextRequest() = runBlocking {
        val access = DoubanFeatureAccess("dev", true); val store = Store(); var calls = 0
        val service = DoubanAccountService(store, transport { before -> before(); calls++; identity },
            now = { 1_000L }, pause = { access.revoke() }, access = access)
        service.acceptLocalSession("synthetic")
        val previousWrites = store.writes
        denied { service.acceptLocalSession("replacement") }
        assertEquals(1, calls); assertEquals(previousWrites, store.writes)
        assertEquals(DoubanPhase.DISCONNECTED, service.state.value.phase)
    }
    @Test fun lateInFlightResponseCannotResurrectStateAndCleanupCanFinish() = runBlocking {
        withTimeout(5_000) {
            val access = DoubanFeatureAccess("dev", true); val store = Store()
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val service = DoubanAccountService(store, transport { before ->
                before(); started.complete(Unit)
                withContext(NonCancellable) { finish.await() }
                identity
            }, access = access)
            val operation = async { service.acceptLocalSession("synthetic") }
            started.await(); access.revoke(); access.enable(); finish.complete(Unit)
            denied { operation.await() }
            assertEquals(0, store.writes); assertNull(service.state.value.owner)
            access.revoke(); service.disconnect(); assertEquals(1, store.clears)
        }
    }
    @Test fun revokeCancelsTheActualRegisteredCallWithoutWaitingForTimeout() = runBlocking {
        withTimeout(5_000) {
            val access = DoubanFeatureAccess("dev", true); val store = Store()
            val enqueued = CompletableDeferred<Unit>()
            var cancelled = false
            val factory = okhttp3.Call.Factory { request ->
                object : okhttp3.Call {
                    private var callback: okhttp3.Callback? = null
                    override fun request() = request
                    override fun execute(): okhttp3.Response = error("Blocking execute must never be used")
                    override fun enqueue(responseCallback: okhttp3.Callback) {
                        callback = responseCallback; enqueued.complete(Unit)
                    }
                    override fun cancel() {
                        if (!cancelled) { cancelled = true; callback?.onFailure(this, java.io.IOException("Synthetic cancellation")) }
                    }
                    override fun isExecuted() = callback != null
                    override fun isCanceled() = cancelled
                    override fun timeout() = okio.Timeout.NONE
                    override fun clone(): okhttp3.Call = error("No retries")
                }
            }
            val service = DoubanAccountService(store, OfficialDoubanTransport(factory), access = access)
            val operation = async { service.acceptLocalSession("synthetic") }
            enqueued.await(); access.revoke()
            denied { operation.await() }
            assertTrue(cancelled); assertEquals(0, store.writes)
            assertEquals(DoubanPhase.DISCONNECTED, service.state.value.phase)
        }
    }
    @Test fun revocationCallbacksRunOutsideTheAuthorityLock() {
        val access = DoubanFeatureAccess("dev", true)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var outsideLock = false
        try {
            access.onRevoked {
                outsideLock = executor.submit<Boolean> { !access.isEnabled }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            access.revoke(); assertTrue(outsideLock)
        } finally { executor.shutdownNow() }
    }
    @Test fun officialTransportUngatedEntryPointIsDeniedWithoutNetworking() = runBlocking {
        denied { OfficialDoubanTransport().get("/mine/", "synthetic") { fail("Must not reach network gate") } }
    }
}
