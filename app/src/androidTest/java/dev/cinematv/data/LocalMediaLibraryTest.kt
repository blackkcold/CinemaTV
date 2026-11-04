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
        isTv = true, episodes = listOf("https://example.test/one", "https://example.test/two"), positionMs = 1200, lastEpisode = 1, durationMs = 8000)
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

    @Test fun knownSeasonNeverAdoptsUnknownSeasonProgress() = runBlocking {
        val store = library()
        store.saveProgress(first, 1700, 0, 9000)
        val known = first.copy(seasonNumber = 1, episodes = listOf("https://example.test/1"))
        store.cacheItem(known)
        assertNull(store.snapshot.progress(known, 0))
        assertEquals(1700L, store.snapshot.progress(first, 0)?.positionMs)
        assertEquals(1, store.snapshot.quarantinedProgress.size)
        assertNull(store.snapshot.progress(known.copy(seasonNumber = 2), 0))
        store.cacheItem(known.copy(seasonNumber = 2))
        assertNull(store.snapshot.progress(known.copy(seasonNumber = 2), 0))
        assertNull(store.snapshot.progress(known, 0))
    }

    @Test fun reorderRotationRemovalAndQuarantineSurviveReopen() = runBlocking {
        val store = library()
        val original = first.copy(episodeNames = listOf("Pilot", "Return"))
        store.saveProgress(original, 1700, 1, 9000)
        val reordered = original.copy(episodes = listOf("https://cdn.test/b?token=new", "https://cdn.test/a?token=new"),
            episodeNames = listOf("Return", "Pilot"))
        store.cacheItem(reordered)
        assertEquals(1700L, store.snapshot.progress(reordered, 0)?.positionMs)
        assertEquals(0, store.snapshot.history().single().lastEpisode)
        assertEquals(1700L, store.snapshot.history().single().positionMs)
        val removed = reordered.copy(episodes = listOf("https://cdn.test/a?token=latest"), episodeNames = listOf("Pilot"))
        store.cacheItem(removed)
        assertNull(store.snapshot.progress(removed, 0))
        assertEquals(1, store.snapshot.quarantinedProgress.size)
        db.close()
        openDatabase()
        val reopened = library()
        reopened.initialize()
        assertEquals(1, reopened.snapshot.quarantinedProgress.size)
        assertEquals(1700L, reopened.snapshot.quarantinedProgress.single().progress.positionMs)
        reopened.clearHistory()
        assertTrue(reopened.snapshot.quarantinedProgress.isEmpty())
        withContext(Dispatchers.IO) {
            assertNull(db.libraryDao().metadata("checkpoint_evidence:${MediaDomainMapper.episodeId(original, 1)}"))
        }
    }

    @Test fun authoritativeEmptyPlaylistIsPersistedButMetadataOnlyRefreshDoesNotClear() = runBlocking {
        val store = library()
        store.saveProgress(first, 1000, 0, 8000)
        store.cacheItem(first.copy(episodes = emptyList()))
        assertEquals(1000L, store.snapshot.progress(store.snapshot.history().single(), 0)?.positionMs)
        store.cacheItem(first.copy(episodes = emptyList(), playlistKnown = true))
        assertTrue(store.snapshot.history().single().episodes.isEmpty())
        assertEquals(1, store.snapshot.quarantinedProgress.size)
        db.close()
        openDatabase()
        val reopened = library()
        reopened.initialize()
        assertTrue(reopened.snapshot.history().single().playlistKnown)
        assertTrue(reopened.snapshot.history().single().episodes.isEmpty())
        assertEquals(1, reopened.snapshot.quarantinedProgress.size)
    }

    @Test fun metadataOnlyLegacyHistoryCannotBorrowFavoritePlaylistIdentity() = runBlocking {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val metadataOnly = first.copy(episodes = emptyList(), episodeNames = emptyList(), positionMs = 4567)
        prefs.edit().putString("favorites", JSONArray(listOf(MediaItemCodec.json(first))).toString())
            .putString("history", JSONArray(listOf(MediaItemCodec.json(metadataOnly))).toString()).commit()
        val store = library()
        store.initialize()
        assertEquals(first.episodes, store.snapshot.favorites().single().episodes)
        assertNull(store.snapshot.progress(first, metadataOnly.lastEpisode))
        val held = store.snapshot.quarantinedProgress.single()
        assertEquals(4567L, held.progress.positionMs)
        assertEquals(dev.cinematv.data.library.QuarantineReason.MISSING_OR_AMBIGUOUS_IDENTITY, held.reason)
        withContext(Dispatchers.IO) {
            val evidence = requireNotNull(db.libraryDao().metadata("checkpoint_evidence:${held.progress.episodeId}"))
            assertTrue(MediaItemCodec.decode(evidence).episodes.isEmpty())
        }
        db.close()
        openDatabase()
        val reopened = library()
        reopened.initialize()
        assertNull(reopened.snapshot.progress(first, metadataOnly.lastEpisode))
        assertEquals(4567L, reopened.snapshot.quarantinedProgress.single().progress.positionMs)
    }

    @Test fun lateCallbacksNeverReplaceNewerCachedPlaylist() = runBlocking {
        val store = library()
        val named = first.copy(episodeNames = listOf("Pilot", "Return"))
        val variants = listOf(
            named.copy(episodes = listOf("new-b", "new-a"), episodeNames = listOf("Return", "Pilot")) to 0,
            named.copy(episodes = listOf("new-a"), episodeNames = listOf("Pilot")) to -1,
            named.copy(episodes = emptyList(), episodeNames = emptyList(), playlistKnown = true) to -1,
            named.copy(episodes = listOf("new-a", "new-b")) to 1,
            named.copy(seasonNumber = 1) to -1
        )
        variants.forEachIndexed { index, (template, expectedIndex) ->
            val played = named.copy(id = "stale-$index")
            val current = template.copy(id = played.id)
            store.cacheItem(played)
            store.cacheItem(current)
            store.saveProgress(played, 3456, 1, 9000)
            val saved = store.snapshot.history().single { it.id == played.id }
            assertEquals(current.episodes, saved.episodes)
            assertEquals(current.episodeNames, saved.episodeNames)
            assertEquals(current.seasonNumber, saved.seasonNumber)
            assertEquals(expectedIndex, saved.lastEpisode)
            assertEquals(if (expectedIndex >= 0) 3456L else 0L, saved.positionMs)
        }
        val urlOnly = first.copy(id = "stale-urls", episodeNames = emptyList())
        val rotated = urlOnly.copy(episodes = listOf("rotated-a", "rotated-b"))
        store.cacheItem(rotated)
        store.saveProgress(urlOnly, 2222, 1, 9000)
        assertEquals(rotated.episodes, store.snapshot.history().single { it.id == urlOnly.id }.episodes)
        assertNull(store.snapshot.progress(rotated, 1))
        assertEquals(2222L, store.snapshot.quarantinedProgress.single {
            it.progress.sourceMappingId == MediaDomainMapper.entryId(urlOnly)
        }.progress.positionMs)
    }

    @Test fun oldV1PositionCheckpointRemainsIntactAndQuarantinedAfterReopen() = runBlocking {
        val store = library()
        store.cacheItem(first)
        val id = "episode:82033457fbd9dcf9db9da327a317448fb3a1c904f5e1b6484fa88679d93443f4"
        withContext(Dispatchers.IO) {
            assertEquals(1, db.openHelper.readableDatabase.version)
            // Original v1 hash for source listing + unknown season + index 1.
            // Strip new optional JSON fields to exercise a genuinely old snapshot format.
            val source = requireNotNull(db.libraryDao().source(MediaDomainMapper.entryId(first)))
            val oldJson = org.json.JSONObject(source.itemJson).apply {
                remove("episodeIds"); remove("playlistKnown")
            }.toString()
            db.libraryDao().upsertSource(source.copy(itemJson = oldJson))
            db.libraryDao().upsertProgress(dev.cinematv.data.library.EpisodeProgressEntity(
                id, MediaDomainMapper.entryId(first), 1, 777, 8000, 12))
        }
        db.close()
        openDatabase()
        val reopened = library()
        reopened.initialize()
        assertNull(reopened.snapshot.progress(first, 1))
        val held = reopened.snapshot.quarantinedProgress.single()
        assertEquals(dev.cinematv.data.library.QuarantineReason.LEGACY_POSITION_ONLY, held.reason)
        assertEquals(777L, held.progress.positionMs)
        reopened.cacheItem(first.copy(episodes = first.episodes.reversed()))
        withContext(Dispatchers.IO) {
            assertEquals(777L, db.libraryDao().progress(id)?.positionMs)
            assertNotNull(db.libraryDao().metadata("checkpoint_evidence:$id"))
        }
        reopened.clearHistory()
        assertTrue(reopened.snapshot.episodeProgress.isEmpty())
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
            episodeNames = listOf("", "重复名称", "重复名称"), episodeIds = listOf("a", "", "a"),
            seasonNumber = 2, description = "Description", ratingLicenseExpiresAt = 9_000_000L,
            mediaTypeKnown = false, catalogProvider = "licensed-test-provider", catalogAttribution = "Licensed test data",
            catalogLicenseExpiresAt = 8_000_000L)
        assertEquals(item, MediaItemCodec.decode(MediaItemCodec.encode(item)))
    }
}
