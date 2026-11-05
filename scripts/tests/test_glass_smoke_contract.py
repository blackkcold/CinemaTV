#!/usr/bin/env python3
"""Fast source guardrails; not a replacement for Android shader runtime tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class GlassSmokeContractTest(unittest.TestCase):
    def test_runtime_smoke_has_no_gpu_readback(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/GlassMaterialSmokeTest.kt').read_text()
        for forbidden in ('captureToImage(', 'PixelCopy.request(', 'takeScreenshot(', 'forceRedraw('):
            self.assertNotIn(forbidden, source)
        self.assertIn('rememberProductionGlassScene(hasScene.value)', source)
        self.assertIn('recordProductionGlass(scene)', source)
        self.assertIn('failure.fail("test"', source)
        self.assertIn('diagnostics.successfulLensDraws > 0', source)
        self.assertIn('diagnostics.successfulLensDraws > beforePress', source)
        self.assertIn('diagnostics.successfulLensDraws > beforeReduced', source)
        self.assertIn('diagnostics.blurDraws > 0', source)
        self.assertIn('assertEquals(beforeFailure, diagnostics.successfulLensDraws)', source)

    def test_hardware_frame_barrier_is_bounded_and_cleaned_up(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/GlassMaterialSmokeTest.kt').read_text()
        self.assertIn('decor.isHardwareAccelerated', source)
        self.assertIn('INTENDED_VSYNC_TIMESTAMP', source)
        self.assertIn('completed.await(10, TimeUnit.SECONDS)', source)
        self.assertIn('activity.hasWindowFocus()', source)
        self.assertIn('materialDraws.get() > previousDraws', source)
        self.assertIn('finally {', source)
        self.assertIn('removeOnFrameMetricsAvailableListener(listener)', source)
        self.assertIn('thread.quitSafely()', source)

    def test_listener_is_armed_before_mount_and_every_measured_mutation(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/GlassMaterialSmokeTest.kt').read_text()
        helper = source.split('private fun renderStage', 1)[1].split('@Test fun materialSurvives', 1)[0]
        self.assertLess(helper.index('addOnFrameMetricsAvailableListener'), helper.index('mutation()'))
        self.assertLess(helper.index('mutation()'), helper.index('compose.waitForIdle()'))
        self.assertNotIn('postInvalidateOnAnimation()', helper)
        self.assertLess(source.index('render("initial")'), source.index('compose.setContent'))
        for stage in ('focused', 'pressed', 'reduced-motion', 'missing-art', 'latched-failure', 'unfocused'):
            self.assertIn('render("' + stage + '")', source)
        self.assertIn('drawContent()', source)
        self.assertIn('materialDraws.incrementAndGet()', source)


if __name__ == '__main__':
    unittest.main()
