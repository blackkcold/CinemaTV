package dev.cinematv

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.data.LibreTvRepository
import dev.cinematv.data.SourceSubscription
import dev.cinematv.data.library.CinemaLibraryDatabase
import dev.cinematv.data.library.LegacyLibraryImport
import dev.cinematv.data.library.LocalMediaLibrary
import dev.cinematv.ui.shared.SourceSubscriptionsScreen
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.design.LocalTvEffects
import dev.cinematv.ui.tv.design.TvEffects
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production native subscription UI with synthetic loopback only, isolated preferences and Room. */
@RunWith(AndroidJUnit4::class)
class SourceSubscriptionsUiTest {
    @get:Rule val rule = createComposeRule()
    private val store = ViewModelStore()
    private val prefName = "subscription-ui-${UUID.randomUUID()}"
    private lateinit var prefs: SharedPreferences
    private lateinit var db: CinemaLibraryDatabase
    private lateinit var repo: LibreTvRepository
    private lateinit var model: CinemaViewModel
    private lateinit var fixture: SubscriptionServer
    private lateinit var nativeView: View
    private val subscription = SourceSubscription("https://lists.example.test/synthetic.json", "Synthetic list")

    private fun payload(count: Int, suffix: String = "", live: Boolean = false) = JSONObject()
        .put("name", "Synthetic list")
        .put("sources", JSONArray((0 until count).map { index ->
            JSONObject().put("name", "Fixture source $index$suffix").put("url", "https://vod.example.test/$index")
        }))
        .put("liveSources", JSONArray(if (live) listOf(JSONObject().put("name", "Fixture live")
            .put("url", "https://live.example.test/list.m3u")) else emptyList<JSONObject>()))
        .put("stats", JSONObject().put("format", "tvbox").put("skipped", 1)
            .put("skippedByReason", JSONObject().put("spider", 1)).put("truncated", 0)).toString()

