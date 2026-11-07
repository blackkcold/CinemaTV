package dev.cinematv

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import dev.cinematv.hybrid.HybridPolicy
import dev.cinematv.ui.tv.design.GlassQuality
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Visual states of the installed production Web assets on an actual Android TV.
 * Test-side fixture setup is separate from HybridHostUiTest's functional navigation.
 * The CI harness clears only its disposable guest before this independent suite.
 */
@RunWith(AndroidJUnit4::class)
class HybridVisualAcceptanceTest {
    // Future fixtures survive the production gateway's real-time expiry filter.
    // Only this visual suite freezes the Web wall clock; functional EPG tests do not.
    private val visualEpochMs = 4070944800000L // 2099-01-01T10:00:00Z
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var model: CinemaViewModel
    private lateinit var server: UiFixtureServer
    private var completedCaptures = false
    private val output get() = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "visual-evidence").apply { mkdirs() }

    @Before fun launchFreshProductionHost() {
        assertTrue(Build.VERSION.SDK_INT >= 29)
        assertEquals(Configuration.UI_MODE_TYPE_TELEVISION,
            instrumentation.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        server = UiFixtureServer(instrumentation.context).apply {
            homeCatalog = true
            programEpochMs = visualEpochMs
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            activity = it
            model = ViewModelProvider(it)[CinemaViewModel::class.java]
            model.updateReducedMotion(true)
            model.updateGlassQuality(GlassQuality.AUTO)
            model.repo.configureMetadata(server.base, "hybrid-offline-fixture-token")
            model.serverAddress = server.base
            model.connect()
            model.navigate(Page.HOME)
        }
        rule.waitUntil(25_000) { model.authorized && model.featured.size == 24 && model.tvShows.size == 24 }
        awaitRoute(Page.HOME)
        assertEquals(HybridPolicy.ENTRY_URL, evaluate("location.href"))
        assertEquals(visualEpochMs.toString(), evaluate("Date.now = () => $visualEpochMs; Date.now()"))
    }

    @After fun close() {
        try {
            if (::activity.isInitialized) {
                val finalState = JSONObject().put("route", model.page.name)
                    .put("webViewPresent", web() != null).put("syntheticContent", true)
                    .put("completedCaptures", completedCaptures)
                runCatching {
                    finalState.put("dom", JSONObject(evaluate("JSON.stringify({focus:document.activeElement?.dataset.focus||'',playDisabled:document.querySelector('[data-focus=\"detail:play\"]')?.disabled??null,actions:Array.from(document.querySelectorAll('.actions [data-focus]')).map(n=>({id:n.dataset.focus,disabled:n.disabled,text:n.textContent?.slice(0,120)}))})")))
                    if (!completedCaptures) {
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            try { File(output, "failed-state.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            finally { bitmap.recycle() }
                        }
                    }
                }.onFailure { finalState.put("diagnosticError", it.javaClass.simpleName) }
                File(output, "final-state.json").writeText(finalState.toString(2))
            }
        } finally {
            if (::scenario.isInitialized) scenario.close()
            if (::server.isInitialized) server.close()
        }
    }

    private fun web(): WebView? {
        var result: WebView? = null
        instrumentation.runOnMainSync { result = HybridDom.find(activity.window.decorView) }
        return result
    }
    private fun evaluate(expression: String) = HybridDom.evaluate(activity, expression)
    private fun awaitRoute(page: Page) {
        rule.waitUntil(20_000) { model.page == page && web() != null && runCatching {
            evaluate("Boolean(document.querySelector('.route-${page.name.lowercase()}'))") == "true"
        }.getOrDefault(false) }
    }
    private fun focus(id: String) {
        assertEquals("true", evaluate("(() => {const n=Array.from(document.querySelectorAll('[data-focus]')).find(x=>x.dataset.focus===${JSONObject.quote(id)});if(!n||n.disabled)return false;n.focus({preventScroll:true});n.scrollIntoView({block:'nearest',inline:'nearest'});return document.activeElement===n;})()"))
    }
    private fun key(code: Int) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0))
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        instrumentation.waitForIdleSync()
    }
    private fun activate(id: String) {
        focus(id)
        rule.waitUntil(10_000) {
            var ready = false
            instrumentation.runOnMainSync { ready = activity.hasWindowFocus() && HybridDom.find(activity.window.decorView)?.hasWindowFocus() == true }
            ready && evaluate("document.hasFocus() && document.activeElement?.dataset.focus===${JSONObject.quote(id)}") == "true"
        }
        committedFrame()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun committedFrame() {
        val current = requireNotNull(web()) { "Visual acceptance must not use native fallback" }
        val latch = CountDownLatch(1)
        val active = AtomicBoolean(true)
        var observer: android.view.ViewTreeObserver? = null
        val callback = Runnable { if (active.get()) latch.countDown() }
        instrumentation.runOnMainSync {
            current.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (!active.get()) return
                    check(HybridDom.find(activity.window.decorView) === current)
                    check(current.isHardwareAccelerated && current.isAttachedToWindow)
                    observer = current.viewTreeObserver
                    observer!!.registerFrameCommitCallback(callback)
                    current.postInvalidateOnAnimation()
                }
            })
        }
        try { assertTrue("Production compositor frame not committed", latch.await(8, TimeUnit.SECONDS)) }
        finally {
            active.set(false)
            instrumentation.runOnMainSync { observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback) }
        }
    }

    private fun capture(label: String) {
        rule.waitUntil(15_000) {
            evaluate("(() => {const shown=i=>{const r=i.getBoundingClientRect();return r.width>1&&r.height>1&&r.right>0&&r.bottom>0&&r.left<innerWidth&&r.top<innerHeight;};return document.fonts.status==='loaded'&&Array.from(document.images).filter(shown).every(i=>i.complete)&&!document.querySelector('.scene-probe');})()") == "true"
        }
        rule.waitForIdle()
        committedFrame()
        val dom = JSONObject(evaluate("JSON.stringify({width:innerWidth,height:innerHeight,dpr:devicePixelRatio,locale:navigator.language,timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone,visualEpochMs:Date.now(),quality:document.querySelector('.tv-app').dataset.quality,reducedMotion:document.querySelector('.tv-app').dataset.reducedMotion,focus:document.activeElement?.dataset.focus||'',overflow:document.querySelector('.content').scrollWidth-document.querySelector('.content').clientWidth})"))
        assertTrue("The captured state must have a focused control", dom.getString("focus").isNotBlank())
        assertTrue("Horizontal content overflow", dom.getInt("overflow") <= 1)
        assertEquals("true", dom.getString("reducedMotion"))
        assertEquals(visualEpochMs, dom.getLong("visualEpochMs"))
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("Empty compositor screenshot")
        try {
            val metadata = JSONObject().put("schema", 1).put("state", label)
                .put("family", "hybrid-android-production").put("route", model.page.name)
                .put("api", Build.VERSION.SDK_INT).put("provider", WebViewCompat.getCurrentWebViewPackage(activity)?.versionName)
                .put("fingerprint", Build.FINGERPRINT).put("densityDpi", activity.resources.displayMetrics.densityDpi)
                .put("width", bitmap.width).put("height", bitmap.height).put("dom", dom)
                .put("activity", activity.javaClass.name).put("syntheticContent", true)
            assertEquals(bitmap.width.toDouble(), dom.getDouble("width") * dom.getDouble("dpr"), 1.0)
            assertEquals(bitmap.height.toDouble(), dom.getDouble("height") * dom.getDouble("dpr"), 1.0)
            File(output, "$label.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(output, "$label.json").writeText(metadata.toString(2))
        } finally { bitmap.recycle() }
    }

    @Test fun currentProductionRoutesAndMaterialHaveFullResolutionCaptures() {
        focus("nav:HOME")
        capture("home")
        for (page in listOf(Page.EXPLORE, Page.MOVIES, Page.SERIES, Page.SEARCH, Page.LIVE, Page.LIBRARY, Page.SETTINGS)) {
            activate("nav:${page.name}")
            awaitRoute(page)
            if (page == Page.LIVE) {
                rule.waitUntil(15_000) { model.channels.isNotEmpty() && model.epgPrograms.isNotEmpty() }
                rule.waitUntil(10_000) { evaluate("document.querySelectorAll('.program.on-air').length===1") == "true" }
            }
            capture(page.name.lowercase())
        }
        focus("settings:connection")
        repeat(6) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        rule.waitUntil(5_000) { evaluate("document.activeElement?.dataset.focus") == "settings:developer" }
        capture("settings-scrolled")
        activate("nav:HOME"); awaitRoute(Page.HOME)
        activate("hero:open")
        rule.waitUntil(25_000) { model.page == Page.DETAIL && !model.busy && model.selected?.episodes?.isNotEmpty() == true }
        awaitRoute(Page.DETAIL)
        // An uncached detail first focuses Favorite while Play is disabled. When
        // episodes arrive, the production UI deliberately preserves that valid
        // focus. Capture the primary state with one real Left, not forced DOM
        // focus, a second click, or an expectation that loading steals focus.
        rule.waitUntil(5_000) {
            evaluate("(() => {const p=document.querySelector('[data-focus=\"detail:play\"]');return Boolean(p&&!p.disabled)&&['detail:play','detail:favorite'].includes(document.activeElement?.dataset.focus);})()") == "true"
        }
        if (evaluate("document.activeElement?.dataset.focus") == "detail:favorite") key(KeyEvent.KEYCODE_DPAD_LEFT)
        rule.waitUntil(5_000) { evaluate("document.activeElement?.dataset.focus") == "detail:play" }
        capture("detail")
        key(KeyEvent.KEYCODE_BACK); awaitRoute(Page.HOME)
        focus("hero:open"); key(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.waitUntil(5_000) { evaluate("document.activeElement?.dataset.focus?.startsWith('card:')") == "true" }
        capture("rail-focus")
        activate("nav:SETTINGS"); awaitRoute(Page.SETTINGS)
        activate("quality:BASIC")
        rule.waitUntil(5_000) { evaluate("document.querySelector('.tv-app').dataset.quality") == "BASIC" }
        activate("nav:HOME"); awaitRoute(Page.HOME)
        capture("home-basic")
        completedCaptures = true
    }
}
