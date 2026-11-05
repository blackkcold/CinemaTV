package dev.cinematv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import dev.cinematv.ui.tv.TvRootApp
import dev.cinematv.ui.tv.components.GlassDrawDiagnostics
import dev.cinematv.ui.tv.components.LocalGlassDrawDiagnostics
import dev.cinematv.ui.tv.components.LocalProductionGlassObserver
import dev.cinematv.ui.tv.components.LocalProductionSceneDecoded
import dev.cinematv.ui.tv.components.ProductionGlassObservation
import dev.cinematv.ui.tv.design.GlassDecisionReason
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.design.GlassRenderMode
import dev.cinematv.ui.typography.CinemaNotoProvider
import dev.cinematv.ui.typography.LocalCinemaNoto
import dev.cinematv.ui.typography.NotoRegion
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real production ViewModel, roots, admission, focus and materials. No substituted
 * scene/clock/renderer, forced redraw, capability override or preselected focus.
 * Only fixture wiring and passive timing/diagnostic observers are benchmark-specific.
 * No launcher or benchmark command is added to normal release/debug source sets.
 */
class GlassBenchmarkActivity : ComponentActivity() {
    private val model: CinemaViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fixture: GlassBenchmarkFixtureServer
    private lateinit var runId: String
    private lateinit var sourceSha: String
    private lateinit var apkSha: String
    private lateinit var requested: GlassQuality
    private lateinit var expected: GlassRenderMode
    private var observation: ProductionGlassObservation? = null
    private val draws = GlassDrawDiagnostics()
    private var decodedScene: String? = null
    private var fontsReady = false
    private var fontFailures = emptySet<String>()
    private var recorder: FrameTimingRecorder? = null
    private var measuring = false
    private var originalWindowCallback: Window.Callback? = null
    private var inputWindowCallback: Window.Callback? = null
    private var inputIndex = 0
    private var startStatus = JSONObject()
    private val inputs = JSONArray()
    private val violations = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "dev.cinematv.glassbenchmark") { "Benchmark storage must be isolated" }
        check(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Debuggable benchmark is invalid" }
        runId = requireNotNull(intent.getStringExtra("benchmark_id")).also { require(it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
        sourceSha = requireNotNull(intent.getStringExtra("source_sha")).also { require(it.matches(Regex("[0-9a-f]{40}"))) }
        apkSha = requireNotNull(intent.getStringExtra("apk_sha")).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        requested = GlassQuality.valueOf(requireNotNull(intent.getStringExtra("quality")))
        expected = when (requested) {
            GlassQuality.ENHANCED -> GlassRenderMode.FULL
            GlassQuality.BACKDROP -> GlassRenderMode.BLUR
            GlassQuality.BASIC -> GlassRenderMode.BASIC
            else -> error("Only explicit Full, Blur and Basic preferences are compared")
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        fixture = GlassBenchmarkFixtureServer(this).apply { homeCatalog = true }
        model.updateReducedMotion(false)
        model.updateGlassQuality(requested)
        model.repo.configureMetadata(fixture.base, "synthetic-benchmark-token")
        model.serverAddress = fixture.base
        model.connect()
        model.navigate(Page.HOME)
        setContent {
            CinemaNotoProvider {
                val fonts = LocalCinemaNoto.current
                SideEffect {
                    fontsReady = fonts.regional.size == NotoRegion.entries.size && fonts.failedAssets.isEmpty()
                    fontFailures = fonts.failedAssets
                }
                CompositionLocalProvider(
                    LocalGlassDrawDiagnostics provides draws,
                    LocalProductionGlassObserver provides { value ->
                        observation = value
                        if (measuring && (value.actualMode != expected || value.failed || !value.decision.animationsEnabled))
                            violations += "${value.actualMode}/${value.decision.reason}/failed=${value.failed}/animate=${value.decision.animationsEnabled}"
                    },
                    LocalProductionSceneDecoded provides { decodedScene = it },
                ) {
                    BackHandler(enabled = model.page != Page.HOME) { model.back() }
                    TvRootApp(model)
                }
            }
        }
        // Observe the public Window callback before forwarding every event unchanged.
        // ComponentActivity's override is restricted to AndroidX; do not call or
        // override that API and do not swallow native Back/D-pad dispatch.
        val delegate = requireNotNull(window.callback)
        originalWindowCallback = delegate
        val inputObserver = object : Window.Callback by delegate {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                observeNativeKey(event)
                return delegate.dispatchKeyEvent(event)
            }
        }
        inputWindowCallback = inputObserver
        window.callback = inputObserver
        Log.i(TAG, "created $runId")
    }

    private fun drawJson() = JSONObject().put("full", draws.successfulLensDraws)
        .put("blur", draws.blurDraws).put("basic", draws.basicDraws)
    private fun ready(): Boolean {
        val value = observation ?: return false
        val art = (if (model.page == Page.DETAIL) model.selected else model.focused ?: model.featured.firstOrNull())?.backdrop
        return fontsReady && fontFailures.isEmpty() && hasWindowFocus() && model.authorized && !model.busy &&
            model.featured.size == 24 && model.tvShows.size == 24 &&
            value.decision.reason != GlassDecisionReason.WAITING_FOR_WINDOW &&
            decodedScene == art && art?.startsWith("android.resource://$packageName/") == true
    }
    private fun status(): JSONObject {
        val value = observation
        val bookmark = model.focusCoordinator.last(model.focusRoute)
        return JSONObject().put("runId", runId).put("ready", ready()).put("route", model.page.name)
            .put("bookmarkRegion", bookmark?.region ?: "").put("bookmarkItem", bookmark?.itemKey ?: "")
            .put("requested", requested.name).put("expectedMode", expected.name)
            .put("actualMode", value?.actualMode?.name ?: "PENDING")
            .put("reason", value?.decision?.reason?.name ?: "PENDING").put("failed", value?.failed ?: false)
            .put("animationsEnabled", value?.decision?.animationsEnabled ?: false)
            .put("draws", drawJson()).put("decodedScene", decodedScene ?: JSONObject.NULL)
            .put("densityDpi", resources.displayMetrics.densityDpi)
            .put("widthPx", window.decorView.width).put("heightPx", window.decorView.height)
            .put("fontsReady", fontsReady).put("fontFailures", JSONArray(fontFailures.toList()))
            .put("foreground", hasWindowFocus()).put("busy", model.busy)
            .put("environment", value?.environment?.let { env ->
                JSONObject().put("api", env.api).put("lowRam", env.lowRam).put("memoryClassMb", env.memoryClassMb)
                    .put("hardwareAccelerated", env.hardwareAccelerated).put("windowReady", env.windowReady)
                    .put("powerSave", env.powerSave).put("thermal", env.thermal.name)
                    .put("userReducedMotion", env.userReducedMotion).put("systemAnimationsEnabled", env.systemAnimationsEnabled)
            } ?: JSONObject.NULL)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("benchmark_status", false)) Log.i(TAG, "status ${status()}")
        if (intent.getBooleanExtra("benchmark_start", false) && recorder == null) {
            startStatus = status()
            val expectedDraws = when (expected) {
                GlassRenderMode.FULL -> draws.successfulLensDraws
                GlassRenderMode.BLUR -> draws.blurDraws
                GlassRenderMode.BASIC -> draws.basicDraws
            }
            if (!ready() || model.page != Page.HOME || observation?.actualMode != expected || observation?.failed != false ||
                observation?.decision?.animationsEnabled != true || expectedDraws == 0) {
                Log.i(TAG, "blocked $startStatus")
                return
            }
            draws.successfulLensDraws = 0; draws.blurDraws = 0; draws.basicDraws = 0
            measuring = true
            recorder = FrameTimingRecorder(this)
            Log.i(TAG, "started $runId")
        }
        if (intent.getBooleanExtra("benchmark_stop", false)) {
            measuring = false
            recorder?.finish(runId, JSONObject().put("sourceSha", sourceSha).put("apkSha256", apkSha)
                .put("package", packageName).put("requested", requested.name).put("expectedMode", expected.name)
                .put("root", "TvRootApp").put("viewModel", "CinemaViewModel").put("provider", "ProductionGlassProvider")
                .put("screenrecordConcurrent", false).put("start", startStatus).put("end", status())
                .put("inputs", inputs).put("policyViolations", JSONArray(violations.toList())))
            recorder = null
            Log.i(TAG, "complete $runId")
        }
    }
    private fun observeNativeKey(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode in INPUT_KEYS) {
            val start = System.nanoTime()
            val input = JSONObject().put("index", inputIndex++).put("key", event.keyCode)
                .put("deviceId", event.deviceId).put("source", event.source).put("routeBefore", model.page.name)
                .put("measured", measuring).put("receivedNs", start).put("drawsBefore", drawJson())
            recorder?.mark(input, start)
            // Passive diagnostics are taken after the 450 ms active window, not in a frame callback.
            handler.postDelayed({
                input.put("after", status())
                inputs.put(input)
                Log.i(TAG, "input $input")
            }, 520)
        }
    }
    override fun onDestroy() {
        if (window.callback === inputWindowCallback) originalWindowCallback?.let { window.callback = it }
        inputWindowCallback = null
        originalWindowCallback = null
        recorder?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::fixture.isInitialized) fixture.close()
        super.onDestroy()
    }
    companion object {
        const val TAG = "ProductionGlassBenchmark"
        val INPUT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK)
    }
}
