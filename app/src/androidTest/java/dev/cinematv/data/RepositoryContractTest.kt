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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exercises real HTTP decoding and Room persistence using synthetic, loopback-only fixtures. */
@RunWith(AndroidJUnit4::class)
class RepositoryContractTest {
    private lateinit var appContext: Context
    private lateinit var repositoryContext: Context
    private lateinit var db: CinemaLibraryDatabase
    private lateinit var server: ContractServer
    private val preferencesName = "repository-contract-settings"

    @Before fun before() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.deleteSharedPreferences(preferencesName)
        // Repository configuration must never change the UI tests' cinema_settings file.
        repositoryContext = object : ContextWrapper(appContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                appContext.getSharedPreferences(preferencesName, mode)
        }
        db = Room.inMemoryDatabaseBuilder(appContext, CinemaLibraryDatabase::class.java).build()
        server = ContractServer()
    }

    @After fun after() {
        try {
            if (::server.isInitialized) server.close()
        } finally {
            if (::db.isInitialized) db.close()
            if (::appContext.isInitialized) appContext.deleteSharedPreferences(preferencesName)
        }
    }

    private fun emptyImport() = LegacyLibraryImport(emptyList(), emptyList(), emptyList())

    private fun library(importer: () -> LegacyLibraryImport = ::emptyImport) =
        LocalMediaLibrary({ db }, importer)

    private fun repository(store: LocalMediaLibrary = library()) =
        LibreTvRepository(repositoryContext, store).apply { configureServer(server.base) }

    private fun sourceItem() = MediaItem("source-42", "Synthetic series", isTv = true,
        source = TvSource("fixture", "Synthetic source", "${server.base}/provider"))

    private fun detailResponse(names: List<String>, episodes: List<String>) = JSONObject()
        .put("videoInfo", JSONObject().put("desc", "Synthetic detail only")
            .put("year", "2026").put("season", 2).put("episodeNames", JSONArray(names)))
        .put("episodes", JSONArray(episodes))

    @Test fun playableDetailSurvivesImportFailureAndWarningClearsAfterRecovery() = runBlocking {
        val failImport = AtomicBoolean(true)
        val store = library {
            check(!failImport.get()) { "Synthetic legacy import failure" }
            emptyImport()
        }
        val repo = repository(store)
        val episodes = listOf("${server.base}/synthetic-video.mp4?episode=0")
        server.respond(detailResponse(listOf("First episode"), episodes))

        val detail = repo.detail(sourceItem())

        assertEquals(episodes, detail.episodes)
        assertEquals(listOf("First episode"), detail.episodeNames)
        assertTrue("Persistence failure should be visible without blocking playback", repo.persistenceWarning.isNotBlank())
        assertFalse(store.snapshot.ready)
        withContext(Dispatchers.IO) {
            assertNull(db.libraryDao().metadata(LocalMediaLibrary.LEGACY_IMPORT_KEY))
            assertTrue(db.libraryDao().entries().isEmpty())
        }
        assertEquals("/api/detail", server.takeRequest().uri.path)

        failImport.set(false)
        assertEquals(episodes, repo.detail(sourceItem()).episodes)
        assertEquals("", repo.persistenceWarning)
        assertTrue(store.snapshot.ready)
        assertEquals(episodes, store.snapshot.records.single().item.episodes)
    }

    @Test fun episodeNamesKeepBlankDuplicateAndBeyondFiftiethPositions() = runBlocking {
        val names = MutableList(64) { "Episode ${it + 1}" }.apply {
            this[0] = ""
            this[1] = "Repeated label"
            this[2] = "Repeated label"
            this[51] = ""
            this[63] = "Final positional label"
        }
        val episodes = List(names.size) { "${server.base}/synthetic-video.mp4?episode=$it" }
        val store = library()
        val repo = repository(store)
        server.respond(detailResponse(names, episodes))

        val detail = repo.detail(sourceItem())

        assertEquals(64, detail.episodeNames.size)
        assertEquals(names, detail.episodeNames)
        assertEquals(episodes, detail.episodes)
        assertEquals(2, detail.seasonNumber)
        assertEquals(names, store.snapshot.records.single().item.episodeNames)
        assertEquals("", repo.persistenceWarning)
    }

    @Test fun licensedCatalogRetainsProvenanceAndExpiresAtExactBoundary() = runBlocking {
        val expiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
        val provider = "Synthetic licensed provider"
        val attribution = "Synthetic fixture attribution, not a real metadata licence"
        server.respond(catalogResponse(provider = provider, attribution = attribution,
            license = license(Instant.ofEpochMilli(expiry).toString())))
        val repo = repository().apply { configureMetadata(server.base, "synthetic-test-token") }

        val catalog = repo.catalog("合成 query", isTv = true)
        val item = catalog.items.single()

        assertTrue(catalog.available)
        assertEquals(provider, catalog.provider)
        assertEquals(attribution, catalog.attribution)
        assertEquals(provider, item.catalogProvider)
        assertEquals(attribution, item.catalogAttribution)
        assertEquals(expiry, item.catalogLicenseExpiresAt)
        assertEquals(expiry, item.ratingLicenseExpiresAt)
        assertEquals(mapOf(provider.lowercase(Locale.ROOT) to "fixture-7"), item.metadataIds)
        assertEquals(listOf("Synthetic alias"), item.aliases)
        assertEquals(listOf("Synthetic actor"), item.actors)
        assertTrue(item.isTv)
        assertNull(item.source)
        assertEquals("Douban", item.ratingSource)
        assertEquals(12, item.doubanTopRank)
        assertEquals(8.5, requireNotNull(RatingPolicy.score(item, expiry - 1)), 0.0)
        assertTrue(RatingPolicy.isDoubanTop(item, expiry - 1))
        assertNotNull(item.usableMetadata(expiry - 1))
        assertNull(item.usableMetadata(expiry))
        assertFalse(CatalogLicensePolicy.isAvailable(item, expiry))
        assertFalse(SearchFilters(doubanTopOnly = true).matches(item, expiry))
        assertNull(RatingPolicy.score(item, expiry))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/catalog", request.uri.path)
        assertEquals("tv", request.uri.getQueryParameter("type"))
        assertEquals("50", request.uri.getQueryParameter("limit"))
        assertEquals("合成 query", request.uri.getQueryParameter("query"))
        assertEquals(setOf("type", "limit", "query"), request.uri.queryParameterNames)
        assertEquals("Bearer synthetic-test-token", request.headers["authorization"])
    }

    @Test fun expiredAndMalformedAttachedLicensesAreRejectedInsteadOfBecomingUnrestricted() = runBlocking {
        val repo = repository().apply { configureMetadata(server.base, "synthetic-test-token") }
        val invalidLicenses = listOf(
            license(Instant.ofEpochMilli(System.currentTimeMillis() - 60_000).toString()),
            license("not-an-ISO-expiry"),
            JSONObject().put("name", "Missing synthetic expiry")
        )
        invalidLicenses.forEach { invalid ->
            server.respond(catalogResponse(license = invalid))
            val catalog = repo.catalog()
            assertFalse("An invalid attached licence must not be advertised as available", catalog.available)
            assertTrue("Do not decode expired metadata with unrestricted zero expiry", catalog.items.isEmpty())
        }
    }

    @Test fun tmdbWithoutAttachedLicenseKeepsItsOwnRatingAndNeverBecomesDoubanTop() = runBlocking {
        server.respond(catalogResponse(provider = "TMDB", ratingSource = "TMDB", license = null))
        val repo = repository().apply { configureMetadata(server.base, "synthetic-test-token") }

        val item = repo.catalog().items.single()

        assertEquals("TMDB", item.catalogProvider)
        assertEquals("TMDB", item.ratingSource)
        assertEquals(mapOf("tmdb" to "fixture-7"), item.metadataIds)
        assertEquals(0L, item.catalogLicenseExpiresAt)
        assertEquals(0L, item.ratingLicenseExpiresAt)
        assertNull(item.doubanTopRank)
        assertFalse(RatingPolicy.isDoubanTop(item))
        assertEquals(8.5, requireNotNull(RatingPolicy.score(item)), 0.0)
        assertNotNull(item.usableMetadata())
    }

    @Test fun unlicensedDoubanScoreAndChartAreNotExposed() = runBlocking {
        server.respond(catalogResponse(license = null))
        val repo = repository().apply { configureMetadata(server.base, "synthetic-test-token") }

        val item = repo.catalog().items.single()

        assertEquals("", item.rating)
        assertEquals("", item.ratingSource)
        assertNull(item.doubanTopRank)
        assertNull(RatingPolicy.score(item))
        assertFalse(SearchFilters(ratingSource = "Douban", minRating = 8.0).matches(item))
    }

    private fun license(expiry: String) = JSONObject().put("name", "Synthetic test licence")
        .put("url", "https://licence.example.test/synthetic").put("expiresAt", expiry)

    private fun catalogResponse(
        provider: String = "Synthetic licensed provider",
        attribution: String = "Synthetic fixture attribution only",
        ratingSource: String = "Douban",
        license: JSONObject?
    ) = JSONObject().put("status", "available").put("provider", provider)
        .put("attribution", attribution).put("license", license ?: JSONObject.NULL)
        .put("items", JSONArray().put(JSONObject()
            .put("id", "synthetic:tv:fixture-7").put("provider", provider).put("providerId", "fixture-7")
            .put("title", "Synthetic catalog series").put("type", "tv").put("year", "2026")
            .put("poster", "https://art.example.test/synthetic-poster.jpg")
            .put("backdrop", "https://art.example.test/synthetic-backdrop.jpg")
            .put("overview", "Only synthetic data; no public metadata or playback is requested.")
            .put("aliases", JSONArray(listOf("Synthetic alias")))
            .put("actors", JSONArray(listOf("Synthetic actor")))
            .put("rating", 8.5).put("ratingScale", 10).put("ratingSource", ratingSource)
            .put("topRank", 12).put("topSource", "Douban")))

    private data class CapturedRequest(val method: String, val uri: Uri, val headers: Map<String, String>)

    /** One connection at a time is enough for these sequential repository contract calls. */
    private class ContractServer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val requests = LinkedBlockingQueue<CapturedRequest>()
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var running = true
        @Volatile private var body = "{}".toByteArray(Charsets.UTF_8)
        val base = "http://127.0.0.1:${socket.localPort}"
        private val worker = Thread({
            while (running) {
                try { socket.accept().use(::handle) }
                catch (error: Exception) {
                    if (running) failure.compareAndSet(null, error)
                    break
                }
            }
        }, "repository-contract-http").apply { isDaemon = true; start() }

        fun respond(json: JSONObject) { body = json.toString().toByteArray(Charsets.UTF_8) }

        fun takeRequest(): CapturedRequest = requireNotNull(requests.poll(5, TimeUnit.SECONDS)) {
            "Expected a local repository request; fixture failure: ${failure.get()}"
        }

        private fun handle(client: Socket) {
            client.soTimeout = 3000
            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = requireNotNull(reader.readLine()).split(' ')
            require(request.size >= 2)
            val headers = linkedMapOf<String, String>()
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                headers[header.substringBefore(':').trim().lowercase(Locale.ROOT)] = header.substringAfter(':').trim()
            }
            requests.put(CapturedRequest(request[0], Uri.parse(request[1]), headers))
            val response = body
            client.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(response)
                flush()
            }
        }

        override fun close() {
            running = false
            socket.close()
            worker.join(4000)
            assertFalse("Local fixture thread failed to stop", worker.isAlive)
            assertNull("Local HTTP fixture failed", failure.get())
        }
    }
}
