package dev.cinematv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phone detail controls and the real library/VM agree on resume versus restart. */
@RunWith(AndroidJUnit4::class)
class MobilePlaybackIntentTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[CinemaViewModel::class.java]
    private lateinit var server: UiFixtureServer

    @After fun cleanFixture() {
        if (::server.isInitialized) server.close()
        rule.activity.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
    }

    @Test fun completedFilmOffersReplayAndStartsAtZero() {
        openCheckpoint("completed", positionMs = 30_000L)
        rule.onNodeWithText("重新播放").assertExists()
        rule.onNodeWithTag("mobile-play-primary").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(Page.PLAYER, model.page); assertEquals(0L, model.playbackPosition) }
    }

    @Test fun incompleteFilmOffersExplicitRestartWithoutDeletingItsLibraryEntry() {
        openCheckpoint("restart", positionMs = 10_000L)
        rule.onNodeWithText("继续播放").assertExists()
        rule.onNodeWithTag("mobile-play-restart").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(Page.PLAYER, model.page)
            assertEquals(0L, model.playbackPosition)
            assertEquals("Playback restart fixture", model.selected?.title)
        }
    }

    @Test fun incompleteFilmResumesItsOwnCheckpoint() {
        openCheckpoint("resume", positionMs = 10_000L)
        rule.onNodeWithText("继续播放").assertExists()
        rule.onNodeWithTag("mobile-play-primary").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(Page.PLAYER, model.page); assertEquals(10_000L, model.playbackPosition) }
    }

    private fun openCheckpoint(id: String, positionMs: Long) {
        server = UiFixtureServer(InstrumentationRegistry.getInstrumentation().context)
        rule.runOnIdle { model.serverAddress = server.base; model.connect() }
        rule.waitUntil(15_000) { model.authorized }
        val item = MediaItem(id, "Playback $id fixture", source = TvSource("fixture", "Offline source", "${server.base}/provider"),
            episodes = listOf("${server.base}/stream.mp4"), episodeIds = listOf("fixture-movie"),
            episodeNames = listOf("正片"), playlistKnown = true)
        server.registerDetail(item)
        runBlocking {
            model.repo.library.initialize()
            model.repo.saveProgress(item, positionMs, 0, 30_000L)
        }
        rule.runOnIdle { model.open(item) }
        rule.waitUntil(15_000) { !model.busy && model.selected?.id == id && model.episodeProgress(0)?.positionMs == positionMs }
    }
}
