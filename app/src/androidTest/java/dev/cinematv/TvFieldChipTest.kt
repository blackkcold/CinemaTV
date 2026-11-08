package dev.cinematv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.ui.tv.components.TvChip
import dev.cinematv.ui.tv.components.TvChipRow
import dev.cinematv.ui.tv.components.TvField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Native remote editing contract: focus stays outer until OK, Back leaves edit mode. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvFieldChipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun field(tag: String) = compose.onNodeWithTag("$tag-shell")
    private fun input(tag: String) = compose.onNodeWithTag("$tag-input")

    @Test fun remoteFocusDoesNotEnterEditModeUntilOk() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                dev.cinematv.ui.tv.components.GlassAction("上方", Modifier.testTag("above"), onClick = {})
                TvField("query", {}, "片名", Modifier, tag = "search")
            }
        }
        compose.onNodeWithTag("above").requestFocus()
        compose.onNodeWithTag("above").performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.DirectionDown)
        }
        compose.waitForIdle()
        field("search").assertIsFocused()
        input("search").assertIsNotFocused()
    }

    @Test fun okEntersEditModeAndBackLeavesWithoutSubmittingOrNavigating() {
        val submits = AtomicInteger()
        compose.setContent {
            TvField("query", {}, "片名", Modifier,
                tag = "search", onSubmit = { submits.incrementAndGet() })
        }
        field("search").requestFocus()
        field("search").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.waitForIdle()
        input("search").assertIsFocused()
        input("search").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Back) }
        compose.waitForIdle()
        field("search").assertIsFocused()
        assertEquals(0, submits.get())
    }

    @Test fun emptySubmitNeverCallsTheAction() {
        val submits = AtomicInteger()
        val value = mutableStateOf("")
        compose.setContent {
            TvField(value.value, { value.value = it }, "片名", Modifier,
                tag = "search", onSubmit = { if (value.value.isNotBlank()) submits.incrementAndGet() })
        }
        field("search").requestFocus()
        field("search").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        input("search").performImeAction()
        compose.waitForIdle()
        assertEquals(0, submits.get())
        compose.runOnIdle { value.value = "星际" }
        input("search").performImeAction()
        compose.waitForIdle()
        assertEquals(1, submits.get())
    }

    @Test fun chipsUseThe36dpChipHeightAnd12dpGap() {
        compose.setContent {
            TvChipRow(contentPadding = PaddingValues(0.dp)) {
                item { TvChip("电影", selected = true, onClick = {}, modifier = Modifier.testTag("chip-a")) }
                item { TvChip("剧集", selected = false, onClick = {}, modifier = Modifier.testTag("chip-b")) }
            }
        }
        val first = compose.onNodeWithTag("chip-a").assertHeightIsEqualTo(36.dp)
            .fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("chip-b").assertHeightIsEqualTo(36.dp)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(with(compose.density) { 12.dp.toPx() }, second.left - first.right, 1f)
    }
}
