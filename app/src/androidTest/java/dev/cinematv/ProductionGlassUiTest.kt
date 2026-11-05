package dev.cinematv

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.ui.tv.components.ProductionGlassObservation
import dev.cinematv.ui.tv.design.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Real app UI/inputs plus native window pixels. No media, shader timing or device certification. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ProductionGlassUiTest {
    @get:Rule val rule = createAndroidComposeRule<ProductionGlassTestActivity>()
    private lateinit var server: UiFixtureServer
    private val model get() = rule.activity.model
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val directory get() = File(rule.activity.filesDir, "production-glass").apply { mkdirs() }
    private val started = SystemClock.elapsedRealtime()
    private val captures = JSONArray()
    private val modes = listOf(GlassQuality.ENHANCED, GlassQuality.BACKDROP, GlassQuality.BASIC)
    private val sourceSha get() = requireNotNull(arguments.getString("glassSourceSha"))
    private val layout get() = requireNotNull(arguments.getString("glassLayout"))
    private val allRoutes get() = Build.VERSION.SDK_INT >= 33

    @Before fun connectOfflineProductionRoot() {
        require(sourceSha.matches(Regex("[0-9a-f]{40}"))) { "A real source commit is required" }
        require(layout in listOf("tv", "phone"))
        rule.waitUntil(15_000) { rule.activity.bundledFontsReady || rule.activity.fontLoadFailures.isNotEmpty() }
        assertTrue("Bundled Noto failed: ${rule.activity.fontLoadFailures}", rule.activity.bundledFontsReady)
        rule.waitUntil(10_000) { rule.activity.hasWindowFocus() }
        assertEquals("Window orientation must select the intended actual root", layout == "tv", rule.activity.isTv)
        server = UiFixtureServer(InstrumentationRegistry.getInstrumentation().context).apply { homeCatalog = true }
        rule.runOnIdle {
            model.updateReducedMotion(true)
            model.updateGlassQuality(GlassQuality.BASIC)
            model.repo.configureMetadata(server.base, "offline-fixture-token")
            model.serverAddress = server.base
            model.connect()
            model.navigate(Page.HOME)
        }
        rule.waitUntil(20_000) { model.authorized && model.featured.size == 24 && model.tvShows.size == 24 && !model.busy }
        awaitScene()
        assertHomeControl()
    }

    @After fun exportAndClose() {
        // Partial JSON and the trace survive failure; only the method sets completed=true.
        if (::server.isInitialized) server.close()
        rule.activity.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
    }

    private fun observation(): ProductionGlassObservation = checkNotNull(rule.activity.glassObservation)
    private fun counts(): IntArray = rule.runOnUiThread {
        rule.activity.glassDraws.let { intArrayOf(it.successfulLensDraws, it.blurDraws, it.basicDraws) }
    }
    private fun resetCounts() = rule.runOnUiThread {
        rule.activity.glassDraws.let { it.successfulLensDraws = 0; it.blurDraws = 0; it.basicDraws = 0 }
    }
    private fun counter(mode: GlassRenderMode) = when (mode) {
        GlassRenderMode.FULL -> 0
        GlassRenderMode.BLUR -> 1
        GlassRenderMode.BASIC -> 2
    }
    private fun awaitPolicy(requested: GlassQuality) {
        rule.waitUntil(15_000) {
            rule.activity.glassObservation?.let {
                it.requested == requested && it.decision.reason != GlassDecisionReason.WAITING_FOR_WINDOW
            } == true
        }
        val actual = observation()
        assertEquals(resolveProductionGlass(requested, actual.environment), actual.decision)
        assertEquals(actual.decision.mode, actual.actualMode)
        assertFalse("Unexpected renderer failure, not accepted as successful coverage", actual.failed)
        assertEquals("Requested preference must not be rewritten by fallback", requested, model.glassQuality)
        assertTrue(actual.environment.userReducedMotion)
        assertFalse(actual.decision.animationsEnabled)
        if (actual.actualMode != GlassRenderMode.BASIC) {
            assertTrue("Live glass must await a committed native frame and focus", actual.environment.windowReady)
            assertTrue(actual.environment.hardwareAccelerated)
        }
    }
    private fun awaitScene() {
        val art = rule.runOnIdle {
            (if (model.page == Page.DETAIL) model.selected else model.focused ?: model.featured.firstOrNull())?.backdrop
        }
        assertTrue("Only local generated fixture imagery is permitted", art?.startsWith("android.resource://dev.cinematv/") == true)
        rule.waitUntil(10_000) { rule.activity.decodedScene == art }
        rule.runOnIdle { model.dismissMessage() }
    }
    private fun assertHomeControl() {
        if (rule.activity.isTv) rule.onNodeWithText("观看详情").assertIsFocused()
        else rule.onNodeWithText("查看影片").assertIsDisplayed().assertHasClickAction()
    }
    private fun tab(label: String) = rule.onNode(hasText(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    private fun key(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        trace("key=$key")
    }
    private fun back() {
        Espresso.pressBack()
        trace("Back")
    }
    private fun trace(action: String) {
        val focused = rule.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() }
        File(directory, "focus-trace.txt").appendText("$action route=${model.page}\n$focused\n")
    }

    private fun recordRenderer(stage: String, expected: GlassRenderMode) {
        val actual = observation()
        val draws = counts()
        val snapshot = JSONObject().put("stage", stage).put("route", model.page.name)
            .put("expected", expected.name).put("requested", actual.requested.name)
            .put("policyMode", actual.actualMode.name).put("reason", actual.decision.reason.name)
            .put("failed", actual.failed).put("windowReady", actual.environment.windowReady)
            .put("hardwareAccelerated", actual.environment.hardwareAccelerated)
            .put("decodedScene", rule.activity.decodedScene ?: JSONObject.NULL)
            .put("draws", JSONObject().put("full", draws[0]).put("blur", draws[1]).put("basic", draws[2]))
        File(directory, "renderer-trace.jsonl").appendText(snapshot.toString() + "\n")
        android.util.Log.i("CinemaTVGlassQa", snapshot.toString())
    }

    private fun phonePressUntilMaterialDraw(label: String, expected: GlassRenderMode) {
        val button = rule.onNodeWithText(label).assertIsDisplayed().assertHasClickAction()
        val before = counts()[counter(expected)]
        recordRenderer("$label before-down", expected)
        button.performTouchInput { down(center) }
        var drawn = false
        try {
            // Scrollable parents delay PressInteraction while deciding whether this is
            // a scroll. waitForIdle alone does not guarantee that delayed press fired;
            // cancelling then can produce no material change at all. Keep the genuine
            // pointer down until its production material actually draws, then cancel.
            // No fixed sleep, source remount, forced redraw or policy override is used.
            rule.waitUntil(10_000) { counts()[counter(expected)] > before }
            drawn = true
        } finally {
            recordRenderer("$label held-draw=$drawn", expected)
            button.performTouchInput { cancel() }
        }
        rule.waitForIdle()
        recordRenderer("$label cancelled", expected)
    }

    @Test fun actualProductionRootsRenderRequestedTiersAndKeepNativeInput() {
        writeManifest(false)
        for (requested in modes) {
            rule.runOnIdle { model.updateGlassQuality(requested) }
            awaitPolicy(requested)
            awaitScene()
            if (!rule.activity.isTv) recordRenderer("home-${requested.name} before-reset", observation().actualMode)
            resetCounts()
            // Real focus/press changes repaint existing production controls, even when API26
            // correctly resolves all three preferences to BASIC. No remount/forceRedraw hook.
            if (rule.activity.isTv) {
                key(Key.DirectionUp)
                tab("精选").assertIsFocused().assertIsSelected()
                key(Key.DirectionDown)
                assertHomeControl()
            } else {
                phonePressUntilMaterialDraw("查看影片", observation().actualMode)
            }
            awaitPolicy(requested)
            val effective = observation().actualMode
            rule.waitUntil(10_000) { counts()[counter(effective)] > 0 }
            val draws = counts()
            if (effective != GlassRenderMode.FULL) assertEquals("No lens draw in this tier", 0, draws[0])
            if (effective == GlassRenderMode.BASIC) assertEquals("No blur draw in safe tier", 0, draws[1])
            capture("${requested.name.lowercase()}-home", requested, effective)
            if (allRoutes || requested == GlassQuality.BASIC) {
                if (rule.activity.isTv) tvRoutes(requested) else phoneRoutes(requested)
            }
        }
        writeManifest(true)
    }

    private fun tvRoutes(requested: GlassQuality) {
        key(Key.DirectionUp)
        tab("精选").assertIsFocused().assertIsSelected()
        key(Key.DirectionRight)
        tab("电影").assertIsFocused().assertIsNotSelected()
        tab("精选").assertIsSelected()
        assertEquals("Focus alone must not activate a route", Page.HOME, model.page)
        capture("${requested.name.lowercase()}-navigation", requested, observation().actualMode)
        key(Key.DirectionCenter)
        rule.waitUntil(10_000) { model.page == Page.MOVIES }
        tab("电影").assertIsSelected()
        trace("Movies activated")
        back()
        rule.waitUntil(10_000) { model.page == Page.HOME }
        // Existing navigation policy preserves the focused tab across section
        // changes; returning Home must not steal it into page content.
        tab("电影").assertIsFocused().assertIsNotSelected()
        tab("精选").assertIsSelected()
        key(Key.DirectionDown)
        assertHomeControl()
        resetCounts()
        key(Key.DirectionCenter)
        rule.waitUntil(15_000) { model.page == Page.DETAIL && !model.busy && model.selected?.episodes?.size == 36 }
        rule.onNodeWithText("▶ 立即播放").assertIsFocused()
        trace("Detail opened")
        awaitScene()
        val effective = observation().actualMode
        rule.waitUntil(10_000) { counts()[counter(effective)] > 0 }
        capture("${requested.name.lowercase()}-detail", requested, effective)
        back()
        rule.waitUntil(10_000) { model.page == Page.HOME }
        assertHomeControl()
        awaitScene()
    }

    private fun phoneRoutes(requested: GlassQuality) {
        rule.onNodeWithText("查看影片").performTouchInput { click() }
        rule.waitUntil(15_000) { model.page == Page.DETAIL && !model.busy && model.selected?.episodes?.size == 36 }
        awaitScene()
        rule.onNodeWithText("播放 / 续播").assertIsDisplayed().assertHasClickAction()
        resetCounts()
        phonePressUntilMaterialDraw("播放 / 续播", GlassRenderMode.BASIC)
        // Detail has no local glass recording. It must not inherit the disposed Home hero.
        rule.waitUntil(10_000) { counts()[2] > 0 }
        assertEquals(0, counts()[0])
        assertEquals(0, counts()[1])
        capture("${requested.name.lowercase()}-detail", requested, GlassRenderMode.BASIC)
        back()
        rule.waitUntil(10_000) { model.page == Page.HOME }
        assertHomeControl()
        rule.onNodeWithContentDescription("打开浏览").performTouchInput { click() }
        rule.waitUntil(10_000) { model.page == Page.MOVIES }
        rule.onNodeWithContentDescription("打开浏览").assertIsSelected()
        capture("${requested.name.lowercase()}-navigation", requested, null)
        back()
        rule.waitUntil(10_000) { model.page == Page.HOME }
        rule.onNodeWithContentDescription("打开精选").assertIsSelected()
        assertHomeControl()
        awaitScene()
    }

    private fun capture(name: String, requested: GlassQuality, renderer: GlassRenderMode?) {
        assertNotEquals("No video/surface sampling in this suite", Page.PLAYER, model.page)
        assertTrue("No cross-window Dialog capture", rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        awaitPolicy(requested)
        val root = rule.onRoot().fetchSemanticsNode()
        check(root.positionInWindow.x == 0f && root.positionInWindow.y == 0f)
        val activity = rule.activity
        val bitmap = TvWindowCapture.capture(activity, root.size.width, root.size.height)
        val file = File(directory, "$name.png")
        try {
            check(file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) })
            val observed = observation()
            val env = observed.environment
            captures.put(JSONObject().put("file", file.name).put("sha256", sha256(file))
                .put("sourceSha", sourceSha).put("sdk", Build.VERSION.SDK_INT)
                .put("widthPx", bitmap.width).put("heightPx", bitmap.height)
                .put("densityDpi", activity.resources.displayMetrics.densityDpi)
                .put("route", model.page.name).put("requested", requested.name)
                .put("policyMode", observed.actualMode.name).put("renderer", renderer?.name ?: "NOT_ASSERTED")
                .put("reason", observed.decision.reason.name).put("failed", observed.failed)
                .put("draws", JSONObject().put("full", counts()[0]).put("blur", counts()[1]).put("basic", counts()[2]))
                .put("decodedScene", activity.decodedScene ?: JSONObject.NULL)
                .put("environment", JSONObject().put("api", env.api).put("lowRam", env.lowRam)
                    .put("memoryClassMb", env.memoryClassMb).put("hardwareAccelerated", env.hardwareAccelerated)
                    .put("windowReady", env.windowReady).put("powerSave", env.powerSave).put("thermal", env.thermal.name)
                    .put("userReducedMotion", env.userReducedMotion).put("systemAnimationsEnabled", env.systemAnimationsEnabled))
                .put("foregroundVerified", activity.hasWindowFocus()).put("nativeWindowFrameFence", true)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started))
        } finally { bitmap.recycle() }
        writeManifest(false)
    }
    private fun writeManifest(completed: Boolean) {
        File(directory, "captures.json").writeText(JSONObject().put("schema", 1).put("completed", completed)
            .put("sourceSha", sourceSha).put("sdk", Build.VERSION.SDK_INT).put("layout", layout)
            .put("host", "dev.cinematv.ProductionGlassTestActivity")
            .put("root", if (rule.activity.isTv) "TvRootApp" else "MobileRootApp")
            .put("scope", "Actual production UI in an offline debug host; original generated local artwork; native compositor pixels")
            .put("excludes", JSONArray(listOf("video surfaces", "cross-window dialogs", "physical-device performance certification")))
            .put("captureMethod", "TvWindowCapture: hardware frame metrics fence and UiAutomation native display screenshot; no crop or resize")
            .put("captures", captures).toString(2))
    }
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
