package dev.cinematv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.diagnostics.DiagnosticLevel
import dev.cinematv.diagnostics.DiagnosticLog
import dev.cinematv.ui.douban.DiagnosticsLevelRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Device coverage for the dev-only diagnostics panel. Everything here is synthetic: no
 * account, cookie or network request is involved, and the log files are deleted afterwards.
 * A physical Android TV remote walk remains a separate manual gate; passing this class does
 * not certify a retail device.
 */
private const val WAIT_MS = 10_000L

private fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.waitForTag(tag: String) {
    waitUntil(WAIT_MS) {
        onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
    // A dialog can enter the semantics tree before its window is laid out. Scrolling the
    // dialog body is a test affordance only: it reveals the node, it never moves focus, and
    // a node the remote cannot reach still fails the focus assertions below.
    runCatching { onNodeWithTag(tag).performScrollTo() }
    waitUntil(WAIT_MS) {
        try {
            onNodeWithTag(tag).assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }
    }
}

private fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.present(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

private fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.focused(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false)
        .any { it.config.getOrNull(SemanticsProperties.Focused) == true }

private fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.settingsPage() {
    runOnIdle { ViewModelProvider(activity)[CinemaViewModel::class.java].navigate(Page.SETTINGS) }
    waitUntil(WAIT_MS) { !dev.cinematv.data.douban.DoubanDeveloperOptions.get(activity).busy.value }
    waitForTag("developer-options-entry")
}

/** Opens Dev → diagnostics without touching the personal-account experiment. */
private fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.openDiagnostics() {
    settingsPage()
    onNodeWithTag("developer-options-entry").performClick()
    waitForTag("diagnostics-entry")
    onNodeWithTag("diagnostics-entry").performClick()
    waitForTag("diagnostics-status")
}

private fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.closeDiagnostics() {
    // Recover a confirmation left by a failed assertion before closing the panel.
    repeat(3) {
        if (present("diagnostics-confirm")) {
            onNodeWithTag("diagnostics-cancel").performClick()
            waitUntil(WAIT_MS) { !present("diagnostics-confirm") }
        }
    }
    if (present("diagnostics-status")) {
        onNodeWithTag("diagnostics-close").performClick()
        waitUntil(WAIT_MS) { !present("diagnostics-status") }
    }
}

private fun resetLogFiles() {
    DiagnosticLog.setEnabled(false)
    runBlocking { DiagnosticLog.clear() }
}

@RunWith(AndroidJUnit4::class)
class DiagnosticsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun startOff() = resetLogFiles()
    @After fun leaveOff() {
        runCatching { rule.closeDiagnostics() }
        resetLogFiles()
    }

    @Test fun panelTogglesLoggingAndLevelThenWritesToDisk() {
        rule.openDiagnostics()
        rule.onNodeWithTag("diagnostics-toggle").performClick()
        rule.waitUntil(WAIT_MS) { DiagnosticLog.enabled }
        rule.waitUntil(WAIT_MS) {
            try { rule.onNodeWithTag("diagnostics-level-verbose").assertIsEnabled(); true }
            catch (_: AssertionError) { false }
        }
        rule.onNodeWithTag("diagnostics-level-verbose").performClick()
        rule.waitUntil(WAIT_MS) { DiagnosticLog.level == DiagnosticLevel.VERBOSE }
        // The toggle and level events must reach a real file, not just the in-memory queue.
        rule.waitUntil(WAIT_MS) {
            val stats = runBlocking { DiagnosticLog.stats() }
            stats.bytes > 0 && !stats.failed && stats.dropped == 0L
        }
        rule.onNodeWithTag("diagnostics-toggle").performClick()
        rule.waitUntil(WAIT_MS) { !DiagnosticLog.enabled }
        rule.waitUntil(WAIT_MS) { rule.present("diagnostics-close") }
        rule.onNodeWithTag("diagnostics-close").performClick()
        rule.waitUntil(WAIT_MS) { !rule.present("diagnostics-status") }
    }
}

/** Native TV host on a controlled emulator; not physical Android TV certification. */
@RunWith(AndroidJUnit4::class)
class TvDiagnosticsUiTest {
    val rule = createAndroidComposeRule<TvTestActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(TvDisplayOrientationRule()).around(rule)

    @Before fun startOff() = resetLogFiles()
    @After fun leaveOff() {
        runCatching { rule.closeDiagnostics() }
        resetLogFiles()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun remoteTogglesLoggingAndReturnsFocusToTheEntry() {
        rule.settingsPage()
        val developer = rule.onNodeWithTag("developer-options-entry")
        developer.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        developer.assertIsFocused()
        developer.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForTag("diagnostics-entry")

        val entry = rule.onNodeWithTag("diagnostics-entry")
        entry.performScrollTo()
        entry.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        entry.assertIsFocused()
        entry.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForTag("diagnostics-status")

        // Remote focus must land inside the dialog; a lost focus makes the panel unusable
        // with a D-pad even though it renders correctly.
        rule.waitUntil(WAIT_MS) {
            listOf("diagnostics-toggle", "diagnostics-level-basic", "diagnostics-level-verbose",
                "diagnostics-close", "diagnostics-clear-logs").any { rule.focused(it) }
        }

        val toggle = rule.onNodeWithTag("diagnostics-toggle")
        toggle.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        toggle.assertIsFocused()
        toggle.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitUntil(WAIT_MS) { DiagnosticLog.enabled }

        // Back closes only the panel and returns remote focus to the entry that opened it.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(WAIT_MS) { !rule.present("diagnostics-status") }
        rule.waitUntil(WAIT_MS) { rule.focused("diagnostics-entry") }
    }
}

@RunWith(AndroidJUnit4::class)
class TvDiagnosticsConfirmFocusTest {
    val rule = createAndroidComposeRule<TvTestActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(TvDisplayOrientationRule()).around(rule)

    @Before fun startOff() = resetLogFiles()
    @After fun leaveOff() {
        runCatching { rule.closeDiagnostics() }
        resetLogFiles()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun clearingLogsDefaultsFocusToCancelAndKeepsTheLogs() {
        DiagnosticLog.setEnabled(true)
        rule.waitUntil(WAIT_MS) { DiagnosticLog.enabled }
        rule.waitUntil(WAIT_MS) { runBlocking { DiagnosticLog.stats().files } > 0 }
        val before = runBlocking { DiagnosticLog.stats().bytes }

        rule.openDiagnostics()
        val clear = rule.onNodeWithTag("diagnostics-clear-logs")
        rule.waitUntil(WAIT_MS) {
            try { clear.assertIsEnabled(); true } catch (_: AssertionError) { false }
        }
        // The destructive row can sit below the dialog fold on a TV; scrolling is a test
        // affordance, the focus and key delivery below are the real subjects.
        clear.performScrollTo()
        clear.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        clear.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForTag("diagnostics-confirm")

        // A destructive confirmation must never arm itself: Cancel holds the default focus.
        rule.waitUntil(WAIT_MS) { rule.focused("diagnostics-cancel") }
        rule.onNodeWithTag("diagnostics-cancel").assertIsFocused()
        rule.onNodeWithTag("diagnostics-cancel").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitUntil(WAIT_MS) { !rule.present("diagnostics-confirm") }
        assertTrue("Cancel must not delete the logs", runBlocking { DiagnosticLog.stats().bytes } >= before)
        assertTrue(rule.present("diagnostics-status"))
    }
}

/**
 * Layout gate for the narrowest supported phone width with a large system font. This covers
 * the real row composable, not a replica; the surrounding dialog chrome is Material3's.
 */
class DiagnosticsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun threeControlsStayReadableAt360DpAndLargeFont() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1.3f)) {
                MaterialTheme {
                    // 360dp screen minus the dialog's 24dp content padding on each side.
                    Box(Modifier.width(312.dp)) {
                        DiagnosticsLevelRow(
                            tv = false,
                            enabled = true,
                            level = DiagnosticLevel.BASIC,
                            onToggle = {},
                            onSelectLevel = {}
                        )
                    }
                }
            }
        }
        listOf("diagnostics-toggle", "diagnostics-level-basic", "diagnostics-level-verbose")
            .forEach { tag ->
                val node = compose.onNodeWithTag(tag)
                node.assertIsDisplayed()
                node.assertWidthIsAtLeast(58.dp)
                node.assertHeightIsAtLeast(40.dp)
            }
    }
}
