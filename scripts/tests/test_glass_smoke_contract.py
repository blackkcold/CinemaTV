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
        self.assertIn('RuntimeShader(STILL_LENS_AGSL)', source)
        self.assertIn('diagnostics.successfulLensDraws > 0', source)
        self.assertIn('diagnostics.successfulLensDraws > focusedDraws', source)
        self.assertIn('assertEquals(pressedDraws, diagnostics.successfulLensDraws)', source)

    def test_hardware_frame_barrier_is_bounded_and_cleaned_up(self):
        source = (ROOT / 'app/src/androidTest/java/dev/cinematv/GlassMaterialSmokeTest.kt').read_text()
        self.assertIn('decor.isHardwareAccelerated', source)
        self.assertIn('INTENDED_VSYNC_TIMESTAMP', source)
        self.assertIn('completed.await(10, TimeUnit.SECONDS)', source)
        self.assertIn('finally {', source)
        self.assertIn('removeOnFrameMetricsAvailableListener(listener)', source)
        self.assertIn('thread.quitSafely()', source)


if __name__ == '__main__':
    unittest.main()
