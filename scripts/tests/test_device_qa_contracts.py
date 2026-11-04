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
        self.assertIn('disable-animations: false', device)
        self.assertIn('-gpu lavapipe -feature -Vulkan -no-snapshot -no-boot-anim -memory 2048', device)
        self.assertIn('matrix: ${{ fromJSON(needs.select-devices.outputs.matrix) }}', device)
        self.assertNotIn('emu-crash', device)
        self.assertNotIn('minidump', device)

    def test_media_dependency_is_installed_before_fixture_generation(self):
        workflow = (ROOT / '.github/workflows/tv-smoke.yml').read_text()
        device = workflow.split('\n  instrumented:\n', 1)[1]
        update = device.index('sudo apt-get update')
        install = device.index('sudo apt-get install --no-install-recommends -y ffmpeg')
        generate = device.index('bash scripts/generate-test-media.sh')
        self.assertLess(update, install)
        self.assertLess(install, generate)

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

    def test_search_submits_visible_ime_and_waits_for_hidden_restored_window(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        search = source.split('performTextReplacement(server.title(0))', 1)[1].split('screenshot("08-search")', 1)[0]
        markers = ['?.isVisible(WindowInsetsCompat.Type.ime()) == true',
                   'performImeAction()', '!model.busy && model.results.isNotEmpty()',
                   '!insets.isVisible(WindowInsetsCompat.Type.ime())',
                   'location.contentEquals(intArrayOf(0, 0))',
                   'windowRestored && root.positionInWindow.x == 0f && root.positionInWindow.y == 0f',
                   'rule.onNodeWithContentDescription(server.title(0)).assertIsDisplayed()']
        positions = [search.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(search.count('rule.waitUntil(15000)'), 3)
        for bypass in ('hideSoftInput', 'closeSoftKeyboard', 'model.search()', 'KEYCODE_BACK', 'Type.ime()).hide'):
            self.assertNotIn(bypass, search)
        capture = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvWindowCapture.kt').read_text()
        self.assertIn('Unexpected window offset ${location.contentToString()}', capture)
        self.assertIn('imeVisible=', capture)
        self.assertIn('softInputMode=', capture)


if __name__ == '__main__':
    unittest.main()
