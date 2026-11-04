package dev.cinematv.data

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceResolutionTest {
    private val work = MediaItem("catalog:1", "Exact work", year = "2020", aliases = listOf("Alias work"))
    private fun candidate(id: String, title: String = work.title) = work.copy(id = id, title = title,
        source = TvSource(id, "Source $id", "https://source.example/$id"), metadataIds = emptyMap())
    private fun playable(item: MediaItem) = item.copy(episodes = listOf("https://media.example/video.mp4"))
    private fun snapshot(vararg items: MediaItem) = SearchSnapshot(items.toList(), complete = true)

    @Test fun failureThenAvailableUsesKnownCandidateAndReportsProvenance() = runBlocking {
        val tried = mutableListOf<String>()
        val progress = mutableListOf<ResolutionProgress>()
        val resolved = CatalogSourceResolver({ snapshot(candidate("one"), candidate("two")) }) {
            tried += it.id
            if (it.id == "one") throw LibreTvHttpException(502, "upstream unavailable")
            playable(it)
        }.resolve(work, progress::add)
        assertEquals(listOf("one", "two"), tried)
        assertEquals("two", resolved.source?.key)
        assertEquals(ResolutionPhase.RESOLVED, progress.last().phase)
        assertTrue(resolved.metadataIds.isEmpty())
    }

    @Test fun unusableAndIdentityConflictsNeverWin() = runBlocking {
        val tried = mutableListOf<String>()
        val candidates = arrayOf(candidate("wrongYear").copy(year = "2021"),
            candidate("wrongType").copy(isTv = true), candidate("wrongSeason").copy(seasonNumber = 2),
            candidate("empty"), candidate("unsafe"), candidate("changed"), candidate("good"))
        val result = CatalogSourceResolver({ snapshot(*candidates) }) {
            tried += it.id
            when (it.id) {
                "empty" -> it
                "unsafe" -> it.copy(episodes = listOf("file:///private/video"))
                "changed" -> playable(it).copy(year = "2022")
                else -> playable(it)
            }
        }.resolve(work.copy(seasonNumber = 1))
        assertEquals(listOf("empty", "unsafe", "changed", "good"), tried)
        assertEquals("good", result.id)
    }

    @Test fun aliasesAreLimitedAndCandidatesDeduplicated() = runBlocking {
        val queries = mutableListOf<String>()
        var attempts = 0
        try {
            CatalogSourceResolver({ queries += it; snapshot(candidate("same")) }) {
                attempts++; it
            }.resolve(work.copy(aliases = listOf(work.title, "Alias work", "Other", "Ignored")))
            fail("No usable episodes")
        } catch (_: SourceResolutionException) { }
        assertEquals(listOf("Exact work", "Alias work", "Other"), queries)
        assertEquals(1, attempts)
    }

    @Test fun aliasCanResolveOnlyWithMatchingIdentity() = runBlocking {
        val queries = mutableListOf<String>()
        val result = CatalogSourceResolver({ query ->
            queries += query
            if (query == work.title) snapshot() else snapshot(candidate("alias", "Alias work"))
        }, ::playable).resolve(work)
        assertEquals("alias", result.id)
        assertEquals(listOf(work.title, "Alias work"), queries)
    }

    @Test fun attemptBudgetStopsFurtherQueries() = runBlocking {
        var searches = 0
        var attempts = 0
        try {
            CatalogSourceResolver({ searches++; snapshot(*(1..20).map { candidate("$it") }.toTypedArray()) }) {
                attempts++; throw IOException("ordinary connection failure")
            }.resolve(work)
            fail("Expected exhaustion")
        } catch (_: SourceResolutionException) { }
        assertEquals(6, attempts)
        assertEquals(1, searches)
    }

    @Test fun authAccessRateLimitAndUnconfiguredServerStopImmediately() = runBlocking {
        for (status in listOf(400, 401, 403, 407, 429, 451, 503)) {
            var attempts = 0
            try {
                CatalogSourceResolver({ snapshot(candidate("one"), candidate("two")) }) {
                    attempts++; throw LibreTvHttpException(status, "restricted")
                }.resolve(work)
                fail("Should not bypass $status")
            } catch (error: LibreTvHttpException) { assertEquals(status, error.status) }
            assertEquals(1, attempts)
        }
    }

    @Test fun sourceAccessDenialStopsEvenWithOtherResults() = runBlocking {
        var attempts = 0
        try {
            CatalogSourceResolver({ SearchSnapshot(listOf(candidate("one")),
                listOf(SourceSearchOutcome("blocked", false, "HTTP 403")), true) }) {
                attempts++; playable(it)
            }.resolve(work)
            fail("Restricted search must stop")
        } catch (_: SourceResolutionException) { }
        assertEquals(0, attempts)
    }

    @Test fun cancellationDoesNotTryAnotherSource() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var attempts = 0
        val job = launch {
            CatalogSourceResolver({ snapshot(candidate("one"), candidate("two")) }) {
                attempts++; entered.complete(Unit); awaitCancellation()
            }.resolve(work)
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
        assertTrue(job.isCancelled)
    }

    @Test fun incompleteSearchAndProtocolFailuresCannotBecomeFallbackSuccess() = runBlocking {
        var attempts = 0
        try {
            CatalogSourceResolver({ SearchSnapshot(listOf(candidate("one"))) }) {
                attempts++; playable(it)
            }.resolve(work)
            fail("Incomplete search must stop")
        } catch (_: SourceResolutionException) { }
        assertEquals(0, attempts)
        try {
            CatalogSourceResolver({ snapshot(candidate("one"), candidate("two")) }) {
                attempts++; throw LibreTvProtocolException("missing episodes")
            }.resolve(work)
            fail("Protocol error must stop")
        } catch (_: LibreTvProtocolException) { }
        assertEquals(1, attempts)
    }

    @Test fun unknownTypeIsAllowedButNewDetailConflictIsRejected() = runBlocking {
        val result = CatalogSourceResolver({ snapshot(candidate("unknown").copy(mediaTypeKnown = false), candidate("good")) }) {
            if (it.id == "unknown") playable(it).copy(mediaTypeKnown = true, isTv = true) else playable(it)
        }.resolve(work)
        assertEquals("good", result.id)
    }

    @Test(timeout = 10000) fun realBlockedHttpHeadersDisconnectOnDeadline() = runBlocking {
        assertRealSocketCancellation(sendHeaders = false)
    }

    @Test(timeout = 10000) fun realBlockedHttpBodyDisconnectsOnCancellation() = runBlocking {
        assertRealSocketCancellation(sendHeaders = true)
    }

    private suspend fun assertRealSocketCancellation(sendHeaders: Boolean) {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val entered = CountDownLatch(1)
        val closedByClient = CountDownLatch(1)
        var client: Socket? = null
        val fixture = Thread {
            try {
                listener.accept().use { socket ->
                    client = socket
                    socket.soTimeout = 6000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    if (sendHeaders) {
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{".toByteArray())
                            flush()
                        }
                    }
                    entered.countDown()
                    if (input.read() == -1) closedByClient.countDown()
                }
            } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
        val call = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            .newCall(Request.Builder().url("http://127.0.0.1:${listener.localPort}/api/fixture").build())
        val started = System.nanoTime()
        try {
            val result = withTimeoutOrNull(500) {
                withCancellableCall(call) { response ->
                    assertEquals(200, response.code)
                    response.body!!.string()
                }
            }
            assertNull("Own deadline should cancel blocked HTTP", result)
            assertTrue("The fixture must receive a real HTTP request", entered.await(1, TimeUnit.SECONDS))
            assertTrue("Must not wait for 20-second HTTP timeout", (System.nanoTime() - started) / 1_000_000 < 2500)
            assertTrue("Cancellation must close the peer socket", withContext(Dispatchers.IO) {
                closedByClient.await(2, TimeUnit.SECONDS)
            })
        } finally {
            client?.close()
            listener.close()
            fixture.join(2000)
        }
    }

    @Test fun invalidAndCredentialedUrlsAreNotPlayable() {
        for (url in listOf("", "https:", "javascript:alert(1)", "file:///tmp/video", "https://user:secret@example.com/a")) {
            assertFalse(url, SourceResolutionPolicy.usableUrl(url))
        }
        assertTrue(SourceResolutionPolicy.usableUrl("https://example.com/video?token=synthetic"))
    }
}
