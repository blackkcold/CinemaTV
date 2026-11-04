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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileLibraryCatalogControlsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun unconfiguredCatalogNeverRequestsUpstream() = metadataBoundary(rule, rule.activity)
    @Test fun browseAndSearchLoadMore() = catalogControls(rule, rule.activity, false)
    @Test fun partialThenExpiredPage() = catalogControls(rule, rule.activity, false, expired = true)
    @Test fun cleanupCancelBackConfirmAndStatusRemoval() = libraryControls(rule, rule.activity, false)
}

@RunWith(AndroidJUnit4::class)
class TvLibraryCatalogControlsTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    @Test fun unconfiguredCatalogNeverRequestsUpstream() = metadataBoundary(rule, rule.activity)
    @Test fun browseAndSearchLoadMore() = catalogControls(rule, rule.activity, true)
    @Test fun partialThenExpiredPage() = catalogControls(rule, rule.activity, true, expired = true)
    @Test fun cleanupCancelBackConfirmAndStatusRemoval() = libraryControls(rule, rule.activity, true)
}

@OptIn(ExperimentalTestApi::class)
private fun activate(node: SemanticsNodeInteraction, tv: Boolean) {
    if (tv) {
        node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        node.assertIsFocused()
        node.performKeyInput { pressKey(Key.DirectionCenter) }
    } else node.performClick()
}

/** Real screen controls, real VM and repository, only loopback synthetic BFF responses. */
private fun catalogControls(rule: ComposeContentTestRule, activity: ComponentActivity, tv: Boolean, expired: Boolean = false) {
    val model = ViewModelProvider(activity)[CinemaViewModel::class.java]
    UiFixtureServer(activity).use { server ->
        server.pagedCatalog = true
        server.catalogPartial = expired
        rule.runOnIdle {
            model.repo.configureServer(server.base)
            model.repo.configureMetadata(server.base, "offline-fixture-token")
            model.refreshCatalog()
            model.navigate(Page.MOVIES)
        }
        rule.waitUntil(15000) { model.movieBrowse.pages == 1 && model.seriesBrowse.pages == 1 && !model.movieBrowse.loading }
        rule.runOnIdle { assertEquals(2, model.browseMovies.size); assertTrue(model.movieBrowse.canLoadMore) }
        if (expired) {
            rule.onNodeWithText("来源快照尚未完整或为旧缓存", substring = true).assertExists()
            server.catalogExpired = true
        }
        activate(rule.onNodeWithText("加载更多"), tv)
        if (expired) {
            rule.waitUntil(15000) { model.movieBrowse.restartRequired }
            rule.runOnIdle {
                assertTrue(model.movieBrowse.items.isEmpty())
                assertFalse(model.movieBrowse.canLoadMore)
            }
            rule.onNodeWithText("目录授权已过期或无效", substring = true).assertExists()
        } else {
            rule.waitUntil(15000) { model.movieBrowse.pages == 2 && !model.movieBrowse.loading }
            rule.runOnIdle { assertEquals(4, model.browseMovies.size); assertFalse(model.movieBrowse.canLoadMore) }
            if (tv) rule.onNodeWithText("本次加载结束").assertIsFocused()
            rule.runOnIdle { model.navigate(Page.SEARCH); model.query = "Paging fixture"; model.search() }
            rule.waitUntil(15000) { !model.busy && model.movieSearchBrowse.pages == 1 && model.seriesSearchBrowse.pages == 1 }
            val before = model.results.size
            activate(rule.onNodeWithText("加载更多目录"), tv)
            rule.waitUntil(15000) { !model.searchCatalogLoading && model.movieSearchBrowse.pages == 2 && model.seriesSearchBrowse.pages == 2 }
            rule.runOnIdle { assertTrue(model.results.size > before); assertFalse(model.canLoadSearchCatalog) }
            if (tv) rule.onNodeWithText("本次加载结束").assertIsFocused()
            assertTrue(server.catalogRequests.any { it.contains("offset=2") && it.contains("query=Paging") })
        }
        rule.runOnIdle { model.repo.configureMetadata("", "") }
    }
}