    private fun mount(tv: Boolean, count: Int, live: Boolean = false) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        prefs = application.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty())
        db = Room.inMemoryDatabaseBuilder(application, CinemaLibraryDatabase::class.java).build()
        fixture = SubscriptionServer(subscription, payload(count, live = live))
        val context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        repo = LibreTvRepository(context, LocalMediaLibrary(databaseProvider = { db },
            legacyProvider = { LegacyLibraryImport(emptyList(), emptyList(), emptyList()) }))
        repo.configureServer(fixture.base)
        rule.runOnUiThread {
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CinemaViewModel(application, repo) as T
            })[CinemaViewModel::class.java]
        }
        rule.setContent {
            val inputMode = LocalInputModeManager.current
            val view = LocalView.current
            SideEffect { nativeView = view }
            LaunchedEffect(tv) { if (tv) inputMode.requestInputMode(InputMode.Keyboard) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalTvEffects provides TvEffects(reducedMotion = true, glass = GlassQuality.BASIC)) {
                    SourceSubscriptionsScreen(model, tv, Modifier.fillMaxSize())
                }
            }
        }
        rule.waitUntil(8_000) { model.authorized && model.defaultSourceSubscriptions.size == 1 }
        rule.waitForIdle()
        assertTrue("Only advertised SourceList is present before import", model.sources.isEmpty() && model.liveSources.isEmpty())
    }

    @After fun cleanUp() {
        rule.runOnUiThread { store.clear() }
        if (::fixture.isInitialized) fixture.close()
        if (::db.isInitialized) db.close()
        if (::prefs.isInitialized) assertTrue(prefs.edit().clear().commit())
        ApplicationProvider.getApplicationContext<Context>().deleteSharedPreferences(prefName)
    }

    private fun visible(tag: String): SemanticsNodeInteraction {
        rule.onNodeWithTag("source-subscriptions-screen").performScrollToNode(hasTestTag(tag))
        return rule.onNodeWithTag(tag)
    }

    private fun remoteClick(tag: String) {
        visible(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.waitUntil(5_000) { nativeView.hasWindowFocus() }
        rule.onNodeWithTag(tag).assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitForIdle()
    }

    @Test fun phoneCanPreviewImportRefreshAndStopWithoutLosingDisabledSources() {
        mount(tv = false, count = 1, live = true)
        visible("subscription-preview-${subscription.id}").performClick()
        rule.waitUntil(5_000) { model.sourceSubscriptionPreview != null && !model.sourceSubscriptionBusy }
        visible("subscription-preview-stats").assertTextContains("Spider", substring = true)
        visible("subscription-apply").performClick()
        rule.waitUntil(5_000) { model.sourceSubscriptions.size == 1 && !model.sourceSubscriptionBusy }
        rule.runOnIdle {
            assertEquals(1, model.sources.size)
            assertEquals(1, model.liveSources.size)
            model.toggleSource(model.sources.first())
        }
        fixture.response = 200 to payload(2, suffix = " updated")
        visible("subscription-refresh-${subscription.id}").performClick()
        rule.waitUntil(5_000) { model.sourceSubscriptionPreview != null && !model.sourceSubscriptionBusy }
        rule.runOnIdle {
            assertEquals(1, model.sourceSubscriptionPreview?.changes?.vod?.added)
            assertEquals(1, model.sourceSubscriptionPreview?.changes?.vod?.changed)
            assertEquals(1, model.sourceSubscriptionPreview?.changes?.live?.removed)
        }
        visible("subscription-apply").performClick()
        rule.waitUntil(5_000) { model.sources.size == 2 && !model.sourceSubscriptionBusy }
        rule.runOnIdle {
            assertFalse(model.sources.first { it.url.endsWith("/0") }.enabled)
            assertTrue(model.liveSources.isEmpty())
        }
        visible("subscription-stop-${subscription.id}").performClick()
        rule.waitUntil(5_000) { model.sourceSubscriptions.isEmpty() && !model.sourceSubscriptionBusy }
        rule.runOnIdle {
            assertEquals(2, model.sources.size)
            assertFalse(model.sources.first { it.url.endsWith("/0") }.enabled)
            assertTrue(model.sourceSubscriptionNotice.contains("保留"))
        }
    }

    @Test fun nativeTvRemoteCanReadLongPreviewAndFailedRefreshKeepsImportedSources() {
        mount(tv = true, count = 14)
        remoteClick("subscription-preview-${subscription.id}")
        rule.waitUntil(5_000) { model.sourceSubscriptionPreview != null && !model.sourceSubscriptionBusy }
        rule.waitForIdle()
        rule.onNodeWithTag("subscription-apply").assertIsFocused()
        val sources = rule.runOnIdle { model.sourceSubscriptionPreview!!.snapshot.sources }
        val first = "subscription-preview-vod-${sources.first().key}"
        val last = "subscription-preview-vod-${sources.last().key}"
        visible(first).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag(first).assertIsFocused()
        repeat(sources.size - 1) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            rule.waitForIdle()
        }
        rule.onNodeWithTag(last).assertIsFocused().assertIsDisplayed()
        remoteClick("subscription-apply")
        rule.waitUntil(5_000) { model.sourceSubscriptions.size == 1 && !model.sourceSubscriptionBusy }
        val saved = rule.runOnIdle { model.sourceSubscriptions.single() }
        fixture.response = 502 to "{\"error\":\"synthetic unavailable\"}"
        remoteClick("subscription-refresh-${subscription.id}")
        rule.waitUntil(5_000) { !model.sourceSubscriptionBusy && model.sourceSubscriptionNotice.contains("已保留现有") }
        rule.runOnIdle {
            assertNull(model.sourceSubscriptionPreview)
            assertEquals(saved, model.sourceSubscriptions.single())
            assertEquals(14, model.sources.size)
        }
    }

    private class SubscriptionServer(private val subscription: SourceSubscription, body: String) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val clients = CopyOnWriteArrayList<Socket>()
        @Volatile private var running = true
        @Volatile private var failure: Throwable? = null
        @Volatile var response = 200 to body
        val base = "http://127.0.0.1:${listener.localPort}"
        private val worker = Thread({
            while (running) try { listener.accept().use { clients += it; handle(it) } }
            catch (error: Exception) { if (running) failure = error }
        }, "subscription-ui-loopback").apply { isDaemon = true; start() }

        private fun handle(client: Socket) {
            client.soTimeout = 5_000
            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
            val path = reader.readLine().split(' ')[1]
            while (!reader.readLine().isNullOrEmpty()) Unit
            val parsed = Uri.parse(path)
            val reply = when (parsed.path) {
                "/api/status" -> 200 to JSONObject().put("passwordRequired", true).put("verified", true)
                    .put("defaultSources", JSONArray()).put("defaultLiveSources", JSONArray())
                    .put("defaultSubscriptions", JSONArray().put(JSONObject().put("url", subscription.url)
                        .put("name", subscription.name).put("refreshDays", subscription.refreshDays))).toString()
                "/api/source-list" -> { check(parsed.getQueryParameter("url") == subscription.url); response }
                else -> error("Unexpected fixture endpoint")
            }
            val bytes = reply.second.toByteArray(Charsets.UTF_8)
            client.getOutputStream().apply {
                write(("HTTP/1.1 ${reply.first} Fixture\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(bytes); flush()
            }
        }

        override fun close() {
            running = false
            listener.close()
            clients.forEach { it.close() }
            worker.join(6_000)
            assertFalse("Subscription fixture did not stop", worker.isAlive)
            assertNull("Subscription fixture failed", failure)
        }
    }
}
