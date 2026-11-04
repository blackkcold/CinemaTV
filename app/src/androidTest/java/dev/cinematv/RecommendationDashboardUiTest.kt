package dev.cinematv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        Espresso.pressBack()
        rule.onNodeWithTag("recommendation-dashboard").assertDoesNotExist()
        if (tv) opener.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) } else opener.performClick()
        rule.onNodeWithTag("recommendation-dashboard-close").performClick()
        rule.onNodeWithTag("recommendation-dashboard").assertDoesNotExist()
        if (tv) opener.assertIsFocused()
    }
}
