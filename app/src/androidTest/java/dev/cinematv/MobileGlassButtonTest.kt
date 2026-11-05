package dev.cinematv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.mobile.MobileGlassButton
import dev.cinematv.ui.mobile.MobileGlassOutlinedButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MobileGlassButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pressedEdgeKeepsStableTouchTargetAndSingleClick() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                MobileGlassButton(onClick = { clicks++ }) { Text("Edge hold") }
            }
        }
        val button = compose.onNodeWithText("Edge hold")
        val before = button.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        button.performTouchInput { down(Offset(center.x, 1f)) }
        compose.mainClock.advanceTimeBy(600)
        button.assertHeightIsAtLeast(48.dp)
        assertEquals(before, button.fetchSemanticsNode().boundsInRoot)
        button.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun primaryAndSecondaryKeepNativeTouchTargetsRolesAndDisabledState() {
        var clicks = 0
        val enabled = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Column {
                    MobileGlassButton(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth(), enabled = enabled.value) {
                        Text("Primary")
                    }
                    MobileGlassOutlinedButton(onClick = { clicks++ }) { Text("Secondary") }
                }
            }
        }
        val primary = compose.onNodeWithText("Primary")
        val secondary = compose.onNodeWithText("Secondary")
        for (button in listOf(primary, secondary)) {
            button.assertHasClickAction().assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertHeightIsAtLeast(48.dp)
            button.performTouchInput { click() }
        }
        compose.runOnIdle { assertEquals(2, clicks); enabled.value = false }
        primary.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, clicks) }
        secondary.performTouchInput { click() }
        compose.runOnIdle { assertEquals(3, clicks) }
    }
}
