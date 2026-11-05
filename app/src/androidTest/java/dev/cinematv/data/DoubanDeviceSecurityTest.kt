package dev.cinematv.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.data.douban.DoubanEntry
import dev.cinematv.data.douban.DoubanShelf
import dev.cinematv.data.douban.DoubanVault
import dev.cinematv.data.library.CinemaLibraryDatabase
import dev.cinematv.data.library.LegacyLibraryImport
import dev.cinematv.data.library.LocalMediaLibrary
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic isolated storage only. Never touches the production session filename/key. */
@RunWith(AndroidJUnit4::class)
class DoubanDeviceSecurityTest {
    @Test fun keystoreRoundTripTamperAndDelete() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = DoubanVault(context, "douban-fixture")
        try {
            vault.clear(); vault.write(JSONObject().put("cookie", "SYNTHETIC_ONLY_DO_NOT_USE"))
            val file = File(context.noBackupFilesDir, "douban-fixture.v1")
            assertFalse(String(file.readBytes()).contains("SYNTHETIC_ONLY_DO_NOT_USE"))
            assertEquals("SYNTHETIC_ONLY_DO_NOT_USE", vault.read()!!.getString("cookie"))
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertThrows(Exception::class.java) { vault.read() }
        } finally { vault.clear() }
        assertNull(vault.read())
    }
    @Test fun importPreservesConflictsFavoritesAndEpisodeProgress() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CinemaLibraryDatabase::class.java).build()
        val prefs = context.getSharedPreferences("douban-import-fixture", Context.MODE_PRIVATE)
        val library = LocalMediaLibrary({ db }, { LegacyLibraryImport.read(prefs) })
        try {
            library.initialize()
            val item = MediaItem("source-id", "作品A", metadataIds = mapOf("douban" to "123"), source = TvSource("fixture", "fixture", "https://example.test"), episodes = listOf("https://example.test/a"), episodeIds = listOf("ep-1"))
            library.setStatus(item, LibraryStatus.WATCHING); library.toggleFavorite(item); library.saveProgress(item, 400, 0, 1000)
            val result = library.importDouban(listOf(DoubanEntry("123", "作品A", DoubanShelf.WATCHED), DoubanEntry("456", "作品A", DoubanShelf.WANT)))
            assertEquals(1, result.first); assertEquals(1, result.second)
            assertEquals(LibraryStatus.WATCHING, library.snapshot.status(item)); assertTrue(library.snapshot.isFavorite(item))
            assertEquals(400L, library.snapshot.progress(item, 0)!!.positionMs)
            assertEquals(2, library.snapshot.records.size)
            assertEquals(0, library.importDouban(listOf(DoubanEntry("456", "作品A", DoubanShelf.WANT))).first)
        } finally { db.close(); prefs.edit().clear().commit() }
    }
}
