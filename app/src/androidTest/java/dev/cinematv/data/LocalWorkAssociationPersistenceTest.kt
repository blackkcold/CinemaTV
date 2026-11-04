package dev.cinematv.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.data.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room transactions, reopen/legacy coexistence, unsupported versions and stale writers. */
@RunWith(AndroidJUnit4::class)
class LocalWorkAssociationPersistenceTest {
    private lateinit var db: CinemaLibraryDatabase
    private lateinit var context: Context
    private val name = "manual-work-associations-test.db"
    private val a = MediaItem("a", "Source A", source = TvSource("a", "A", "https://a.example"),
        isTv = true, seasonNumber = 1, metadataIds = mapOf("tmdb" to "11"),
        episodes = listOf("https://a.example/play"), episodeIds = listOf("pilot"))
    private val b = a.copy(id = "b", title = "Source B", source = TvSource("b", "B", "https://b.example"),
        metadataIds = mapOf("tmdb" to "22"))
    private fun store(clock: () -> Long = { 100L }) = LocalMediaLibrary({ db }, {
        LegacyLibraryImport(emptyList(), emptyList(), emptyList())
    }, now = clock)
    private fun open() { db = Room.databaseBuilder(context, CinemaLibraryDatabase::class.java, name).build() }
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(name); open() }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    @Test fun associationAndUndoSurviveReopenWithoutRekeyingProgressOrFlags() = runBlocking {
        val library = store()
        library.cacheItem(a); library.cacheItem(b); library.toggleFavorite(a)
        library.setStatus(b, LibraryStatus.WATCHED); library.saveProgress(a, 12000, 0, 80000)
        val before = library.snapshot.episodeProgress
        library.associateWork(a, b, 0, library.snapshot.associations.revision)
        val linked = library.snapshot.associations
        db.close(); open()
        val reopened = store(); reopened.initialize()
        assertEquals(linked, reopened.snapshot.associations)
        assertEquals(before, reopened.snapshot.episodeProgress)
        assertTrue(reopened.snapshot.isFavorite(a)); assertFalse(reopened.snapshot.isFavorite(b))
        assertEquals(LibraryStatus.WATCHED, reopened.snapshot.status(b))
        assertEquals(a.metadataIds, reopened.snapshot.records.first { it.item.id == "a" }.item.metadataIds)
        reopened.unlinkWork(MediaDomainMapper.entryId(a), linked.revision)
        db.close(); open()
        val again = store(); again.initialize()
        again.undoWorkAssociation(again.snapshot.associations.revision)
        assertEquals(linked.entries, again.snapshot.associations.entries)
        assertEquals(before, again.snapshot.episodeProgress)
    }
    @Test fun concurrentInstancesAndChangedEvidenceCannotOverwriteConfirmedRevision() = runBlocking {
        val first = store(); first.cacheItem(a); first.cacheItem(b)
        val stale = store(); stale.initialize()
        val revision = stale.snapshot.associations.revision
        first.associateWork(a, b, null, revision)
        assertTrue(runCatching { stale.associateWork(a, b, 9, revision) }.isFailure)
        val current = first.snapshot.associations.revision
        first.cacheItem(b.copy(metadataIds = mapOf("tmdb" to "33")))
        assertTrue(runCatching { first.associateWork(a, b, 9, current) }.isFailure)
        assertEquals(current, first.snapshot.associations.revision)
    }
    @Test fun everyPreviewedGroupMemberMustStillExistAndMatchItsReviewedEvidence() = runBlocking {
        val library = store()
        val c = b.copy(id = "c", title = "Source C", metadataIds = mapOf("tmdb" to "22"))
        library.cacheItem(a); library.cacheItem(b); library.cacheItem(c)
        library.associateWork(b, c, null, library.snapshot.associations.revision)
        val revision = library.snapshot.associations.revision
        val reviewed = listOf(b, c)
        // Omitting a member, adding an unrelated one, or duplicating a member fails closed.
        assertTrue(runCatching { library.associateWork(a, b, null, revision, listOf(b)) }.isFailure)
        assertTrue(runCatching { library.associateWork(a, b, null, revision, reviewed + a) }.isFailure)
        assertTrue(runCatching { library.associateWork(a, b, null, revision, reviewed + c) }.isFailure)
        // Catalog updates do not bump the manual overlay revision, but must invalidate evidence.
        library.cacheItem(c.copy(metadataIds = mapOf("tmdb" to "unreviewed-conflict")))
        assertEquals(revision, library.snapshot.associations.revision)
        assertTrue(runCatching { library.associateWork(a, b, null, revision, reviewed) }.isFailure)
        assertEquals(revision, library.snapshot.associations.revision)
        // Re-reviewing the complete latest group makes the same operation valid.
        val latest = library.snapshot.records.map { it.item }.filter { it.id == "b" || it.id == "c" }
        withContext(Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM source_mappings WHERE id = ?",
                arrayOf(MediaDomainMapper.entryId(c)))
        }
        assertTrue(runCatching { library.associateWork(a, b, null, revision, latest) }.isFailure)
        library.cacheItem(latest.single { it.id == "c" })
        library.associateWork(a, b, null, revision, latest)
        assertEquals(3, library.snapshot.associations.entries.size)
    }

    @Test fun thirdMemberExpiryInvalidatesFrozenReviewWithoutChangingManualRevision() = runBlocking {
        var clock = 100L
        val library = store { clock }
        val c = b.copy(id = "c", title = "Licensed C", catalogLicenseExpiresAt = 101)
        library.cacheItem(a); library.cacheItem(b); library.cacheItem(c)
        library.associateWork(b, c, null, library.snapshot.associations.revision)
        val revision = library.snapshot.associations.revision
        clock = 102
        assertTrue(runCatching { library.associateWork(a, b, null, revision, listOf(b, c)) }.isFailure)
        assertEquals(revision, library.snapshot.associations.revision)
        assertEquals(2, library.snapshot.associations.entries.size)
    }

    @Test fun unsupportedOrCorruptPayloadIsPreservedAndNeverDestructivelyMigrated() = runBlocking {
        val library = store(); library.cacheItem(a); library.cacheItem(b)
        val raw = "{\"version\":99,\"future\":\"preserve me\"}"
        withContext(Dispatchers.IO) { db.libraryDao().upsertMetadata(LibraryMetadataEntity(LocalWorkAssociationCodec.KEY, raw)) }
        val reopened = store(); reopened.initialize()
        assertFalse(reopened.snapshot.associations.writable)
        assertTrue(runCatching { reopened.associateWork(a, b, null, "initial") }.isFailure)
        assertEquals(raw, withContext(Dispatchers.IO) { db.libraryDao().metadata(LocalWorkAssociationCodec.KEY) })
        assertFalse(LocalWorkAssociationCodec.decode("{broken").writable)
        assertTrue(LocalWorkAssociationCodec.decode(null).writable)
    }
    @Test fun codecRejectsCoercedFractionalStringAndOutOfRangeNumbers() {
        val valid = LocalWorkAssociationCodec.encode(LocalWorkAssociations().associate(a, b, 0, 100))
        val malformed = listOf(
            valid.replace("\"version\":1", "\"version\":1.5"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"season\":0", "\"season\":0.5"),
            valid.replace("\"season\":0", "\"season\":\"0\""),
            valid.replace("\"season\":0", "\"season\":2147483648"),
            valid.replace("\"createdAt\":100", "\"createdAt\":100.5"),
            valid.replace("\"updatedAt\":100", "\"updatedAt\":\"100\""),
            valid.replace("\"updatedAt\":100", "\"updatedAt\":9223372036854775808")
        )
        malformed.forEach { payload ->
            assertNotEquals(valid, payload)
            assertFalse(payload, LocalWorkAssociationCodec.decode(payload).writable)
        }
    }

    @Test fun codecRoundTripRetainsProvenanceUnknownSeasonSpecialsAndUndo() {
        val value = LocalWorkAssociations().associate(a, b.copy(seasonNumber = 0), null, 100)
        assertEquals(value, LocalWorkAssociationCodec.decode(LocalWorkAssociationCodec.encode(value)))
        val unlinked = value.unlink(MediaDomainMapper.entryId(a))
        assertEquals(unlinked, LocalWorkAssociationCodec.decode(LocalWorkAssociationCodec.encode(unlinked)))
    }
}
