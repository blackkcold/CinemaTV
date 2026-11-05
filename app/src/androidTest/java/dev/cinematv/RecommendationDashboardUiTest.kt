package dev.cinematv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import dev.cinematv.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileRecommendationDashboardTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun showsLiveSnapshotClosesAndReopens() = recommendationDashboard(rule, rule.activity, false)
}

@RunWith(AndroidJUnit4::class)
class TvRecommendationDashboardTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    @Test fun showsLiveSnapshotAndReturnsDpadFocus() = recommendationDashboard(rule, rule.activity, true)
}

@OptIn(ExperimentalTestApi::class)
private fun recommendationDashboard(rule: ComposeContentTestRule, activity: ComponentActivity, tv: Boolean) {
    val model = ViewModelProvider(activity)[CinemaViewModel::class.java]
    UiFixtureServer(activity).use { server ->
        server.homeCatalog = true
        rule.runOnIdle {
            model.repo.configureServer(server.base)
            model.repo.configureMetadata(server.base, "offline-fixture-token")
            model.refreshCatalog()
            model.navigate(Page.SETTINGS)
        }
        rule.waitUntil(15_000) { model.recommendationSnapshots.any { it.second.rows.isNotEmpty() } }
        // Initial home loading must wait for the asynchronous ranker before choosing artwork.
        rule.waitUntil(15_000) { model.focused != null }
        val seed = rule.runOnIdle { model.featured.first() }
        val wasFavorite = model.repo.isFavorite(seed)
        runBlocking { if (!wasFavorite) model.repo.toggleFavorite(seed) }
        rule.waitUntil(15_000) { model.recommendationSnapshots.first().second.ranking.positiveWorkCount > 0 }
        if (tv) rule.onNodeWithText("关于 CinemaTV").performClick()
        val opener = rule.onNodeWithTag("recommendation-dashboard-open")
        if (tv) {
            opener.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            opener.performKeyInput { pressKey(Key.DirectionCenter) }
        } else opener.performClick()
        rule.onNodeWithTag("recommendation-dashboard").assertIsDisplayed()
        rule.onNodeWithText("RankSys 0.4.3 + CinemaTV 数值保护", substring = true).assertExists()
        if (tv) rule.onNodeWithTag("recommendation-dashboard-close").assertIsFocused()
        rule.runOnIdle {
            val snapshot = model.recommendationSnapshots.first().second
            assertEquals(model.featured.map { it.id }, snapshot.rows.map { it.item.id })
            assertEquals(snapshot.rows.size, snapshot.ranking.items.size)
            assertTrue(snapshot.ranking.backendVersion.startsWith("RankSys-0.4.3"))
            assertNull(snapshot.ranking.backendFallbackReason)
            assertTrue(snapshot.ranking.items.any { it.trace.coreDecision != null })
        }
        rule.onNodeWithTag("recommendation-licenses-open").performClick()
        rule.onNodeWithTag("recommendation-licenses").assertIsDisplayed()
        try {
            // Focusable TV scroll containers can merge text into a different semantics node.
            // Inspect the actual text child, not an assumption about merged-tree ownership.
            rule.waitUntil(10_000) { rule.onAllNodesWithText("fastutil 7.0.6", substring = true,
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: activity.getExternalFilesDir(null)!!.absolutePath, "ui-evidence").apply { mkdirs() }
            File(directory, "recommendation-licenses-semantics.txt").writeText(
                rule.onNodeWithTag("recommendation-licenses", useUnmergedTree = true).printToString(maxDepth = 30))
            captureDashboard(rule, activity, tv, "licenses-failure", "recommendation-licenses")
            throw failure
        }
        captureDashboard(rule, activity, tv, "licenses", "recommendation-licenses")
        if (tv) {
            rule.onNodeWithTag("recommendation-licenses-close").assertIsFocused()
            val licenseText = rule.onNodeWithTag("recommendation-license-text")
            licenseText.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            licenseText.performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitUntil(5_000) {
                licenseText.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f
            }
        }
        rule.onNodeWithTag("recommendation-licenses-close").performClick()
        rule.onNodeWithTag("recommendation-licenses").assertDoesNotExist()
        if (tv) rule.onNodeWithTag("recommendation-licenses-open").assertIsFocused()
        captureDashboard(rule, activity, tv, "overview")
        if (tv) {
            rule.onNodeWithTag("recommendation-dashboard").performScrollToNode(hasTestTag("recommendation-row-1"))
            val row = rule.onNodeWithTag("recommendation-row-1")
            row.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            row.assertIsFocused()
            val previous = rule.runOnIdle { model.recommendationSnapshots.first().second.asOfMs }
            rule.runOnIdle { model.refreshCatalog() }
            rule.waitUntil(15_000) { model.recommendationSnapshots.first().second.asOfMs > previous }
            // Rebuilding an identical catalog must not discard the current row's D-pad focus.
            row.assertIsFocused()
            captureDashboard(rule, activity, tv, "focused-row")
        }
        // The dialog has its own Android window. Target it rather than the activity root.
        Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.pressBack())
        rule.onNodeWithTag("recommendation-dashboard").assertDoesNotExist()
        if (tv) opener.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) } else opener.performClick()
        rule.onNodeWithTag("recommendation-dashboard-close").performClick()
        rule.onNodeWithTag("recommendation-dashboard").assertDoesNotExist()
        if (tv) opener.assertIsFocused()
        runBlocking { if (!wasFavorite && model.repo.isFavorite(seed)) model.repo.toggleFavorite(seed) }
    }
}

