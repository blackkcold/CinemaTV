package dev.cinematv.data

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.data.library.CinemaLibraryDatabase
import dev.cinematv.data.library.LegacyLibraryImport
import dev.cinematv.data.library.LocalMediaLibrary
import dev.cinematv.data.library.MediaItemCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual SQLite/Room bridge, not a mocked DAO or destructive migration. */
@RunWith(AndroidJUnit4::class)
class LocalMediaLibraryTest {
    private lateinit var context: Context
    private lateinit var db: CinemaLibraryDatabase
    private val databaseName = "library-migration-test.db"
    private val prefsName = "library-migration-test"
    private val first = MediaItem("42", "Test series", source = TvSource("s", "Source", "https://example.test"),
        isTv = true, positionMs = 1200, lastEpisode = 1, durationMs = 8000)
    private val second = first.copy(id = "43", title = "Another series", positionMs = 400, lastEpisode = 0)

    @Before fun before() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        openDatabase()
    }
    private fun openDatabase() { db = Room.databaseBuilder(context, CinemaLibraryDatabase::class.java, databaseName).build() }
    @After fun after() {
        db.close()
        context.deleteDatabase(databaseName)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun library() = LocalMediaLibrary({ db }, {
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        LegacyLibraryImport.read(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE))
    }, now = { 100_000L })

    @Test fun legacyImportIsAtomicIdempotentAndSurvivesReopen() = runBlocking {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val favorites = JSONArray(listOf(MediaItemCodec.json(first))).toString()
        prefs.edit().putString("favorites", favorites)
            .putString("history", JSONArray(listOf(MediaItemCodec.json(first), MediaItemCodec.json(second))).toString())
            .putString("library_entries", JSONArray(listOf(
                MediaItemCodec.json(first).put("state", "WATCHING"),
                MediaItemCodec.json(second).put("state", "WATCHED"))).toString()).commit()
        val store = library()
        assertFalse(store.snapshot.ready)
        val report = withContext(Dispatchers.Main) { store.initialize() }
        assertTrue(report.imported)
        assertEquals(2, report.importedEntries)
        assertEquals(listOf("42", "43"), store.snapshot.history().map { it.id })
        assertEquals(LibraryStatus.WATCHING, store.snapshot.status(first))
        assertEquals(1200L, store.snapshot.progress(first, 1)?.positionMs)
        assertEquals(8000L, store.snapshot.progress(first, 1)?.durationMs)
        assertTrue(store.snapshot.isFavorite(first))
        assertEquals(favorites, prefs.getString("favorites", null))
        store.toggleFavorite(first)
        db.close()
        openDatabase()
        val reopened = library()
        assertFalse(reopened.initialize().imported)
        assertFalse(reopened.snapshot.isFavorite(first)) // Legacy favorite must not resurrect.
        assertEquals(LibraryStatus.WATCHING, reopened.snapshot.status(first))
        assertEquals(1200L, reopened.snapshot.progress(first, 1)?.positionMs)
    }

    @Test fun progressIsPerEpisodeAndFavoritesSurviveHistoryClear() = runBlocking {
        val store = library()
        store.initialize()
        store.saveProgress(first, 2500, 0, 10_000)
        store.saveProgress(first, 750, 1, 4000)
        store.saveProgress(first, 900, 1, 0)
        assertEquals(2500L, store.snapshot.progress(first, 0)?.positionMs)
        assertEquals(900L, store.snapshot.progress(first, 1)?.positionMs)
        assertEquals(4000L, store.snapshot.progress(first, 1)?.durationMs)
        assertNull(store.snapshot.progress(first, 2))
        store.setStatus(first, LibraryStatus.WATCHING)
        store.toggleFavorite(first)
        store.clearHistory()
        assertTrue(store.snapshot.history().isEmpty())
        assertNull(store.snapshot.progress(first, 0))
        assertTrue(store.snapshot.isFavorite(first))
        assertEquals(LibraryStatus.WATCHING, store.snapshot.status(first))
        assertEquals(0L, store.snapshot.favorites().single().positionMs)
    }

    @Test fun corruptLegacyArrayDoesNotMarkMigrationCompleteOrDiscardValidRecords() = runBlocking {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString("favorites", JSONArray(listOf(MediaItemCodec.json(first))).toString())
            .putString("history", "broken json").commit()
        val store = library()
        assertTrue(runCatching { store.initialize() }.isFailure)
        withContext(Dispatchers.IO) {
            assertNull(db.libraryDao().metadata(LocalMediaLibrary.LEGACY_IMPORT_KEY))
            assertTrue(db.libraryDao().entries().isEmpty())
        }
        prefs.edit().putString("history", "[]").commit()
        assertTrue(store.initialize().imported)
        assertTrue(store.snapshot.isFavorite(first))
    }

    @Test fun knownSeasonEnrichmentRekeysOnlyUnknownSeasonProgress() = runBlocking {
        val store = library()
        store.saveProgress(first, 1700, 0, 9000)
        val known = first.copy(seasonNumber = 1, episodes = listOf("https://example.test/1"))
        store.cacheItem(known)
        assertEquals(1700L, store.snapshot.progress(known, 0)?.positionMs)
        assertEquals(1700L, store.snapshot.progress(first, 0)?.positionMs)
        assertNull(store.snapshot.progress(known.copy(seasonNumber = 2), 0))
        store.cacheItem(known.copy(seasonNumber = 2))
        assertNull(store.snapshot.progress(known.copy(seasonNumber = 2), 0))
        assertEquals(1700L, store.snapshot.progress(known, 0)?.positionMs)
    }

    @Test fun newRatingProvenanceDoesNotRetainDoubanRank() = runBlocking {
        val store = library()
        store.setStatus(first.copy(rating = "9.0", ratingSource = "Douban", doubanTopRank = 10,
            ratingLicenseExpiresAt = 200_000), LibraryStatus.WANT_TO_WATCH)
        store.cacheItem(first.copy(rating = "7.5", ratingSource = "TMDB"))
        val saved = store.snapshot.byStatus(LibraryStatus.WANT_TO_WATCH).single()
        assertNull(saved.doubanTopRank)
        assertEquals("TMDB", saved.ratingSource)
        assertEquals(0L, saved.ratingLicenseExpiresAt)
    }

    @Test fun snapshotCodecRoundTripsRichMetadataAndEpisodeNames() {
        val item = first.copy(aliases = listOf("别名"), actors = listOf("演员"), rating = "9.0",
            ratingSource = "Douban", doubanTopRank = 12, metadataIds = mapOf("tmdb" to "123"),
            episodes = listOf("https://example.test/1", "https://example.test/2", "https://example.test/3"),
            episodeNames = listOf("", "重复名称", "重复名称"),
            seasonNumber = 2, description = "Description", ratingLicenseExpiresAt = 9_000_000L,
            mediaTypeKnown = false, catalogProvider = "licensed-test-provider", catalogAttribution = "Licensed test data",
            catalogLicenseExpiresAt = 8_000_000L)
        assertEquals(item, MediaItemCodec.decode(MediaItemCodec.encode(item)))
    }
}