private fun libraryControls(rule: ComposeContentTestRule, activity: ComponentActivity, tv: Boolean) {
    val model = ViewModelProvider(activity)[CinemaViewModel::class.java]
    UiFixtureServer(activity).use { server ->
        val item = MediaItem("cleanup-${server.base}", "Cleanup fixture", isTv = true, seasonNumber = 1,
            source = TvSource("cleanup", "Offline fixture", "${server.base}/provider"),
            episodes = listOf("${server.base}/stream.mp4?episode=0", "${server.base}/stream.mp4?episode=1"),
            playlistKnown = true, poster = server.poster, backdrop = server.backdrop(0))
        server.registerDetail(item)
        runBlocking {
            model.repo.library.initialize()
            model.repo.library.cacheItem(item)
            model.repo.library.saveProgress(item, 12000, 0, 90000)
            model.repo.library.saveProgress(item, 23000, 1, 90000)
            model.repo.setLibraryStatus(item, LibraryStatus.WATCHING)
            if (!model.repo.isFavorite(item)) model.repo.toggleFavorite(item)
        }
        rule.runOnIdle { model.repo.configureServer(server.base); model.navigate(Page.LIBRARY) }
        val trigger = if (tv) "清空全部观看历史" else "清空全部观看记录"
        val title = "$trigger？"
        fun openConfirmation() {
            activate(rule.onNodeWithText(trigger).performScrollTo(), tv)
            rule.onNodeWithText(title).assertExists()
            if (tv) rule.onNodeWithText("取消").assertIsFocused()
        }
        fun assertPreserved() = rule.runOnIdle {
            assertEquals(12000L, model.repo.library.snapshot.progress(item, 0)!!.positionMs)
            assertEquals(23000L, model.repo.library.snapshot.progress(item, 1)!!.positionMs)
            assertTrue(model.repo.isFavorite(item))
            assertEquals(LibraryStatus.WATCHING, model.repo.libraryStatus(item))
        }
        openConfirmation()
        activate(rule.onNodeWithText("取消"), tv)
        rule.onNodeWithText(title).assertDoesNotExist()
        assertPreserved()
        if (tv) rule.onNodeWithText(trigger).assertIsFocused()
        openConfirmation()
        // The dialog has a separate Android window; target it rather than the unfocused Activity.
        Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.pressBack())
        rule.waitForIdle()
        rule.onNodeWithText(title).assertDoesNotExist()
        assertPreserved()
        if (tv) rule.onNodeWithText(trigger).assertIsFocused()

        // Removing a status must preserve the independent favorite and BOTH episode checkpoints.
        rule.runOnIdle { model.open(item) }
        rule.waitUntil(15000) { !model.busy && model.selected?.id == item.id }
        if (tv) activate(rule.onNodeWithText("片库状态").performScrollTo(), true)
        val remove = rule.onNodeWithText(if (tv) "移除片库状态" else "移除片库状态（保留收藏和进度）")
        activate(if (tv) remove else remove.performScrollTo(), tv)
        rule.waitUntil(15000) { !model.hasLibraryStatus(item) }
        rule.runOnIdle {
            assertTrue(model.repo.isFavorite(item))
            assertEquals(12000L, model.repo.library.snapshot.progress(item, 0)!!.positionMs)
            assertEquals(23000L, model.repo.library.snapshot.progress(item, 1)!!.positionMs)
        }
        // Restore status before clear to independently verify clear preserves it.
        runBlocking { model.repo.setLibraryStatus(item, LibraryStatus.WATCHED) }
        Espresso.pressBack()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(Page.LIBRARY, model.page) }
        openConfirmation()
        activate(rule.onNodeWithText(if (tv) "永久清空" else "确认清空"), tv)
        rule.waitUntil(15000) { model.history().isEmpty() }
        rule.runOnIdle {
            assertNull(model.repo.library.snapshot.progress(item, 0))
            assertNull(model.repo.library.snapshot.progress(item, 1))
            assertTrue(model.repo.isFavorite(item))
            assertEquals(LibraryStatus.WATCHED, model.repo.libraryStatus(item))
        }
        rule.onNodeWithText(trigger).assertDoesNotExist()
        if (tv) rule.onNodeWithText("全部").assertIsFocused()
    }
}

private fun metadataBoundary(rule: ComposeContentTestRule, activity: ComponentActivity) {
    val model = ViewModelProvider(activity)[CinemaViewModel::class.java]
    UiFixtureServer(activity).use { server ->
        rule.runOnIdle {
            model.repo.configureServer(server.base)
            model.repo.configureMetadata("", "")
            model.refreshCatalog()
            model.navigate(Page.MOVIES)
        }
        rule.waitUntil(15000) { model.movieBrowse.nextOffset == null && model.seriesBrowse.nextOffset == null }
        assertFalse(server.requests.any { it.startsWith("/api/douban") })
        val requestsBefore = server.requests.toList()
        runBlocking { model.repo.recommend() }
        assertEquals("No configured metadata catalog must never trigger an upstream request", requestsBefore, server.requests.toList())
        server.homeCatalog = true
        rule.runOnIdle { model.repo.configureMetadata(server.base, "offline-fixture-token") }
        val items = runBlocking { model.repo.recommend() }
        assertEquals(24, items.size)
        assertTrue(items.all { it.ratingSource == "Synthetic" && it.catalogProvider == "synthetic" })
        assertTrue(server.requests.any { it.startsWith("/v1/catalog?") })
        assertFalse(server.requests.any { it.startsWith("/api/douban") })
        rule.runOnIdle { model.repo.configureMetadata("", "") }
    }
}
