package dev.cinematv.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.data.library.CinemaLibraryDatabase
import dev.cinematv.data.library.LegacyLibraryImport
import dev.cinematv.data.library.LocalMediaLibrary
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic loopback only. Never contacts a real source, metadata service, or media URL. */
@RunWith(AndroidJUnit4::class)
class LibreTvProtocolTest {
    private lateinit var context: Context
    private lateinit var db: CinemaLibraryDatabase
    private lateinit var server: FixtureServer
    private lateinit var repo: LibreTvRepository
    // A late SharedPreferences.apply() from another case must never revive our fixture.
    private val prefName = "libretv-protocol-fixture-${UUID.randomUUID()}"
    private lateinit var fixturePreferences: SharedPreferences
    private val work = MediaItem("catalog:test", "Fixture film", year = "2020",
        aliases = listOf("Fixture alias"), metadataIds = mapOf("catalog" to "test"))

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixturePreferences = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        assertTrue("Protocol fixture preferences must start empty", fixturePreferences.all.isEmpty())
        db = Room.inMemoryDatabaseBuilder(context, CinemaLibraryDatabase::class.java).build()
        server = FixtureServer()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                fixturePreferences
        }
        repo = LibreTvRepository(isolated, LocalMediaLibrary(
            databaseProvider = { db },
            legacyProvider = { LegacyLibraryImport(emptyList(), emptyList(), emptyList()) }
        )).apply {
            configureServer(this@LibreTvProtocolTest.server.base)
            addSource("Fixture source", "${this@LibreTvProtocolTest.server.base}/provider")
        }
        assertEquals("Only this case's loopback source is configured",
            listOf("${server.base}/provider"), repo.sources().map { it.url })
    }

    @After fun tearDown() {
        try {
            if (::server.isInitialized) server.close()
        } finally {
            try {
                if (::db.isInitialized) db.close()
            } finally {
                if (::fixturePreferences.isInitialized) {
                    // Commit on the same instance drains its pending apply writes before deletion.
                    assertTrue("Protocol fixture cleanup must persist", fixturePreferences.edit().clear().commit())
                }
                if (::context.isInitialized) context.deleteSharedPreferences(prefName)
            }
        }
    }

    @Test fun overlappingFixturesDoNotShareSourcesOrPendingPreferenceWrites() = runBlocking {
        val previous = LibreTvProtocolTest()
        try {
            previous.setUp()
            assertNotEquals("Concurrent fixtures must have distinct backing files", prefName, previous.prefName)
            withTimeout(5_000) {
                val firstWrite = CompletableDeferred<Unit>()
                val continueWriting = CompletableDeferred<Unit>()
                val writer = launch(Dispatchers.IO) {
                    previous.repo.addSource("Pending fixture source", "${previous.server.base}/other")
                    firstWrite.complete(Unit)
                    continueWriting.await()
                    repeat(16) {
                        previous.repo.addSource("Pending fixture source $it", "${previous.server.base}/other")
                    }
                }
                firstWrite.await()
                assertEquals(listOf("${server.base}/provider"), repo.sources().map { it.url })
                continueWriting.complete(Unit)
                repeat(16) {
                    assertEquals(listOf("${server.base}/provider"), repo.sources().map { it.url })
                }
                writer.join()
            }
            assertEquals(2, previous.repo.sources().size)
        } finally { previous.tearDown() }
        assertEquals(listOf("${server.base}/provider"), repo.sources().map { it.url })
    }

    private fun row(id: String, year: String = "2020", typeName: String = "电影") = JSONObject()
        .put("sourceKey", repo.sources().single().key).put("sourceName", "Fixture source")
        .put("vodId", id).put("name", work.title).put("year", year).put("typeName", typeName)
    private fun search(vararg rows: JSONObject) = JSONObject().put("list", JSONArray(rows.toList()))
        .put("failures", JSONArray())
    private fun detail(title: String = work.title, year: String = "2020", episodes: List<String> = listOf("https://media.example.test/fixture.mp4")) = JSONObject()
        .put("videoInfo", JSONObject().put("title", title).put("year", year).put("typeName", "电影")
            .put("actor", "Actor One / Actor Two").put("sourceKey", repo.sources().single().key))
        .put("episodes", JSONArray(episodes))

    @Test fun officialActorAndFailureThenAvailablePreserveSourceAndNoInventedIds() = runBlocking {
        server.enqueue(search(row("one"), row("two")).toString())
        server.enqueue("{\"error\":\"upstream unavailable\"}", 502)
        server.enqueue(detail().toString())
        val progress = mutableListOf<ResolutionProgress>()
        val result = repo.detail(work, progress::add)
        assertEquals("two", result.id)
        assertEquals(repo.sources().single(), result.source)
        assertEquals(listOf("Actor One", "Actor Two"), result.actors)
        assertTrue(result.metadataIds.isEmpty())
        assertTrue(result.episodeIds.isEmpty())
        assertTrue(result.episodeNames.isEmpty())
        assertEquals(ResolutionPhase.RESOLVED, progress.last().phase)
        val requests = server.requests.toList()
        assertEquals(listOf("/api/search", "/api/detail", "/api/detail"), requests.map { it.uri.path })
        assertEquals(listOf("one", "two"), requests.drop(1).map { it.uri.getQueryParameter("id") })
        assertEquals(work.title, JSONObject(requests.first().body).getString("wd"))
    }

    @Test fun conflictingSearchRowsAreSkippedAndEmptyDetailAllowsNextCandidate() = runBlocking {
        server.enqueue(search(row("badYear", "2010"), row("badType", typeName = "电视剧"), row("empty"), row("good")).toString())
        server.enqueue(detail(episodes = emptyList()).toString())
        server.enqueue(detail().toString())
        assertEquals("good", repo.detail(work).id)
        assertEquals(listOf("empty", "good"), server.requests.drop(1).map { it.uri.getQueryParameter("id") })
    }

    @Test fun conflictingDetailIdentityCannotWin() = runBlocking {
        server.enqueue(search(row("changed"), row("good")).toString())
        server.enqueue(detail(year = "2021").toString())
        server.enqueue(detail().toString())
        assertEquals("good", repo.detail(work).id)
    }

    @Test fun directAuthoritativeEmptyClearsPlaylistButMalformedPayloadDoesNot() = runBlocking {
        val selected = work.copy(id = "direct", source = repo.sources().single())
        server.enqueue(detail().toString())
        assertTrue(repo.detail(selected).episodes.isNotEmpty())
        server.enqueue("{\"videoInfo\":{}}")
        try { repo.detail(selected); fail("Missing array is not an authoritative empty playlist") }
        catch (_: LibreTvProtocolException) { }
        assertTrue(repo.localItems().single().episodes.isNotEmpty())
        server.enqueue(detail(episodes = emptyList()).toString())
        val empty = repo.detail(selected)
        assertTrue(empty.playlistKnown)
        assertTrue(empty.episodes.isEmpty())
        assertTrue(repo.localItems().single().episodes.isEmpty())
    }

    @Test fun missingSeasonOnFreshDetailQuarantinesKnownSeasonCheckpoint() = runBlocking {
        val selected = work.copy(id = "reused", source = repo.sources().single(), seasonNumber = 1)
        val oldPayload = detail()
        oldPayload.getJSONObject("videoInfo").put("season", 1)
            .put("episodeNames", JSONArray(listOf("Episode 1")))
        server.enqueue(oldPayload.toString())
        val old = repo.detail(selected)
        repo.saveProgress(old, 42000L, 0, 120000L)
        assertNotNull(repo.library.snapshot.progress(old, 0))
        val checkpointCount = repo.library.snapshot.episodeProgress.size
        val freshPayload = detail()
        freshPayload.getJSONObject("videoInfo").put("episodeNames", JSONArray(listOf("Episode 1")))
        server.enqueue(freshPayload.toString())
        val refreshed = repo.detail(old)
        assertNull(refreshed.seasonNumber)
        assertNull(repo.library.snapshot.progress(refreshed, 0))
        assertEquals(checkpointCount, repo.library.snapshot.episodeProgress.size)
        assertEquals(0L, refreshed.positionMs)
    }

    @Test fun accessDenialNeverTriesTheNextCandidate() = runBlocking {
        server.enqueue(search(row("denied"), row("other")).toString())
        server.enqueue("{\"error\":\"forbidden\"}", 403)
        try { repo.detail(work); fail("Access denial must stop") }
        catch (error: LibreTvHttpException) { assertEquals(403, error.status) }
        assertEquals(2, server.requests.size)
    }

    @Test fun streamedOutcomesKeepTimeoutLatencyFailuresAndDoneAuthority() = runBlocking {
        val key = repo.sources().single().key
        val source = JSONObject().put("type", "source").put("sourceKey", key)
            .put("ok", false).put("error", "request timeout").put("timedOut", true)
            .put("ms", 10042).put("list", JSONArray())
        val done = search().put("type", "done").put("failures", JSONArray().put(JSONObject()
            .put("sourceKey", key).put("error", "request timeout").put("timedOut", true)))
        server.enqueue("$source\n$done\n")
        val updates = repo.searchSnapshots("Fixture").toList()
        assertEquals(2, updates.size)
        assertFalse(updates.first().complete)
        assertTrue(updates.last().complete)
        assertTrue(updates.last().outcomes.single().timedOut)
        assertEquals(10042L, updates.last().outcomes.single().ms)
        assertEquals("request timeout", updates.last().outcomes.single().error)
        assertEquals("1", server.requests.single().uri.getQueryParameter("stream"))
    }

    @Test fun doneReplacesPartialOrderAndCachedDoneNeedsNoSourceEvent() = runBlocking {
        val first = search(row("one"), row("two")).put("type", "source")
            .put("sourceKey", repo.sources().single().key).put("ok", true).put("ms", 8)
        val done = search(row("two"), row("one")).put("type", "done")
        server.enqueue("$first\n$done\n")
        val updates = repo.searchSnapshots("Fixture").toList()
        assertEquals(listOf("one", "two"), updates.first().items.map { it.id })
        assertEquals(listOf("two", "one"), updates.last().items.map { it.id })
        server.enqueue(done.toString() + "\n")
        val cached = repo.searchSnapshots("Fixture").toList().single()
        assertTrue(cached.complete)
        assertTrue(cached.outcomes.isEmpty())
        assertEquals(listOf("two", "one"), cached.items.map { it.id })
    }

    @Test fun mismatchedDetailSourceCannotBeCachedOrUsed() = runBlocking {
        val payload = detail()
        payload.getJSONObject("videoInfo").put("sourceKey", "unrequested-source")
        server.enqueue(payload.toString())
        try { repo.detail(work.copy(id = "one", source = repo.sources().single())); fail("Wrong source") }
        catch (_: LibreTvProtocolException) { }
        assertTrue(repo.localItems().isEmpty())
    }

    @Test fun truncatedNdjsonRetainsPartialResultButNeverClaimsCompletion() = runBlocking {
        server.enqueue(search(row("partial")).put("type", "source")
            .put("sourceKey", repo.sources().single().key).put("ok", true).put("ms", 14).toString() + "\n")
        val updates = mutableListOf<SearchSnapshot>()
        try { repo.searchSnapshots("Fixture").collect { updates += it }; fail("Missing done must fail") }
        catch (error: IOException) { assertTrue(error.message.orEmpty().contains("done")) }
        assertEquals(1, updates.size)
        assertEquals("partial", updates.single().items.single().id)
        assertFalse(updates.single().complete)
    }

    @Test fun legacySingleJsonAndActorsRemainCompatible() = runBlocking {
        server.enqueue(search(row("legacy")).toString())
        assertEquals("legacy", repo.searchSnapshots("Fixture").toList().single().items.single().id)
        val payload = detail()
        payload.getJSONObject("videoInfo").remove("actor")
        payload.getJSONObject("videoInfo").put("actors", JSONArray(listOf("Legacy actor")))
            .put("episodeNames", JSONArray(listOf("", "Repeated", "Repeated")))
            .put("episodeIds", JSONArray(listOf("", "duplicate", "duplicate")))
        payload.put("episodes", JSONArray(listOf("https://media.example.test/1", "https://media.example.test/2", "https://media.example.test/3")))
        server.enqueue(payload.toString())
        val result = repo.detail(work.copy(id = "legacy", source = repo.sources().single()))
        assertEquals(listOf("Legacy actor"), result.actors)
        assertEquals(listOf("", "Repeated", "Repeated"), result.episodeNames)
        assertEquals(listOf("", "duplicate", "duplicate"), result.episodeIds)
    }

    @Test fun streaming401ClearsCookieAndUnconfiguredStatusIsNotVerified() = runBlocking {
        server.enqueue("{\"success\":true}", headers = "Set-Cookie: synthetic=session; HttpOnly\r\n")
        assertTrue(repo.login("synthetic-password"))
        server.enqueue("{\"error\":\"未登录\"}", 401)
        try { repo.searchSnapshots("Fixture").toList(); fail("401 must fail") }
        catch (error: LibreTvHttpException) { assertEquals(401, error.status); assertEquals("未登录", error.serverMessage) }
        server.enqueue("{\"passwordRequired\":false,\"verified\":false,\"version\":\"2.21.1\"}")
        val status = repo.status()
        assertFalse(status.verified)
        assertFalse(status.passwordRequired)
        assertEquals("synthetic=session", server.requests[1].headers["cookie"])
        assertNull(server.requests[2].headers["cookie"])
    }

    @Test fun cancellingBlockedDetailDoesNotRequestAnotherCandidate() = runBlocking {
        server.enqueue(search(row("blocked"), row("other")).toString())
        server.enqueue("", stall = true, stallBody = true)
        val job = launch { repo.detail(work) }
        withTimeout(5000) {
            withContext(Dispatchers.IO) { assertTrue(server.stalled.poll(4, TimeUnit.SECONDS) == true) }
            job.cancelAndJoin()
        }
        assertEquals(2, server.requests.size)
        assertTrue(job.isCancelled)
        assertTrue(withContext(Dispatchers.IO) { server.closedByClient.poll(2, TimeUnit.SECONDS) == true })
    }

    @Test fun cancellingBlockedJsonSearchClosesSocketWithoutAliasRetry() = runBlocking {
        server.enqueue("", stall = true)
        val job = launch { repo.detail(work) }
        withTimeout(5000) {
            withContext(Dispatchers.IO) { assertTrue(server.stalled.poll(4, TimeUnit.SECONDS) == true) }
            job.cancelAndJoin()
        }
        assertEquals(1, server.requests.size)
        assertTrue(job.isCancelled)
        assertTrue(withContext(Dispatchers.IO) { server.closedByClient.poll(2, TimeUnit.SECONDS) == true })
    }

    @Test fun cancellingBlockedNdjsonBodyClosesSocket() = runBlocking {
        server.enqueue("", stall = true, stallBody = true)
        val job = launch { repo.searchSnapshots("Fixture").collect() }
        withTimeout(5000) {
            withContext(Dispatchers.IO) { assertTrue(server.stalled.poll(4, TimeUnit.SECONDS) == true) }
            job.cancelAndJoin()
        }
        assertEquals(1, server.requests.size)
        assertTrue(withContext(Dispatchers.IO) { server.closedByClient.poll(2, TimeUnit.SECONDS) == true })
    }

    private data class Captured(val uri: Uri, val headers: Map<String, String>, val body: String)
    private data class Reply(val body: String, val code: Int, val headers: String, val stall: Boolean, val stallBody: Boolean)
    private class FixtureServer : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val replies = LinkedBlockingQueue<Reply>()
        val requests = CopyOnWriteArrayList<Captured>()
        val stalled = LinkedBlockingQueue<Boolean>()
        val closedByClient = LinkedBlockingQueue<Boolean>()
        private val clients = CopyOnWriteArrayList<Socket>()
        @Volatile private var running = true
        @Volatile private var failure: Throwable? = null
        val base = "http://127.0.0.1:${listener.localPort}"
        private val worker = Thread({
            while (running) try {
                listener.accept().use { client -> clients += client; handle(client) }
            } catch (error: Exception) { if (running) failure = error }
        }, "libretv-protocol-loopback").apply { isDaemon = true; start() }
        fun enqueue(body: String, code: Int = 200, headers: String = "", stall: Boolean = false, stallBody: Boolean = false) {
            replies.put(Reply(body, code, headers, stall, stallBody))
        }
        private fun handle(client: Socket) {
            client.soTimeout = 5000
            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
            val path = requireNotNull(reader.readLine()).split(' ')[1]
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val count = headers["content-length"]?.toInt() ?: 0
            val body = CharArray(count)
            var offset = 0
            while (offset < count) {
                val read = reader.read(body, offset, count - offset)
                if (read < 0) break
                offset += read
            }
            requests += Captured(Uri.parse(path), headers, String(body, 0, offset))
            val reply = requireNotNull(replies.poll(5, TimeUnit.SECONDS)) { "Unexpected request: $path" }
            if (reply.stall) {
                if (reply.stallBody) client.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n{".toByteArray())
                    flush()
                }
                stalled.put(true)
                // EOF or reset must be caused by cancellation before fixture teardown.
                try {
                    if (reader.read() == -1) closedByClient.put(true)
                } catch (_: java.net.SocketException) { if (running) closedByClient.put(true) }
                catch (_: IOException) { }
                return
            }
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            client.getOutputStream().apply {
                write(("HTTP/1.1 ${reply.code} Fixture\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n${reply.headers}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(bytes); flush()
            }
        }
        override fun close() {
            running = false
            listener.close()
            clients.forEach { it.close() }
            worker.join(6000)
            assertFalse("Fixture failed to stop", worker.isAlive)
            assertNull("Fixture server failed", failure)
        }
    }
}
