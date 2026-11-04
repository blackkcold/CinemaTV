package dev.cinematv

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MobilePlaybackSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[CinemaViewModel::class.java]
    private lateinit var server: UiFixtureServer

    @After fun cleanFixture() {
        if (::server.isInitialized) server.close()
        rule.activity.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
    }
    @Test fun playbackSurvivesPortraitLandscapeAndReturnsWithProgress() {
        server = UiFixtureServer(InstrumentationRegistry.getInstrumentation().context)
        rule.runOnIdle {
            model.serverAddress = server.base
            model.connect()
        }
        rule.waitUntil(20000) { model.authorized && model.featured.isNotEmpty() }
        rule.runOnIdle { model.open(model.featured.first()) }
        rule.waitUntil(15000) { !model.busy && model.selected?.episodes?.size == 36 }
        rule.onNodeWithText("播放 / 续播").performScrollTo().performClick()
        rule.waitUntil(20000) { model.page == Page.PLAYER && (model.selected?.durationMs ?: 0) > 0 }
        capture("mobile-player-portrait")
        rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(15000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val video = rule.onNodeWithContentDescription("视频画面").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(video.height <= root.height + 1 && video.width <= root.width + 1)
        capture("mobile-player-landscape")
        rule.onNodeWithText("← 返回").performClick()
        rule.runOnIdle {
            assertEquals(Page.DETAIL, model.page)
            assertTrue((model.selected?.positionMs ?: 0) > 0)
            assertTrue((model.selected?.durationMs ?: 0) >= 29000)
            rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    private fun capture(name: String) {
        val folder = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: rule.activity.getExternalFilesDir(null)!!.absolutePath, "ui-evidence").apply { mkdirs() }
        rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
