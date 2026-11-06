package dev.cinematv.hybrid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.cinematv.Page
import org.json.JSONObject

data class HybridCatalogFilter(val category: String = "", val year: String = "", val rated: Boolean = false)

class HybridRouteState {
    var page = 0
    var episodePage = 0
    var libraryFilter = "ALL"
    var catalogFilter = HybridCatalogFilter()
    var bookmark: JSONObject? = null
}

/** Logical presentation state survives player/WebView disposal and activity recreation, without storing URLs in JS. */
class HybridPresentationState : ViewModel() {
    val ids = HybridOpaqueIds()
    private val routes = LinkedHashMap<String, HybridRouteState>(32, .75f, true)
    var change by mutableIntStateOf(0)
        private set
    var failure by mutableStateOf<String?>(null)
    var nativeFallback by mutableStateOf(false)
    var nativePanel by mutableStateOf<Page?>(null)
    var nativeReturnPage: Page = Page.SETTINGS
    var nativeSettingsSection = 0
    var searchInput by mutableStateOf(false)
    var clearHistoryConfirmation by mutableStateOf(false)

    fun route(key: String): HybridRouteState = routes.getOrPut(key) { HybridRouteState() }.also {
        while (routes.size > 32) routes.remove(routes.keys.first())
    }

    fun changed() { change++ }
    fun fail(message: String) { failure = message; nativePanel = null; searchInput = false; clearHistoryConfirmation = false }
    fun retry() { failure = null; nativeFallback = false; changed() }
}
