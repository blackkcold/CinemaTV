package dev.cinematv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.tv.components.TvConfirmDialog
import dev.cinematv.ui.tv.components.TvEmptyState
import dev.cinematv.ui.tv.components.TvErrorState
import dev.cinematv.ui.tv.components.TvProgressBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvStatusDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failureIsDistinctFromEmptyAndRetryIsExplicit() {
        val failed = mutableStateOf(false)
        compose.setContent {
            if (failed.value) TvErrorState("加载失败", "网络不可用", onRetry = {}, modifier = Modifier.testTag("error"))
            else TvEmptyState("还没有内容", "连接后重试")
        }
        compose.onNodeWithText("还没有内容").assertIsDisplayed()
        compose.onNodeWithText("加载失败").assertDoesNotExist()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.runOnIdle { failed.value = true }
        compose.onNodeWithText("加载失败").assertIsDisplayed()
        compose.onNodeWithText("重试").assertHasClickAction()
        compose.onNodeWithText("还没有内容").assertDoesNotExist()
    }

    @Test fun confirmDialogDefaultsToCancelAndOneBackReturnsToItsTrigger() {
        val open = mutableStateOf(false)
        val confirmed = AtomicInteger()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                GlassAction("打开", Modifier.testTag("trigger"), onClick = { open.value = true })
                if (open.value) TvConfirmDialog(
                    title = "清空记录",
                    message = "此操作不可撤销",
                    confirmLabel = "清空",
                    onConfirm = { confirmed.incrementAndGet(); open.value = false },
                    onDismiss = { open.value = false },
                )
            }
        }
        compose.onNodeWithTag("trigger").requestFocus()
        compose.onNodeWithTag("trigger").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("清空记录").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("清空记录").assertDoesNotExist()
        assertEquals(0, confirmed.get())
        compose.onNodeWithTag("trigger").assertIsFocused()
    }

    @Test fun progressIsAReadOnlyFourDpRange() {
        compose.setContent { TvProgressBar(0.4f, Modifier.testTag("progress")) }
        compose.onNodeWithTag("progress")
            .assertHeightIsEqualTo(4.dp)
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.4f, 0f..1f))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
