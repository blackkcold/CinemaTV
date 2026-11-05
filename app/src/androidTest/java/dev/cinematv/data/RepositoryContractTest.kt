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
import java.util.UUID
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
    private val preferencesName = "repository-contract-settings-${UUID.randomUUID()}"
    private lateinit var fixturePreferences: SharedPreferences

    @Before fun before() {
        appContext = ApplicationProvider.getApplicationContext()
        fixturePreferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertTrue("Repository fixture preferences must start empty", fixturePreferences.all.isEmpty())
        // Repository configuration must never change the UI tests' cinema_settings file.
        repositoryContext = object : ContextWrapper(appContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                fixturePreferences
        }
        db = Room.inMemoryDatabaseBuilder(appContext, CinemaLibraryDatabase::class.java).build()
        server = ContractServer()
    }

    @After fun after() {
        try {
            if (::server.isInitialized) server.close()
        } finally {
            try {
                if (::db.isInitialized) db.close()
            } finally {
                if (::fixturePreferences.isInitialized) {
                    assertTrue("Repository fixture cleanup must persist", fixturePreferences.edit().clear().commit())
                }
                if (::appContext.isInitialized) appContext.deleteSharedPreferences(preferencesName)
            }
        }
    }

    private fun emptyImport() = LegacyLibraryImport(emptyList(), emptyList(), emptyList())

    private fun library(importer: () -> LegacyLibraryImport = ::emptyImport) =
        LocalMediaLibrary({ db }, importer)

    private fun repository(store: LocalMediaLibrary = library()) =
        LibreTvRepository(repositoryContext, store).apply { configureServer(this@RepositoryContractTest.server.base) }

    private fun sourceItem() = MediaItem("source-42", "Synthetic series", isTv = true,
        source = TvSource("fixture", "Synthetic source", "${this@RepositoryContractTest.server.base}/provider"))

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
        val episodes = listOf("${this@RepositoryContractTest.server.base}/synthetic-video.mp4?episode=0")
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
        val episodes = List(names.size) { "${this@RepositoryContractTest.server.base}/synthetic-video.mp4?episode=$it" }
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
        val repo = repository().apply { configureMetadata(this@RepositoryContractTest.server.base, "synthetic-test-token") }

        val catalog = repo.catalog("合成 query", isTv = true)
        val item = catalog.items.single()

        assertTrue(catalog.available)
        assertNull("Old BFF responses remain supported without invented acquisition status", catalog.acquisition)
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
        val repo = repository().apply { configureMetadata(this@RepositoryContractTest.server.base, "synthetic-test-token") }
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
        val repo = repository().apply { configureMetadata(this@RepositoryContractTest.server.base, "synthetic-test-token") }

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
        val repo = repository().apply { configureMetadata(this@RepositoryContractTest.server.base, "synthetic-test-token") }

        val item = repo.catalog().items.single()

        assertEquals("", item.rating)
        assertEquals("", item.ratingSource)
        assertNull(item.doubanTopRank)
        assertNull(RatingPolicy.score(item))
        assertFalse(SearchFilters(ratingSource = "Douban", minRating = 8.0).matches(item))
    }

    @Test fun partialAcquisitionKeepsSnapshotCountsDatesAndLegalAttributionSeparate() = runBlocking {
        val now = System.currentTimeMillis()
        val expiry = now + TimeUnit.DAYS.toMillis(1)
        val fetched = now - TimeUnit.HOURS.toMillis(2)
        val retry = now + 60_000
        val response = catalogResponse(provider = "Douban", license = license(Instant.ofEpochMilli(expiry).toString()))
            .put("nextOffset", 50).put("acquisition", acquisitionFixture("partial", fetched, retry))
        server.respond(response)
        val repo = repository().apply { configureMetadata(this@RepositoryContractTest.server.base, "synthetic-test-token") }

        val catalog = repo.catalog("Synthetic", topOnly = true)
        val state = requireNotNull(catalog.acquisition)

        assertEquals("stale-cache", state.source)
        assertEquals(fetched, state.fetchedAt)
        assertTrue(state.stale)
        assertFalse(state.complete)
        assertEquals(250, state.expectedTotal)
        assertEquals(50, state.availableTotal)
        assertEquals(1, catalog.items.size)
        assertEquals(listOf("synthetic-partial-warning"), state.warnings)
        assertFalse(state.blocked)
        assertEquals(retry, state.retryAt)
        assertEquals("synthetic-snapshot-a", state.snapshotId)
        assertTrue(state.refreshing)
        assertEquals(CatalogAcquisitionState.PARTIAL, state.state)
        assertEquals(50, catalog.nextOffset)
        assertEquals(expiry, catalog.licenseExpiresAt)
        assertEquals(expiry, catalog.items.single().catalogLicenseExpiresAt)
        assertEquals("Synthetic fixture attribution only", catalog.items.single().catalogAttribution)
        val status = CatalogStatus.message(CatalogChartSnapshot(catalog, catalog.items), now)
        assertTrue(status.contains("50/250"))
        assertTrue(status.contains("已载入 1 条"))
        assertTrue(status.contains("旧缓存"))
        assertFalse(status.contains("synthetic-partial-warning"))
        assertNull(catalog.items.single().usableMetadata(expiry))
        val request = server.takeRequest()
        assertEquals("douban-top250", request.uri.getQueryParameter("chart"))
    }

    @Test fun acquisitionStatesDecodeWithoutEnablingForbiddenPolling() = runBlocking {
        val now = System.currentTimeMillis()
        val repo = repository().apply { configureMetadata(this@RepositoryContractTest.server.base, "synthetic-test-token") }
        mapOf("rate_limited" to CatalogAcquisitionState.RATE_LIMITED,
            "access_blocked" to CatalogAcquisitionState.ACCESS_BLOCKED,
            "disabled" to CatalogAcquisitionState.DISABLED).forEach { (name, expected) ->
            server.respond(catalogResponse(license = license(Instant.ofEpochMilli(now + 86_400_000).toString()))
                .put("items", JSONArray()).put("status", "empty")
                .put("acquisition", acquisitionFixture(name, now - 1000, now - 1)
                    .put("blocked", name == "access_blocked")))
            val catalog = repo.catalog(topOnly = true)
            assertFalse(catalog.available)
            assertEquals(expected, catalog.acquisition?.state)
            assertNull(CatalogRefreshPolicy.nextDelay(catalog, now, now + 90_000))
        }
    }

    @Test fun emptyRefreshingSnapshotIsNotReportedAsUnavailableOrComplete() = runBlocking {
        val now = System.currentTimeMillis()
        server.respond(catalogResponse(license = license(Instant.ofEpochMilli(now + 86_400_000).toString()))
            .put("status", "empty").put("items", JSONArray())
            .put("acquisition", acquisitionFixture("refreshing", now, now)
                .put("availableTotal", 0).put("source", JSONObject.NULL)
                .put("fetchedAt", JSONObject.NULL).put("snapshotId", JSONObject.NULL).put("stale", false)))
        val repo = repository().apply { configureMetadata(this@RepositoryContractTest.server.base, "synthetic-test-token") }

        val catalog = repo.catalog(topOnly = true)

        assertTrue(catalog.items.isEmpty())
        assertEquals(CatalogAcquisitionState.REFRESHING, catalog.acquisition?.state)
        assertNull(catalog.acquisition?.fetchedAt)
        assertNull(catalog.acquisition?.snapshotId)
        assertNull(catalog.acquisition?.source)
        assertEquals(5_000L, CatalogRefreshPolicy.nextDelay(catalog, now, now + 90_000))
        val text = CatalogStatus.message(CatalogChartSnapshot(catalog), now)
        assertTrue(text.contains("后台更新中"))
        assertTrue(text.contains("快照未完整 0/250"))
    }

    @Test fun expiredLicenseCannotBeRevivedByFreshAcquisitionMetadata() = runBlocking {
        val now = System.currentTimeMillis()
        server.respond(catalogResponse(license = license(Instant.ofEpochMilli(now - 1).toString()))
            .put("acquisition", acquisitionFixture("fresh", now, now).put("complete", true).put("availableTotal", 250)))
        val repo = repository().apply { configureMetadata(this@RepositoryContractTest.server.base, "synthetic-test-token") }

        val catalog = repo.catalog(topOnly = true)

        assertTrue(catalog.licenseRejected)
        assertFalse(catalog.available)
        assertTrue(catalog.items.isEmpty())
        assertNull(CatalogRefreshPolicy.nextDelay(catalog, now, now + 90_000))
        assertTrue(CatalogStatus.message(CatalogChartSnapshot(catalog), now).contains("授权已过期或无效"))
    }

    @Test fun pagingQueryAndMalformedAcquisitionRemainBoundedAndReadable() = runBlocking {
        val now = System.currentTimeMillis()
        server.respond(catalogResponse(license = license(Instant.ofEpochMilli(now + 86_400_000).toString()))
            .put("nextOffset", 100).put("acquisition", acquisitionFixture("unknown-provider-state", now, now)
                .put("fetchedAt", "not-a-date").put("retryAt", "not-a-date")))
        val repo = repository().apply { configureMetadata(this@RepositoryContractTest.server.base, "synthetic-test-token") }
        val catalog = repo.catalog(offset = 50, topOnly = true)
        assertEquals(100, catalog.nextOffset)
        assertNull(catalog.acquisition?.fetchedAt)
        assertNull(catalog.acquisition?.retryAt)
        assertEquals(CatalogAcquisitionState.UNAVAILABLE, catalog.acquisition?.state)
        val request = server.takeRequest()
        assertEquals("50", request.uri.getQueryParameter("offset"))
        assertEquals("douban-top250", request.uri.getQueryParameter("chart"))
        assertFalse(CatalogStatus.message(CatalogChartSnapshot(catalog), now).contains("unknown-provider-state"))
    }

    private fun acquisitionFixture(state: String, fetched: Long, retry: Long) = JSONObject()
        .put("source", "stale-cache").put("fetchedAt", Instant.ofEpochMilli(fetched).toString())
        .put("stale", true).put("complete", false).put("expectedTotal", 250).put("availableTotal", 50)
        .put("warnings", JSONArray(listOf("synthetic-partial-warning"))).put("blocked", false)
        .put("retryAt", Instant.ofEpochMilli(retry).toString()).put("snapshotId", "synthetic-snapshot-a")
        .put("refreshing", true).put("state", state)

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
