package dev.cinematv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
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

class MainActivity : ComponentActivity() {
    private val model: CinemaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        setContent {
            BackHandler(enabled = model.page != Page.HOME) { model.back() }
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
    }
}
