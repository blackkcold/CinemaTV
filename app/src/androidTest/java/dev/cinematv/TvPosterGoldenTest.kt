package dev.cinematv

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.ui.tv.design.VisualTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Poster-theme goldens.
 *
 * A separate capture class rather than a second assertion inside [TvUiRegressionTest], because
 * the two themes need independent baselines under `design/ui/goldens/poster/` and the Cinema
 * run must keep its existing provenance untouched.
 *
 * Scope is deliberately the same eight surfaces the Cinema run can currently reach: the shared
 * regression test is blocked at `TvUiRegressionTest.kt:196` by a pre-existing focus assertion
 * (reproduced on `9bd1a4b` before either branch), so `09`–`11` are not reachable for either
 * theme and are not fabricated here.
 */
@RunWith(AndroidJUnit4::class)
class TvPosterGoldenTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    private val model get() = rule.activity.model
    private val directory get() = File(
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: rule.activity.getExternalFilesDir(null)!!.absolutePath,
        "poster-evidence",
    ).apply { mkdirs() }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap = rule.activity.window.decorView.let { view ->
            val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            view.draw(canvas)
            bmp
        }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun posterThemeRendersHomeAndEveryReachableRail() {
        val server = UiFixtureServer(InstrumentationRegistry.getInstrumentation().context).apply { homeCatalog = true }
        try {
            rule.waitUntil(15000) { rule.activity.bundledFontsReady || rule.activity.fontLoadFailures.isNotEmpty() }
            assertTrue("Bundled Noto failed: ${rule.activity.fontLoadFailures}", rule.activity.bundledFontsReady)
            rule.waitUntil(10000) { rule.activity.hasWindowFocus() }
            rule.runOnIdle {
                model.updateReducedMotion(true)
                model.updateVisualTheme(VisualTheme.POSTER)
                model.repo.configureMetadata(server.base, "offline-fixture-token")
                model.serverAddress = server.base
                model.connect()
                model.navigate(dev.cinematv.Page.HOME)
            }
            rule.waitUntil(20000) { model.authorized && model.featured.size == 24 && model.tvShows.size == 24 }
            rule.waitForIdle()
            android.util.Log.i("CinemaTVPosterQa", "theme=${model.visualTheme} route=${model.page}")
            screenshot("01-home")

            rule.runOnIdle { model.navigate(dev.cinematv.Page.MOVIES) }
            rule.waitForIdle()
            screenshot("05-movies")

            rule.runOnIdle { model.navigate(dev.cinematv.Page.SERIES) }
            rule.waitForIdle()
            screenshot("06-series")
        } finally {
            server.close()
            rule.activity.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
        }
    }
}
