import copy
import unittest

import protocol


def base_run():
    frames = [
        {"vsyncNs": 1, "intendedVsyncNs": 1, "totalNs": 5_000_000, "deadlineNs": 10_000_000, "firstDraw": True},
        {"vsyncNs": 2, "intendedVsyncNs": 2, "totalNs": 15_000_000, "deadlineNs": 10_000_000, "firstDraw": False},
        {"vsyncNs": 3, "intendedVsyncNs": 3, "totalNs": 8_000_000, "deadlineNs": 10_000_000, "firstDraw": False},
        {"vsyncNs": 4, "intendedVsyncNs": 4, "totalNs": 20_000_000, "deadlineNs": 10_000_000, "firstDraw": False},
    ]
    return {
        "schema": 3, "runId": "r1", "sourceSha": "a" * 40, "apkSha256": "b" * 64,
        "buildType": "release", "device": "tv", "renderer": "lavapipe",
        "protocolVersion": 3, "refreshHz": 60.0,
        "requestedTier": "FULL", "actualTier": "FULL", "sceneReady": True,
        "metricCallbackDrops": 0, "truncated": False,
        "windows": [{"startNs": 1, "endNs": 10}],
        "inputs": [{"id": 1, "receivedNs": 1, "responseDrawNs": 2}],
        "frames": frames,
    }


class ProtocolTest(unittest.TestCase):
    def test_valid_run_summarizes_deadline_jank_and_percentiles(self):
        summary = protocol.summarize(base_run())
        self.assertTrue(summary.valid, summary.errors)
        self.assertEqual(4, summary.frames)
        self.assertAlmostEqual(0.5, summary.jank_ratio)
        self.assertAlmostEqual(20_000_000.0, summary.p95_ns)
        self.assertAlmostEqual(20_000_000.0, summary.p99_ns)
        self.assertEqual(1, summary.first_draw_frames)

    def test_no_frames_never_becomes_evidence(self):
        run = base_run()
        run["frames"] = []
        self.assertIn("no-frames", protocol.summarize(run).errors)

    def test_duplicate_frame_identity_is_deduplicated(self):
        run = base_run()
        run["frames"].append(copy.deepcopy(run["frames"][0]))
        summary = protocol.summarize(run)
        self.assertEqual(4, summary.frames)
        self.assertEqual(1, summary.duplicate_frames)

    def test_callback_drops_truncation_scene_and_tier_fail_closed(self):
        cases = {
            "callback-drops": dict(metricCallbackDrops=2),
            "capacity-truncated": dict(truncated=True),
            "scene-not-ready": dict(sceneReady=False),
            "tier-mismatch": dict(actualTier="BLUR"),
        }
        for expected, change in cases.items():
            with self.subTest(expected=expected):
                run = base_run()
                run.update(change)
                self.assertIn(expected, protocol.summarize(run).errors)

    def test_missing_response_and_empty_window_fail_closed(self):
        run = base_run()
        run["inputs"] = [{"id": 1, "receivedNs": 1}]
        self.assertIn("response-timeout", protocol.summarize(run).errors)
        run = base_run()
        run["windows"] = []
        self.assertIn("no-input-windows", protocol.summarize(run).errors)
        run = base_run()
        run["windows"] = [{"startNs": 7, "endNs": 7}]
        self.assertEqual(1, protocol.summarize(run).empty_windows)

    def test_signed_overrun_is_not_clamped_before_comparison(self):
        run = base_run()
        run["frames"] = [{"vsyncNs": 9, "intendedVsyncNs": 9, "totalNs": 12_000_000,
                          "deadlineNs": 10_000_000, "firstDraw": False}]
        summary = protocol.summarize(run)
        self.assertAlmostEqual(1.0, summary.jank_ratio)


if __name__ == "__main__":
    unittest.main()
