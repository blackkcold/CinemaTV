#!/usr/bin/env python3
"""Host-only guardrails for the deliberately small emulator stabilization change."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DeviceQaContractTest(unittest.TestCase):
    def test_single_viewport_diagnostic_is_pr_only_and_release_stays_gated(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        qa = workflow.split('\n  ui-qa:\n', 1)[1].split('\n  preview:\n', 1)[0]
        self.assertIn("github.event_name == 'pull_request' && contains(github.event.pull_request.title, '[ui-tv4k]') && 'tv4k' || 'all'", qa)
        self.assertIn("needs: [apk, ui-qa]", workflow)
        release = workflow.split('\n  preview:\n', 1)[1]
        self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/main'", release)
        self.assertNotIn('pull_request', release)

    def test_only_device_job_pins_os_and_keeps_renderer_contract(self):
        workflow = (ROOT / '.github/workflows/tv-smoke.yml').read_text()
        selector, device = workflow.split('\n  instrumented:\n', 1)
        self.assertIn('runs-on: ubuntu-latest', selector)
        self.assertIn('runs-on: ubuntu-24.04', device)
        self.assertNotIn('runs-on: ubuntu-latest', device)
        self.assertIn("emulator-build: '15004761'", device)
        self.assertIn('api-level: ${{ matrix.api }}', device)
        self.assertIn('target: ${{ matrix.target }}', device)
        self.assertIn('disable-animations: false', device)
        self.assertIn('-gpu lavapipe -feature -Vulkan -no-snapshot -no-boot-anim -memory 2048', device)
        self.assertIn('matrix: ${{ fromJSON(needs.select-devices.outputs.matrix) }}', device)
        self.assertNotIn('emu-crash', device)
        self.assertNotIn('minidump', device)

    def test_committed_media_is_validated_before_compile_without_tool_downloads(self):
        workflow = (ROOT / '.github/workflows/tv-smoke.yml').read_text()
        device = workflow.split('\n  instrumented:\n', 1)[1]
        validate = device.index('bash scripts/generate-test-media.sh')
        compile_apks = device.index(':app:assembleDebug :app:assembleDebugAndroidTest')
        self.assertLess(validate, compile_apks)
        self.assertNotIn('apt-get', device)
        self.assertNotIn('--regenerate', device)
        self.assertNotIn('download-artifact', device)
        self.assertIn('uses: actions/checkout@v4', device)

    def test_capture_markers_bracket_readback_and_png_before_recycling(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        helper = source.split('private fun screenshot(', 1)[1].split('@Before', 1)[0]
        markers = [
            'capture-begin=$name',
            'val bitmap = captureWindow()',
            'readback-complete=$name',
            'bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)',
            'PNG-complete=$name',
            'bitmap.recycle()',
        ]
        positions = [helper.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        self.assertRegex(helper, r'finally\s*\{(?:\s*//[^\n]*)?\s*bitmap\.recycle\(\)\s*\}')
        self.assertRegex(source, r'finally\s*\{\s*viewport\.recycle\(\)\s*\}')
        # Keep all original evidence views, including the separate final capture.
        self.assertEqual(re.findall(r'screenshot\("(\d{2}-[^"\n]+)"', source), [
            '99-final-state', '01-home', '02-focused-rail', '03-detail', '04-restored-rail',
            '05-movies', '06-series', '07-discovery', '08-search', '09-live',
            '10-library', '11-settings', '12-effects', '13-player', '14-player-panel',
        ])

    def test_capture_requires_fresh_focused_full_resolution_display(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvWindowCapture.kt').read_text()
        test = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        self.assertNotIn('.captureToImage()', test)
        self.assertIn('root.positionInWindow.x == 0f && root.positionInWindow.y == 0f', test)
        self.assertIn('root.size.width, root.size.height', test)
        for contract in ('activity.hasWindowFocus()', 'automation.rootInActiveWindow',
                         'activeRoot.packageName?.toString() == activity.packageName',
                         'FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= requestedAt',
                         'drawn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)',
                         'automation.takeScreenshot()', 'future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)',
                         'bitmap.width == rootWidth && bitmap.height == rootHeight',
                         'if (!transferred) captured?.recycle()', 'executor.shutdownNow()'):
            self.assertIn(contract, source)
        self.assertIn('TIMEOUT_SECONDS = 10L', source)
        self.assertNotIn('createScaledBitmap', source)
        self.assertIn('check(compressed)', test)

    def test_fresh_frame_request_invalidates_compose_draw_host_after_arming(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvWindowCapture.kt').read_text()
        markers = ['activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))',
                   'activity.findViewById<ViewGroup>(android.R.id.content)',
                   'content.getChildAt(0) as? ComposeView',
                   'val drawHost = composeView.getChildAt(0)',
                   'check(drawHost.isAttachedToWindow && drawHost.isShown && drawHost.isHardwareAccelerated)',
                   'drawHost.postInvalidateOnAnimation()',
                   'drawn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)']
        positions = [source.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        self.assertNotIn('decorView.postInvalidateOnAnimation()', source)
        self.assertIn('FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= requestedAt', source)

    def test_initial_accessibility_root_wait_is_bounded_and_never_bypasses_identity(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvWindowCapture.kt').read_text()
        self.assertIn('fun checkForeground(waitForRoot: Boolean = false)', source)
        self.assertIn('SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)', source)
        self.assertIn('if (!waitForRoot || SystemClock.uptimeMillis() >= deadline) break', source)
        self.assertIn('SystemClock.sleep(100)', source)
        self.assertIn('activeRoot != null && activeRoot.packageName?.toString() == activity.packageName', source)
        self.assertIn('activeRoot?.recycle()', source)
        self.assertIn('error("Expected active accessibility window', source)
        self.assertEqual(source.count('checkForeground(waitForRoot = true)'), 1)
        self.assertEqual(source.count('        checkForeground()'), 2)
        self.assertLess(source.index('checkForeground(waitForRoot = true)'), source.index('val drawn = CountDownLatch(1)'))

    def test_search_submits_visible_ime_and_waits_for_hidden_restored_window(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        search = source.split('performTextReplacement(server.title(0))', 1)[1].split('screenshot("08-search")', 1)[0]
        markers = ['?.isVisible(WindowInsetsCompat.Type.ime()) == true',
                   'performImeAction()', '!model.busy && model.results.isNotEmpty()',
                   'awaitHiddenImeAndFullScreen()',
                   'rule.onNodeWithContentDescription(server.title(0)).assertIsDisplayed()']
        positions = [search.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(search.count('rule.waitUntil(15000)'), 2)
        helper = source.split('private fun awaitHiddenImeAndFullScreen()', 1)[1].split('private fun captureWindow()', 1)[0]
        for marker in ('rule.waitUntil(15000)', '!insets.isVisible(WindowInsetsCompat.Type.ime())',
                       'location.contentEquals(intArrayOf(0, 0))',
                       'windowRestored && root.positionInWindow.x == 0f && root.positionInWindow.y == 0f'):
            self.assertIn(marker, helper)
        self.assertNotIn('.hide()', helper)
        for bypass in ('hideSoftInput', 'closeSoftKeyboard', 'model.search()', 'KEYCODE_BACK', 'Type.ime()).hide'):
            self.assertNotIn(bypass, search)
        capture = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvWindowCapture.kt').read_text()
        self.assertIn('Unexpected window offset ${location.contentToString()}', capture)
        self.assertIn('imeVisible=', capture)
        self.assertIn('softInputMode=', capture)

    def test_tv_ime_owner_handles_late_show_without_stealing_destination_focus(self):
        root = (ROOT / 'app/src/main/java/dev/cinematv/ui/tv/TvRootApp.kt').read_text()
        policy = (ROOT / 'app/src/main/java/dev/cinematv/ui/tv/TvImePolicy.kt').read_text()
        self.assertIn('val imeVisible = WindowInsets.isImeVisible', root)
        self.assertIn('LaunchedEffect(model.page, imeVisible)', root)
        self.assertIn('if (shouldHideTvIme(model.page, imeVisible)) keyboard?.hide()', root)
        self.assertIn('imeVisible && page != Page.SEARCH && page != Page.SETTINGS', policy)
        self.assertNotIn('clearFocus(', root)
        test = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        exit_search = test.split('assertEquals(Page.SEARCH, model.page)', 1)[1].split('screenshot("09-live")', 1)[0]
        markers = ['rule.onNode(hasSetTextAction()).assertIsFocused()', 'model.navigate(Page.LIVE)',
                   'awaitHiddenImeAndFullScreen()', 'assertEquals(Page.LIVE, model.page)',
                   'rule.onNode(hasSetTextAction()).assertDoesNotExist()', 'key(KeyEvent.KEYCODE_DPAD_DOWN)']
        positions = [exit_search.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        self.assertNotIn('.hide()', exit_search)

    def test_visible_search_ime_exit_preserves_live_channel_focus(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        exit_visible = source.split('// Also cover a fully visible keyboard exit', 1)[1].split('search-visible-ime-exit-complete', 1)[0]
        markers = ['model.navigate(Page.SEARCH)', 'rule.onNode(hasSetTextAction()).assertIsFocused()',
                   '?.isVisible(WindowInsetsCompat.Type.ime()) == true', 'model.navigate(Page.LIVE)',
                   'awaitHiddenImeAndFullScreen()',
                   'rule.onNode(hasText(model.channels.first().name) and hasClickAction()).assertIsFocused()',
                   'key(KeyEvent.KEYCODE_DPAD_DOWN)',
                   'rule.onNode(hasText(model.channels[1].name) and hasClickAction()).assertIsFocused()']
        positions = [exit_visible.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        for bypass in ('.hide()', 'clearFocus(', 'closeSoftKeyboard'):
            self.assertNotIn(bypass, exit_visible)


if __name__ == '__main__':
    unittest.main()
