package dev.cinematv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
            model.focused = null
            model.refreshCatalog()
            model.navigate(Page.SETTINGS)
        }
        rule.waitUntil(15_000) { model.recommendationSnapshots.any { it.second.rows.isNotEmpty() } }
        // Initial home loading must wait for the asynchronous ranker before choosing artwork.
        rule.waitUntil(15_000) { model.focused != null }
        if (tv) rule.onNodeWithText("关于 CinemaTV").performClick()
        val opener = rule.onNodeWithTag("recommendation-dashboard-open")
        if (tv) {
            opener.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            opener.performKeyInput { pressKey(Key.DirectionCenter) }
        } else opener.performClick()
        rule.onNodeWithTag("recommendation-dashboard").assertIsDisplayed()
        rule.onNodeWithText("Kotlin 基线实现", substring = true).assertExists()
        if (tv) rule.onNodeWithTag("recommendation-dashboard-close").assertIsFocused()
        rule.runOnIdle {
            val snapshot = model.recommendationSnapshots.first().second
            assertEquals(model.featured.map { it.id }, snapshot.rows.map { it.item.id })
            assertEquals(snapshot.rows.size, snapshot.ranking.items.size)
        }
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
    }
}

private fun captureDashboard(rule: ComposeContentTestRule, activity: ComponentActivity, tv: Boolean, stage: String) {
    val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?: activity.getExternalFilesDir(null)!!.absolutePath, "ui-evidence").apply { mkdirs() }
    // This captures the Compose dialog viewport, not a claim about physical display readback.
    val bitmap = rule.onNodeWithTag("recommendation-dashboard").captureToImage().asAndroidBitmap()
    File(directory, "recommendation-${if (tv) "tv" else "phone"}-$stage.png").outputStream().use {
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
    }
}