private fun captureDashboard(rule: ComposeContentTestRule, activity: ComponentActivity, tv: Boolean, stage: String,
    tag: String = "recommendation-dashboard") {
    val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?: activity.getExternalFilesDir(null)!!.absolutePath, "ui-evidence").apply { mkdirs() }
    rule.onNodeWithTag(tag).assertIsDisplayed()
    rule.waitForIdle()
    // API26 compositor rejects this emulator landscape/display-size mismatch,
    // and Compose captureToImage rejects pre-28 dialogs. Copy the actual dialog
    // window instead, with bounded capture and unchanged functional assertions.
    val windowCopy = android.os.Build.VERSION.SDK_INT < 28
    val bitmap = if (windowCopy) captureDialogWindow(rule, tag)
        else rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    val name = "recommendation-${if (tv) "tv" else "phone"}-$stage"
    File(directory, "$name.capture.txt").writeText(
        if (windowCopy) "Dialog-window PixelCopy; API ${android.os.Build.VERSION.SDK_INT}; not physical display readback\n"
        else "Compose dialog viewport capture; API ${android.os.Build.VERSION.SDK_INT}\n")
    File(directory, "$name.png").outputStream().use {
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
    }
    if (windowCopy) bitmap.recycle()
}

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun captureDialogWindow(rule: ComposeContentTestRule, tag: String): android.graphics.Bitmap {
    val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
    val host = (node.root as androidx.compose.ui.platform.ViewRootForTest).view
    var window: android.view.Window? = null
    lateinit var bounds: android.graphics.Rect
    rule.runOnIdle {
        var parent: android.view.View? = host
        while (parent != null && window == null) {
            window = (parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            parent = parent.parent as? android.view.View
        }
        val target = checkNotNull(window) { "No dialog window for API26 evidence" }
        check(target.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0)
        check(target.decorView.hasWindowFocus() && host.isShown && host.isAttachedToWindow)
        val origin = IntArray(2)
        host.getLocationInWindow(origin)
        val rect = node.boundsInRoot
        bounds = android.graphics.Rect(kotlin.math.ceil(rect.left).toInt() + origin[0],
            kotlin.math.ceil(rect.top).toInt() + origin[1], kotlin.math.floor(rect.right).toInt() + origin[0],
            kotlin.math.floor(rect.bottom).toInt() + origin[1])
        check(bounds.width() > 0 && bounds.height() > 0 && bounds.width().toLong() * bounds.height() <= 3_000_000L)
    }
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor { work ->
        Thread(work, "Recommendation-dialog-copy").apply { isDaemon = true }
    }
    val lock = Any()
    var abandoned = false
    var transferred = false
    var captured: android.graphics.Bitmap? = null
    val future = executor.submit<android.graphics.Bitmap> {
        val bitmap = android.graphics.Bitmap.createBitmap(bounds.width(), bounds.height(), android.graphics.Bitmap.Config.ARGB_8888)
        val callback = android.os.HandlerThread("Recommendation-copy-result").apply { isDaemon = true; start() }
        var published = false
        try {
            val done = java.util.concurrent.CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicInteger(android.view.PixelCopy.ERROR_UNKNOWN)
            // Android8 may perform its native copy synchronously, so request and wait are off-main.
            android.view.PixelCopy.request(checkNotNull(window), bounds, bitmap, { status ->
                result.set(status); done.countDown()
            }, android.os.Handler(callback.looper))
            check(done.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Dialog PixelCopy callback timed out" }
            check(result.get() == android.view.PixelCopy.SUCCESS) { "Dialog PixelCopy failed: ${result.get()}" }
            synchronized(lock) {
                check(!abandoned) { "Dialog PixelCopy completed after deadline" }
                captured = bitmap
                published = true
            }
            bitmap
        } finally {
            if (!published) bitmap.recycle()
            callback.quitSafely()
        }
    }
    try {
        val bitmap = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
        rule.runOnIdle { check(checkNotNull(window).decorView.hasWindowFocus() && host.isShown) }
        transferred = true
        return bitmap
    } finally {
        synchronized(lock) { abandoned = true; if (!transferred) captured?.recycle() }
        future.cancel(true)
        executor.shutdownNow()
    }
}
