package dev.cinematv

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import dev.cinematv.ui.typography.CinemaNotoProvider
import dev.cinematv.ui.typography.LocalCinemaNoto
import dev.cinematv.ui.typography.NotoRegion
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import dev.cinematv.ui.tv.TvRootApp

/** Non-exported debug-only host. Fixtures live exclusively in androidTest. */
class TvTestActivity : ComponentActivity() {
    val model: CinemaViewModel by viewModels()
    @Volatile var decodedScene: String? = null
    @Volatile var bundledFontsReady = false
    @Volatile var fontLoadFailures: Set<String> = emptySet()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setContent {
            CinemaNotoProvider {
                val fonts = LocalCinemaNoto.current
                SideEffect {
                    fontLoadFailures = fonts.failedAssets
                    bundledFontsReady = fonts.regional.size == NotoRegion.entries.size && fonts.failedAssets.isEmpty()
                }
                BackHandler(enabled = model.page != Page.HOME) { model.back() }
                TvRootApp(model) { decodedScene = it }
            }
        }
    }
}
