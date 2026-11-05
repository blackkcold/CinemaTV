package dev.cinematv

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import dev.cinematv.ui.mobile.MobileRootApp
import dev.cinematv.ui.tv.TvRootApp
import dev.cinematv.ui.tv.components.GlassDrawDiagnostics
import dev.cinematv.ui.tv.components.LocalGlassDrawDiagnostics
import dev.cinematv.ui.tv.components.LocalGlassDrawScope
import dev.cinematv.ui.tv.components.LocalProductionGlassObserver
import dev.cinematv.ui.tv.components.LocalProductionSceneDecoded
import dev.cinematv.ui.tv.components.ProductionGlassObservation
import dev.cinematv.ui.typography.CinemaNotoProvider
import dev.cinematv.ui.typography.LocalCinemaNoto
import dev.cinematv.ui.typography.NotoRegion

/**
 * Actual production roots in an offline, non-exported debug host, not a material lab.
 * Only window chrome and passive observers differ from MainActivity. No renderer,
 * source, policy, clock, focus, or device capability is replaced here.
 */
class ProductionGlassTestActivity : ComponentActivity() {
    val model: CinemaViewModel by viewModels()
    internal val glassDraws = GlassDrawDiagnostics()
    @Volatile internal var glassObservation: ProductionGlassObservation? = null
    @Volatile var decodedScene: String? = null
    @Volatile var bundledFontsReady = false
    @Volatile var fontLoadFailures: Set<String> = emptySet()
    @Volatile var isTv = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setContent {
            CinemaNotoProvider {
                val fonts = LocalCinemaNoto.current
                val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                SideEffect {
                    isTv = landscape
                    fontLoadFailures = fonts.failedAssets
                    bundledFontsReady = fonts.regional.size == NotoRegion.entries.size && fonts.failedAssets.isEmpty()
                }
                CompositionLocalProvider(
                    LocalGlassDrawDiagnostics provides glassDraws,
                    LocalGlassDrawScope provides model.page.name,
                    LocalProductionGlassObserver provides { glassObservation = it },
                    LocalProductionSceneDecoded provides { decodedScene = it },
                ) {
                    BackHandler(enabled = model.page != Page.HOME) { model.back() }
                    if (landscape) TvRootApp(model) else MobileRootApp(model)
                }
            }
        }
    }
}
