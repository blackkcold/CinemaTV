package dev.cinematv

import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.View
import androidx.activity.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import dev.cinematv.ui.mobile.MobileRootApp

class MainActivity : ComponentActivity() {
    private val model: CinemaViewModel by viewModels()
    private var networkManager: ConnectivityManager? = null
    private var networkListener: ConnectivityManager.NetworkCallback? = null
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isTv = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
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
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
        setContent {
            CompositionLocalProvider(LocalOpticalGlass provides model.opticalGlassEnabled) {
                BackHandler(enabled = model.page != Page.HOME) { model.back() }
                if (isTv) TvRootApp(model) else MobileRootApp(model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!firstResume && model.repo.isConnected) model.refreshConnection()
        firstResume = false
    }

    override fun onDestroy() {
        networkListener?.let { listener ->
            runCatching { networkManager?.unregisterNetworkCallback(listener) }
        }
        networkListener = null
        super.onDestroy()
    }
}

@Composable
private fun TvRootApp(model: CinemaViewModel) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        when (model.page) {
            Page.HOME -> HomePage(model)
            Page.EXPLORE -> ExplorePage(model)
            Page.MOVIES -> CatalogPage(model, false)
            Page.SERIES -> CatalogPage(model, true)
            Page.SEARCH -> SearchPage(model)
            Page.DETAIL -> DetailPage(model)
            Page.PLAYER -> TvPlayerPage(model)
            Page.LIVE -> LivePage(model)
            Page.LIBRARY -> LibraryPage(model)
            Page.SETTINGS -> SettingsPage(model)
        }
        if (model.page !in listOf(Page.DETAIL, Page.PLAYER, Page.SETTINGS)) {
            TopNavigation(model, Modifier.align(Alignment.TopCenter).padding(top = 25.dp))
        }
        if (model.message.isNotBlank() && model.page != Page.PLAYER) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                    .background(Color(0xF02B2529), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .padding(horizontal = 24.dp, vertical = 13.dp)
            ) {
                Text(model.message, color = Ivory, fontSize = 14.sp)
            }
            LaunchedEffect(model.message) {
                kotlinx.coroutines.delay(5500)
                model.dismissMessage()
            }
        }
    }
}
