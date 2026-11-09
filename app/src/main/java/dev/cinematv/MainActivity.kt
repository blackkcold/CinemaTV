package dev.cinematv

import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.*
import dev.cinematv.ui.typography.CinemaNotoProvider
import dev.cinematv.ui.mobile.MobileRootApp
import dev.cinematv.ui.shared.CinemaBrandSession
import dev.cinematv.perf.NativeGlassProbe
import dev.cinematv.ui.tv.components.LocalGlassDrawDiagnostics
import dev.cinematv.ui.tv.components.LocalGlassDrawScope
import dev.cinematv.ui.tv.components.LocalProductionGlassObserver
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.TvRootApp

class MainActivity : ComponentActivity() {
    private val model: CinemaViewModel by viewModels()
    private var networkManager: ConnectivityManager? = null
    private var networkListener: ConnectivityManager.NetworkCallback? = null
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.cinematv.diagnostics.DiagnosticLog.install(this)
        lifecycleScope.launch { dev.cinematv.data.douban.DoubanDeveloperOptions.get(this@MainActivity).initialize() }
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        networkManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (!firstResume && model.repo.isConnected) model.refreshConnection()
                }
            }
        }
        runCatching { networkManager?.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkListener = callback }
        // Full immersive mode is TV-only; phones retain their status/navigation bars.
        if (isTv) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
        setContent {
            val glassProbe = NativeGlassProbe.enabled
            CinemaNotoProvider {
                CompositionLocalProvider(
                    LocalOpticalGlass provides model.opticalGlassEnabled,
                    LocalProductionGlassObserver provides
                        if (glassProbe) ({ NativeGlassProbe.onObservation(it) }) else null,
                    LocalGlassDrawDiagnostics provides if (glassProbe) NativeGlassProbe.diagnostics else null,
                    LocalGlassDrawScope provides if (glassProbe) "root" else null,
                ) {
                    // TV hybrid/native detours and playback own their Back order in one host.
                    BackHandler(enabled = !isTv && model.page != Page.HOME) { model.back() }
                    CinemaBrandSession(
                        reducedMotion = model.reducedMotion,
                        lowCost = model.glassQuality == GlassQuality.BASIC,
                    ) {
                        // TV has one native route owner; the phone UI stays independent.
                        if (isTv) TvRootApp(model) else MobileRootApp(model)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!firstResume && model.repo.isConnected) model.refreshConnection()
        firstResume = false
    }

    override fun onStop() {
        dev.cinematv.diagnostics.DiagnosticLog.flush()
        super.onStop()
    }

    override fun onDestroy() {
        networkListener?.let { listener ->
            runCatching { networkManager?.unregisterNetworkCallback(listener) }
        }
        networkListener = null
        super.onDestroy()
    }
}

