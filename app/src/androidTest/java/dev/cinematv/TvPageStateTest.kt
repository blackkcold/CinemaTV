package dev.cinematv

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Page states are product behaviour: a failed request must be drawn as a recoverable error,
 * never as "no content". These drive the production pages through the offline fixture.
 */
@RunWith(AndroidJUnit4::class)
class TvPageStateTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    private val model get() = rule.activity.model

    private fun connect(server: UiFixtureServer) = rule.runOnIdle {
        model.repo.configureMetadata(server.base, "offline-fixture-token")
        model.serverAddress = server.base
        model.connect()
    }

    @Test fun moviesCatalogFailureShowsAnErrorNotAnEmptyCatalog() {
        UiFixtureServer(rule.activity).use { server ->
            server.catalogFails = true
            connect(server)
            rule.waitUntil(20_000) { model.authorized }
            rule.runOnIdle {
                model.refreshCatalog()
                model.navigate(Page.MOVIES)
            }
            rule.runOnIdle { model.loadMoreCatalog(false) }
            rule.waitUntil(20_000) { model.browseState(false).retryableFailure }
            rule.onNodeWithTag("tv-page-movies").assertExists()
            rule.onNodeWithText("电影目录暂时无法载入").assertExists()
            rule.onNodeWithText("重试").assertExists()
        }
    }

    @Test fun liveChannelFailureShowsAnErrorNotAnEmptySubscription() {
        UiFixtureServer(rule.activity).use { server ->
            server.channelsFail = true
            connect(server)
            rule.waitUntil(20_000) { model.liveSources.isNotEmpty() }
            rule.runOnIdle { model.navigate(Page.LIVE) }
            rule.waitUntil(20_000) { model.channelsError.isNotBlank() }
            rule.onNodeWithTag("tv-page-live").assertExists()
            rule.onNodeWithText("频道列表加载失败").assertExists()
            rule.onNodeWithText("重试").assertExists()
        }
    }

    @Test fun liveGuideFailureShowsAnErrorNotAnEmptyGuide() {
        UiFixtureServer(rule.activity).use { server ->
            server.epgFails = true
            connect(server)
            rule.waitUntil(20_000) { model.liveSources.isNotEmpty() }
            rule.runOnIdle { model.navigate(Page.LIVE) }
            rule.waitUntil(20_000) { model.channels.isNotEmpty() }
            rule.runOnIdle { model.focusChannel(model.channels.first()) }
            rule.waitUntil(20_000) { model.epgError.isNotBlank() }
            rule.onNodeWithText("节目单加载失败").assertExists()
            rule.onNodeWithText("重试").assertExists()
        }
    }
}
