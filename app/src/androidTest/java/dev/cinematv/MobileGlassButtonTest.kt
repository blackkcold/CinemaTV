package dev.cinematv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
