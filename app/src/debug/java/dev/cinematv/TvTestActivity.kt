package dev.cinematv

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
    // Optional configuration injection, without replacing the root or choosing
    // focus. The default preserves the original platform Density instance.
    var testFontScaleOverride by mutableStateOf<Float?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.cinematv.diagnostics.DiagnosticLog.install(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setContent {
            val platformDensity = LocalDensity.current
            val density = testFontScaleOverride?.let { Density(platformDensity.density, it) } ?: platformDensity
            CompositionLocalProvider(LocalDensity provides density) {
                CinemaNotoProvider {
                    val fonts = LocalCinemaNoto.current
                    SideEffect {
                        fontLoadFailures = fonts.failedAssets
                        bundledFontsReady = fonts.regional.size == NotoRegion.entries.size && fonts.failedAssets.isEmpty()
                    }
                    TvRootApp(model) { decodedScene = it }
                }
            }
        }
    }
}
