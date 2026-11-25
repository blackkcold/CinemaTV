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

    def test_api26_native_capture_uses_real_landscape_framebuffer(self):
        workflow = (ROOT / '.github/workflows/tv-smoke.yml').read_text()
        native = (ROOT / '.github/workflows/production-glass.yml').read_text()
        condition = "${{ matrix.api == 26 && '-skin 1920x1080' || '' }}"
        self.assertIn(condition, workflow)
        self.assertIn(condition, native)
        capture = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvWindowCapture.kt').read_text()
        self.assertIn('automation.takeScreenshot()', capture)
        self.assertIn('bitmap.width == rootWidth && bitmap.height == rootHeight', capture)
        self.assertIn('refusing scaling/cropping', capture)

    def test_rail_configuration_fixture_does_not_remount_a_focused_root(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/ContentRailVisibilityTest.kt').read_text()
        host = (ROOT / 'app/src/debug/java/dev/cinematv/TvTestActivity.kt').read_text()
        self.assertIn('RuleChain.outerRule(cleanSettingsBeforeActivity).around(rule)', source)
        self.assertIn('settings.edit().clear().commit()', source)
        self.assertIn('Page.SETTINGS, model.page', source)
        self.assertNotIn('setContent', source)
        self.assertNotIn('requestFocus(', source)
        method = source.split('@Test fun oversizedFontScaleRailSettlesWithoutRepeatedVerticalScroll()', 1)[1]
        self.assertLess(method.index('nativeKey(KeyEvent.KEYCODE_DPAD_DOWN)'), method.index('testFontScaleOverride = 12f'))
        self.assertIn('bookmarkBeforeResize, model.focusCoordinator.last("HOME")', method)
        self.assertIn('mutableStateOf<Float?>(null)', host)
        self.assertIn('Density(platformDensity.density, it) } ?: platformDensity', host)
        self.assertEqual(1, host.count('setContent {'))

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

    def test_remote_trace_retains_synchronized_observation_without_duplicate_idle(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        key = source.split('private fun key(', 1)[1].split('private fun assertFullyVisible(', 1)[0]
        self.assertIn('target.performKeyInput { pressKey(composeKey) }', key)
        self.assertIn('rule.onAllNodes(isFocused()).fetchSemanticsNodes()', key)
        self.assertIn('File(directory, "focus-trace.txt").appendText(', key)
        self.assertLess(key.index('target.performKeyInput'), key.index('val trace ='))
        self.assertLess(key.index('val trace ='), key.index('.appendText('))
        self.assertNotIn('rule.waitForIdle()', key)
        # Back still targets the owning window, including modal dialogs.
        self.assertIn('RootMatchers.isDialog()', key)
        self.assertIn('ViewActions.pressBack()', key)
        self.assertIn('Espresso.pressBack()', key)

    def test_capture_settles_fonts_with_one_synchronized_root_fetch(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        fonts = source.split('private fun awaitBundledFonts()', 1)[1].split('private fun awaitPlatformImeVisible(', 1)[0]
        capture = source.split('private fun captureWindow()', 1)[1].split('private fun screenshot(', 1)[0]
        self.assertIn('rule.waitUntil(15000)', fonts)
        self.assertIn('assertTrue("Bundled Noto failed:', fonts)
        self.assertNotIn('rule.waitForIdle()', fonts + capture)
        markers = ['awaitBundledFonts()', 'rule.onRoot().fetchSemanticsNode()', 'TvWindowCapture.capture(']
        positions = [capture.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(capture.count('fetchSemanticsNode()'), 1)

    def test_frame_sample_keeps_all_remote_events_and_closes_listener_on_failure(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        sample = source.split('private fun recordFrameMetrics()', 1)[1]
        self.assertEqual(sample.count('repeat(8)'), 1)
        loop = sample.split('repeat(8) {', 1)[1].split('}', 1)[0]
        self.assertEqual(re.findall(r'key\(KeyEvent\.(\w+)\)', loop), [
            'KEYCODE_DPAD_RIGHT', 'KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_LEFT', 'KEYCODE_DPAD_UP'])
        self.assertIn('rule.runOnUiThread { rule.activity.window.addOnFrameMetricsAvailableListener(', sample)
        self.assertIn('rule.runOnUiThread { rule.activity.window.removeOnFrameMetricsAvailableListener(listener) }', sample)
        self.assertRegex(sample, r'finally\s*\{\s*try\s*\{\s*rule.runOnUiThread')
        self.assertRegex(sample, r'finally\s*\{\s*thread.quitSafely\(\); thread.join\(2000\)')
        self.assertNotIn('rule.runOnIdle', sample)
        self.assertIn('FrameMetrics.TOTAL_DURATION', sample)
        self.assertIn('assertTrue("Frame evidence must contain rendered frames", values.isNotEmpty())', sample)
        self.assertIn('software renderer; see emulator launch log for actual GPU', sample)
        self.assertNotIn('Lavapipe', sample)

    def test_capture_requires_fresh_focused_full_resolution_display(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvWindowCapture.kt').read_text()
        test = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        self.assertNotIn('.captureToImage()', test)
        self.assertIn('root.positionInWindow.x == 0f && root.positionInWindow.y == 0f', test)
        self.assertIn('root.size.width, root.size.height', test)
        for contract in ('activity.hasWindowFocus()', 'automation.rootInActiveWindow',
                         'activeRoot.packageName?.toString() == activity.packageName',
                         'FrameMetrics.VSYNC_TIMESTAMP',
                         'fence > 0 && drawPasses.get() > 0 && actualVsync >= fence',
                         'drawn.await(remaining, TimeUnit.MILLISECONDS)',
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
                   'drawHost = composeView.getChildAt(0)',
                   'check(drawHost.isAttachedToWindow && drawHost.isShown && drawHost.isHardwareAccelerated)',
                   'observer = drawHost.viewTreeObserver.also { it.addOnDrawListener(drawListener) }',
                   'Choreographer.getInstance().also { it.postFrameCallback(requestFrame) }',
                   'drawn.await(firstWait, TimeUnit.MILLISECONDS)']
        positions = [source.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        request = source.split('val requestFrame = Choreographer.FrameCallback { frameTimeNanos ->', 1)[1].split('val drawListener', 1)[0]
        self.assertLess(request.index('requestedFrame.compareAndSet(-1, frameTimeNanos)'), request.index('drawHost.invalidate()'))
        self.assertNotIn('postInvalidateOnAnimation()', source)
        self.assertNotIn('val requestedAt = System.nanoTime()', source)

    def test_frame_completion_rejects_unarmed_stale_and_draw_only_evidence(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvWindowCapture.kt').read_text()
        draw = source.split('val drawListener =', 1)[1].split('val listener =', 1)[0]
        listener = source.split('val listener = Window.OnFrameMetricsAvailableListener', 1)[1].split('fun frameState()', 1)[0]
        self.assertIn('if (requestedFrame.get() > 0) drawPasses.incrementAndGet()', draw)
        self.assertNotIn('drawn.countDown()', draw)
        self.assertIn('metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)', listener)
        self.assertIn('fence > 0 && drawPasses.get() > 0 && actualVsync >= fence', listener)
        self.assertEqual(source.count('drawn.countDown()'), 1)
        self.assertIn('droppedCallbacks.addAndGet(dropped)', listener)
        for diagnostic in ('requestedFrame=', 'lastVsync=', 'lastIntendedVsync=', 'callbacks=', 'drawPasses=', 'requests='):
            self.assertIn(diagnostic, source)

    def test_frame_retry_preserves_first_fence_deadline_and_cleans_up(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvWindowCapture.kt').read_text()
        self.assertEqual(source.count('val frameDeadline ='), 1)
        self.assertIn('val remaining = (frameDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)', source)
        self.assertIn('drawn.await(remaining, TimeUnit.MILLISECONDS)', source)
        self.assertIn('!drawn.await(firstWait, TimeUnit.MILLISECONDS) && SystemClock.uptimeMillis() < frameDeadline', source)
        self.assertEqual(source.count('postFrameCallback(requestFrame)'), 2)
        self.assertIn('requestedFrame.compareAndSet(-1, frameTimeNanos)', source)
        self.assertNotIn('requestedFrame.set(', source)
        for cleanup in ('removeFrameCallback(requestFrame)', 'removeOnDrawListener(drawListener)',
                        'removeOnFrameMetricsAvailableListener(listener)', 'thread.quitSafely()', 'thread.join(2000)'):
            self.assertIn(cleanup, source)
        self.assertIn('TIMEOUT_SECONDS = 10L', source)

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
        markers = ['rule.onNode(hasSetTextAction()).assertIsFocused()',
                   'awaitPlatformImeVisible("search-submit")',
                   'performImeAction()', '!model.busy && model.results.isNotEmpty()',
                   'awaitHiddenImeAndFullScreen()',
                   'rule.onNodeWithContentDescription(server.title(0)).assertIsDisplayed()']
        positions = [search.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(search.count('rule.waitUntil(15000)'), 1)
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

    def test_visible_ime_wait_observes_platform_without_pumping_compose(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        helper = source.split('private fun awaitPlatformImeVisible(', 1)[1].split('private fun awaitHiddenImeAndFullScreen()', 1)[0]
        for contract in ('val deadline = startedAt + 15000L', 'rule.runOnUiThread {',
                         'ViewCompat.getRootWindowInsets(decor)',
                         'insets?.isVisible(WindowInsetsCompat.Type.ime()) == true',
                         'activity.hasWindowFocus() && visible', 'ready && now <= deadline',
                         'SystemClock.sleep(minOf(50L, deadline - now))',
                         'throw AssertionError(diagnostic)', 'ime-wait-failure.txt',
                         'hardKeyboardHidden=', 'show_ime_with_hard_keyboard'):
            self.assertIn(contract, helper)
        # Comments document why the old polling pattern was removed. Executable
        # code may only observe platform state, never drive rendering or the IME.
        code = '\n'.join(line for line in helper.splitlines() if not line.strip().startswith('//'))
        for bypass in ('runOnIdle', 'waitForIdle', 'waitUntil', 'mainClock', 'advanceTime',
                       '.show()', '.hide()', 'requestFocus(', 'clearFocus(', 'Settings.Secure.put'):
            self.assertNotIn(bypass, code)
        self.assertEqual(source.count('awaitPlatformImeVisible("search-submit")'), 1)
        self.assertEqual(source.count('awaitPlatformImeVisible("search-exit")'), 1)

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
        markers = ['rule.onNodeWithTag("search-field-shell").assertIsFocused()', 'model.navigate(Page.LIVE)',
                   'awaitHiddenImeAndFullScreen()', 'assertEquals(Page.LIVE, model.page)',
                   'rule.onNode(hasSetTextAction()).assertDoesNotExist()', 'key(KeyEvent.KEYCODE_DPAD_DOWN)']
        positions = [exit_search.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        self.assertNotIn('.hide()', exit_search)

    def test_visible_search_ime_exit_preserves_live_channel_focus(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        exit_visible = source.split('// Also cover a fully visible keyboard exit', 1)[1].split('search-visible-ime-exit-complete', 1)[0]
        markers = ['model.navigate(Page.SEARCH)',
                   'rule.onNodeWithTag("search-field-shell").assertIsFocused()',
                   'key(KeyEvent.KEYCODE_DPAD_CENTER)',
                   'rule.onNode(hasSetTextAction()).assertIsFocused()',
                   'awaitPlatformImeVisible("search-exit")', 'model.navigate(Page.LIVE)',
                   'awaitHiddenImeAndFullScreen()', 'assertEquals(Page.LIVE, model.page)',
                   'rule.onNode(hasText(model.channels[1].name) and hasClickAction()).assertIsFocused()',
                   'key(KeyEvent.KEYCODE_DPAD_DOWN)',
                   'rule.onNode(hasText(model.channels[2].name) and hasClickAction()).assertIsFocused()']
        positions = [exit_visible.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        for bypass in ('.hide()', 'clearFocus(', 'closeSoftKeyboard'):
            self.assertNotIn(bypass, exit_visible)


if __name__ == '__main__':
    unittest.main()
