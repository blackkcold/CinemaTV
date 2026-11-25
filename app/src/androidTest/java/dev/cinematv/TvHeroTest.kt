package dev.cinematv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.ui.tv.components.TvHero
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvHeroTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun singleItemHasNoPagerControls() {
        compose.setContent {
            TvHero(1, Modifier.height(240.dp)) { page ->
                Box(Modifier.fillMaxWidth().height(240.dp).testTag("page-$page"))
            }
        }
        compose.onNodeWithTag("page-0").assertIsDisplayed()
        compose.onNodeWithTag("hero-page-prev").assertDoesNotExist()
        compose.onNodeWithTag("hero-page-next").assertDoesNotExist()
        compose.onNodeWithTag("hero-page-dot-0").assertDoesNotExist()
    }

    @Test fun manualNextMovesToTheFollowingHeroAndDisablesAtTheEnd() {
        compose.setContent {
            TvHero(2, Modifier.height(240.dp)) { page ->
                Box(Modifier.fillMaxWidth().height(240.dp).testTag("page-$page"))
            }
        }
        compose.onNodeWithTag("hero-page-prev").assertIsNotEnabled()
        compose.onNodeWithTag("hero-page-next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("page-1").assertIsDisplayed()
        compose.onNodeWithTag("hero-page-next").assertIsNotEnabled()
        compose.onNodeWithTag("hero-page-prev").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("page-0").assertIsDisplayed()
    }
}
