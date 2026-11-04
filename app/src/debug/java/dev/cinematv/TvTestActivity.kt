package dev.cinematv

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.cinematv.ui.tv.TvRootApp

/** Non-exported debug-only host. Fixtures live exclusively in androidTest. */
class TvTestActivity : ComponentActivity() {
    val model: CinemaViewModel by viewModels()
    var decodedScene: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContent {
            BackHandler(enabled = model.page != Page.HOME) { model.back() }
            TvRootApp(model) { decodedScene = it }
        }
    }
}
