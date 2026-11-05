package dev.cinematv

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceView
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.data.LibreTvRepository
import dev.cinematv.data.library.CinemaLibraryDatabase
import dev.cinematv.data.library.LegacyLibraryImport
import dev.cinematv.data.library.LocalMediaLibrary
import dev.cinematv.playback.PlaybackFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Direct adapter + production Media3 factory acceptance, NOT app navigation or full-film QA. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OfficialLibreTvPlaybackAcceptanceTest {
    @Test fun officialAuthenticationSearchDetailDecodePauseResumeAndCheckpoint() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val credential = File(context.filesDir, "official-acceptance.json")
        check(credential.isFile && credential.length() in 1..4096) { "Isolated official lifecycle wrapper required" }
        val config = try { JSONObject(credential.readText()) } finally { credential.delete() }
        val server = config.getString("server")
        check(Regex("http://10\\.0\\.2\\.2:[0-9]{1,5}").matches(server)) { "Only owned emulator host alias allowed" }
        check(java.net.URI(server).port in 1024..65535)
        check(config.getString("fixture") == FIXTURE)
        val password = config.getString("password")
        check(Regex("[A-Za-z0-9_-]{43}").matches(password))
        val prefs = "official-acceptance-isolated"
        context.deleteSharedPreferences(prefs)
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(prefs, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, CinemaLibraryDatabase::class.java).build()
        fun library() = LocalMediaLibrary({ db }, { LegacyLibraryImport(emptyList(), emptyList(), emptyList()) })
        val repo = LibreTvRepository(isolated, library())
        var player: ExoPlayer? = null
        var scenario: ActivityScenario<NotoTestActivity>? = null
        fun <T> main(block: () -> T): T {
            var value: T? = null
            instrumentation.runOnMainSync { value = block() }
            @Suppress("UNCHECKED_CAST") return value as T
        }
        fun await(label: String, timeout: Long = 60_000, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                if (main {
                    check(player?.playerError == null) { "Media3 playback error during $label" }
                    player?.videoDecoderCounters?.ensureUpdated()
                    predicate()
                }) return
                SystemClock.sleep(100)
            }
            throw AssertionError("Timed out: $label")
        }
        try {
            val detailed = withTimeout(180_000) {
                repo.library.initialize()
                repo.configureServer(server)
                val status = repo.status()
                assertEquals("2.21.1", status.version)
                assertFalse(status.verified)
                assertTrue(status.passwordRequired)
                assertTrue(repo.sources().isEmpty())
                assertTrue(repo.login(password))
                assertTrue(repo.status().verified)
                repo.addSource("Controlled static Blender fixture (not dynamic API)", FIXTURE)
                val snapshot = repo.searchSnapshot("Big Buck Bunny")
                assertTrue(snapshot.complete)
                assertTrue(snapshot.outcomes.isEmpty())
                assertEquals(1, snapshot.items.size)
                val found = snapshot.items.single()
                assertEquals(UUID, found.id)
                assertEquals(FIXTURE, found.source?.url)
                assertTrue(found.title.startsWith("Big Buck Bunny"))
                val streamed = repo.searchSnapshots(found.title).toList()
                assertTrue(streamed.isNotEmpty())
                assertTrue(streamed.last().complete)
                assertTrue(streamed.last().outcomes.all { it.ok && !it.timedOut })
                assertEquals(listOf(UUID), streamed.last().items.map { it.id })
                repo.detail(found).also {
                    assertEquals(listOf(MEDIA), it.episodes)
                    assertTrue(it.description.contains("CC BY 3.0"))
                    assertTrue(it.playlistKnown)
                }
            }
            // Neutral debug activity has no app ViewModel, recommendations, saved backend or source discovery.
            scenario = ActivityScenario.launch(NotoTestActivity::class.java)
            scenario.onActivity { activity ->
                val surface = SurfaceView(activity)
                activity.setContentView(surface)
                player = PlaybackFactory.create(activity, cacheVod = false).apply {
                    setVideoSurfaceView(surface)
                    setMediaItem(PlayerItem.fromUri(detailed.episodes.single()))
                    prepare()
                    play()
                }
            }
            await("decoded rendered frames and advancing playback") {
                val p = requireNotNull(player)
                val counters = p.videoDecoderCounters
                counters?.ensureUpdated()
                p.isPlaying && p.currentPosition >= 2500 &&
                    (counters?.decoderInitCount ?: 0) > 0 && (counters?.renderedOutputBufferCount ?: 0) >= 8
            }
            val initialFrames = main { player!!.videoDecoderCounters!!.renderedOutputBufferCount }
            val initialPosition = main { player!!.currentPosition }
            await("continued frame rendering and clock advancement", 30_000) {
                player!!.currentPosition >= initialPosition + 1500 &&
                    player!!.videoDecoderCounters!!.renderedOutputBufferCount >= initialFrames + 8
            }
            main { player!!.pause() }
            await("pause", 5000) { !player!!.isPlaying }
            val paused = main { player!!.currentPosition }
            SystemClock.sleep(750)
            assertTrue("Paused clock must remain stable", abs(main { player!!.currentPosition } - paused) < 150)
            val duration = main { player!!.duration }
            assertTrue(duration > paused)
            withTimeout(10_000) { repo.saveProgress(detailed, paused, 0, duration) }
            val reloadedLibrary = library()
            withTimeout(10_000) { reloadedLibrary.initialize() }
            val restored = reloadedLibrary.snapshot.applyProgress(detailed)
            assertEquals(paused, restored.positionMs)
            assertEquals(0, restored.lastEpisode)
            main {
                player!!.seekTo(restored.positionMs)
                player!!.play()
            }
            val pausedFrames = main { player!!.videoDecoderCounters!!.renderedOutputBufferCount }
            await("resume from checkpoint with new decoded frames", 30_000) {
                player!!.isPlaying && player!!.currentPosition >= paused + 1500 &&
                    player!!.videoDecoderCounters!!.renderedOutputBufferCount >= pausedFrames + 8
            }
            instrumentation.addResults(android.os.Bundle().apply {
                putInt("officialRenderedFrames", main { player!!.videoDecoderCounters!!.renderedOutputBufferCount })
                putLong("officialCheckpointMs", paused)
                putLong("officialResumedPositionMs", main { player!!.currentPosition })
            })
            // Numbers only: no authentication material, response dumps, or personal state.
            android.util.Log.i("CinemaTVQa", "official-libretv acceptance passed: renderedFrames=" +
                main { player!!.videoDecoderCounters!!.renderedOutputBufferCount } + ", checkpointMs=$paused")
        } finally {
            main { player?.release(); player = null }
            scenario?.close()
            db.close()
            context.deleteSharedPreferences(prefs)
            credential.delete()
        }
        Unit
    }

    private companion object {
        const val UUID = "6402b77c-b61f-4a06-96ca-c8420a2becf4"
        const val MEDIA = "https://video.blender.org/object-storage/web_videos/$UUID-240.mp4"
        const val FIXTURE = "https://raw.githubusercontent.com/blackkcold/CinemaTV/74d104aa2b4e212163376bcbb1da04bef6670918/scripts/libretv-contracts/fixtures/blender-bbb-static-cms.json"
    }
}
