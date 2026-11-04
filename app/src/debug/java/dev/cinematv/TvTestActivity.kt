package dev.cinematv

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import dev.cinematv.ui.tv.TvRootApp

/** Non-exported debug-only host. Fixtures live exclusively in androidTest. */
class TvTestActivity : ComponentActivity() {
    val model: CinemaViewModel by viewModels()
    @Volatile var decodedScene: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setContent {
            BackHandler(enabled = model.page != Page.HOME) { model.back() }
            TvRootApp(model) { decodedScene = it }
        }
    }
}
