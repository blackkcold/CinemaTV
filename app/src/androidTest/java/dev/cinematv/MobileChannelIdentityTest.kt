package dev.cinematv

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileChannelIdentityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[CinemaViewModel::class.java]
    private lateinit var server: UiFixtureServer

    @After fun cleanFixture() {
        if (::server.isInitialized) server.close()
        rule.activity.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
    }

    @Test fun sameEpgIdWithDifferentStreamsRendersAndSelectsEachVariant() {
        server = UiFixtureServer(InstrumentationRegistry.getInstrumentation().context).apply { duplicateLiveIds = true }
        rule.runOnIdle {
            model.serverAddress = server.base
            model.connect()
        }
        rule.waitUntil(15_000) { model.authorized && model.liveSources.isNotEmpty() }
        rule.runOnIdle {
            model.navigate(Page.LIVE)
            model.loadChannels(model.liveSources.first())
        }
        rule.waitUntil(15_000) { model.channels.size == 18 && !model.busy }
        val hd = model.channels[0]
        val sd = model.channels[1]
        assertEquals(hd.id, sd.id)
        assertNotEquals(hd.url, sd.url)
        val hdDescription = "选择频道 ${hd.name}"
        val sdDescription = "选择频道 ${sd.name}"
        rule.onNodeWithContentDescription(hdDescription).performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithContentDescription(sdDescription).performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
        rule.onNodeWithContentDescription(hdDescription).performScrollTo().assertIsNotSelected()
        rule.runOnIdle { assertEquals(sd.url, model.activeChannel?.url) }
        rule.onNodeWithContentDescription(hdDescription).performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(hd.url, model.activeChannel?.url) }
    }
}
