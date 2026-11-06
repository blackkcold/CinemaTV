package dev.cinematv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly registered in the phone matrix, independent of screenshot goldens. */
@RunWith(AndroidJUnit4::class)
class MobileWorkNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun seasonsSourcesKeepIndependentStateAndBack() = workNavigationScenario(rule, rule.activity, tv = false)
}

/** Explicitly registered in the TV matrix, uses the real TV root and remote keys. */
@RunWith(AndroidJUnit4::class)
class TvWorkNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    @Test fun seasonsSourcesKeepIndependentStateAndBack() = workNavigationScenario(rule, rule.activity, tv = true)
}

@OptIn(ExperimentalTestApi::class)
private fun workNavigationScenario(rule: ComposeContentTestRule, activity: ComponentActivity, tv: Boolean) {
    val model = ViewModelProvider(activity)[CinemaViewModel::class.java]
    UiFixtureServer(activity).use { server ->
        fun item(provider: String, season: Int?, work: String = "work-navigation-${server.base}") = MediaItem(
            "navigation-$season", "Navigation fixture", isTv = true, seasonNumber = season,
            source = TvSource(provider, "测试源 $provider", "${server.base}/$provider"),
            metadataIds = mapOf("tmdb" to work),
            episodes = (0 until 36).map { "${server.base}/stream.mp4?episode=$it" }, playlistKnown = true)
        val a = item("a", 1); val b = item("b", 1); val second = item("a", 2)
        val unknown = item("c", null); val conflict = item("d", 1, "different-work")
        listOf(a, b, second, unknown, conflict).forEach(server::registerDetail)
        runBlocking {
            model.repo.library.initialize()
            listOf(a, b, second, unknown, conflict).forEach { model.repo.library.cacheItem(it) }
            model.repo.library.saveProgress(a, 12000, 0, 90000)
            model.repo.library.saveProgress(b, 45000, 0, 90000)
            model.repo.setLibraryStatus(a, LibraryStatus.WATCHING)
            model.repo.setLibraryStatus(b, LibraryStatus.WATCHED)
            if (!model.repo.isFavorite(a)) model.repo.toggleFavorite(a)
        }
        rule.runOnIdle { model.repo.configureServer(server.base); model.navigate(Page.LIBRARY); model.open(a) }
        rule.waitUntil(15000) { !model.busy && model.selected?.source?.key == "a" }
        val originalRoute = model.focusRoute
        val initial = model.workNavigation!!
        assertEquals(3, initial.seasons.size)
        assertEquals(2, initial.currentSeason!!.sources.size)
        assertFalse(initial.seasons.flatMap { it.sources }.any { it.item.source?.key == "d" })
        fun revealPanel() { rule.onNodeWithText("季与片源").performScrollTo(); rule.waitForIdle() }
        fun choose(tag: String) {
            revealPanel()
            // performScrollTo only moves the closest scrollable ancestor. Revealing the
            // heading can leave both rails below the phone viewport; scrolling a child
            // option then moves its horizontal LazyRow without revealing it vertically.
            val railTag = if (tag.startsWith("work-seasons:")) "work-seasons-rail" else "work-sources-rail"
            rule.onNodeWithTag(railTag).performScrollTo().performScrollToNode(hasTestTag(tag))
            val node = rule.onNodeWithTag(tag).assertIsDisplayed()
            if (tv) {
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                node.assertIsFocused()
                node.performKeyInput { pressKey(Key.DirectionCenter) }
            } else node.performClick()
            rule.waitUntil(15000) { !model.busy }
            rule.waitForIdle()
            rule.onNodeWithTag(tag).assertIsSelected()
        }
        choose("work-sources:${MediaDomainMapper.entryId(b)}")
        rule.runOnIdle {
            assertEquals(MediaDomainMapper.entryId(b), model.selected?.let(MediaDomainMapper::entryId))
            assertEquals(45000L, model.selected!!.positionMs)
            assertEquals(LibraryStatus.WATCHED, model.repo.libraryStatus(model.selected!!))
            assertFalse(model.repo.isFavorite(model.selected!!))
            assertEquals(originalRoute, model.focusRoute)
            assertEquals(12000L, model.repo.library.snapshot.progress(a, 0)!!.positionMs)
        }
        choose("work-seasons:${MediaDomainMapper.seasonId(second)}")
        rule.runOnIdle { assertEquals(2, model.selected!!.seasonNumber); assertEquals(0L, model.selected!!.positionMs) }
        choose("work-seasons:${MediaDomainMapper.seasonId(unknown)}")
        rule.onNodeWithText(if (tv) "季数未知" else "季数未知 ✓").assertExists()
        rule.runOnIdle {
            assertNull(model.selected!!.seasonNumber)
            val before = model.selected
            model.selectWorkSource(initial.workId, "removed-season", "removed-source")
            model.selectWorkSource("obsolete-work", initial.selectedSeasonId, initial.selectedSourceId)
            assertEquals(before, model.selected)
        }
        choose("work-seasons:${MediaDomainMapper.seasonId(a)}")
        if (tv) rule.onNodeWithTag("work-seasons:${MediaDomainMapper.seasonId(a)}").assertIsFocused()
        rule.runOnIdle { assertEquals(12000L, model.selected!!.positionMs) }
        Espresso.pressBack()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(Page.LIBRARY, model.page) }
        if (tv) rule.onAllNodes(isFocused()).assertCountEquals(1)
    }
}
