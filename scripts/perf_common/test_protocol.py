import copy
import unittest

import protocol


def base_run():
    frames = [
        {"vsyncNs": 10, "intendedVsyncNs": 10, "totalNs": 5_000_000, "deadlineNs": 10_000_000, "firstDraw": True},
        {"vsyncNs": 20, "intendedVsyncNs": 20, "totalNs": 15_000_000, "deadlineNs": 10_000_000, "firstDraw": False},
        {"vsyncNs": 30, "intendedVsyncNs": 30, "totalNs": 8_000_000, "deadlineNs": 10_000_000, "firstDraw": False},
        {"vsyncNs": 40, "intendedVsyncNs": 40, "totalNs": 20_000_000, "deadlineNs": 10_000_000, "firstDraw": False},
    ]
    return {
        "schema": 4, "protocolVersion": 4, "runId": "r1", "sourceSha": "a" * 40,
        "apkSha256": "b" * 64, "buildType": "release", "device": "tv", "renderer": "lavapipe",
        "purpose": "input-causality", "sourceReady": True, "sceneReady": True,
        "requestedTier": "FULL", "actualTier": None,
        "refreshHz": 60.0, "activeWindowNs": 450_000_000,
        "metricCallbackDrops": 0, "truncated": False,
        "windows": [{"startNs": 5, "endNs": 450_000_005}],
        "inputs": [{"id": 1, "receivedNs": 5, "stateAppliedNs": 15, "responseDrawNs": 20,
                    "latencyNs": 15, "responseOutcome": "paired"}],
        "frames": frames,
    }


class ProtocolTest(unittest.TestCase):
    def test_valid_run_summarizes_deadline_jank_and_percentiles(self):
        summary = protocol.summarize(base_run())
        self.assertTrue(summary.valid, summary.errors)
        self.assertEqual(3, summary.frames)
        self.assertEqual(4, summary.raw_frames)
        self.assertAlmostEqual(2 / 3, summary.jank_ratio)
        self.assertAlmostEqual(20_000_000.0, summary.p95_ns)
        self.assertAlmostEqual(20_000_000.0, summary.p99_ns)
        self.assertEqual(1, summary.first_draw_frames)
        self.assertEqual(1, summary.paired_inputs)
        self.assertAlmostEqual(15.0, summary.latency_p95_ns)

    def test_no_frames_never_becomes_evidence(self):
        run = base_run()
        run["frames"] = []
        self.assertIn("no-frames", protocol.summarize(run).errors)

    def test_duplicate_frame_identity_is_deduplicated(self):
        run = base_run()
        run["frames"].append(copy.deepcopy(run["frames"][0]))
        summary = protocol.summarize(run)
        self.assertEqual(4, summary.raw_frames)
        self.assertEqual(1, summary.duplicate_frames)

    def test_callback_drops_truncation_scene_and_source_fail_closed(self):
        cases = {
            "callback-drops": dict(metricCallbackDrops=2),
            "capacity-truncated": dict(truncated=True),
            "scene-not-ready": dict(sceneReady=False),
            "source-not-ready": dict(sourceReady=False),
        }
        for expected, change in cases.items():
            with self.subTest(expected=expected):
                run = base_run()
                run.update(change)
                self.assertIn(expected, protocol.summarize(run).errors)

    def test_tier_direct_requires_a_matching_actual_tier(self):
        run = base_run()
        run["purpose"] = "tier-direct"
        self.assertIn("missing:actualTier", protocol.summarize(run).errors)
        run["actualTier"] = "BLUR"
        self.assertIn("tier-mismatch", protocol.summarize(run).errors)
        run["actualTier"] = "FULL"
        self.assertNotIn("tier-mismatch", protocol.summarize(run).errors)

    def test_input_causality_must_prove_state_and_frame(self):
        cases = {
            "response-no-state": {"stateAppliedNs": None},
            "response-not-a-frame": {"responseDrawNs": 25},
            "response-before-state": {"stateAppliedNs": 35},
            "response-outside-window": {"responseDrawNs": 40, "stateAppliedNs": 15},
        }
        for expected, change in cases.items():
            with self.subTest(expected=expected):
                run = base_run()
                run["inputs"] = [dict(run["inputs"][0], **change)]
                if expected == "response-outside-window":
                    run["windows"] = [{"startNs": 5, "endNs": 6}]
                    run["activeWindowNs"] = 10
                self.assertIn(expected, protocol.summarize(run).errors)

    def test_missing_response_and_empty_window_fail_closed(self):
        run = base_run()
        run["inputs"] = [{"id": 1, "receivedNs": 5}]
        self.assertIn("response-no-state", protocol.summarize(run).errors)
        run = base_run()
        run["windows"] = []
        self.assertIn("no-input-windows", protocol.summarize(run).errors)
        run = base_run()
        run["windows"] = [{"startNs": 7, "endNs": 7}]
        self.assertEqual(1, protocol.summarize(run).empty_windows)

    def test_overlapping_windows_are_rejected(self):
        run = base_run()
        run["windows"] = [{"startNs": 5, "endNs": 500}, {"startNs": 400, "endNs": 900}]
        self.assertIn("window-overlap", protocol.summarize(run).errors)

    def test_old_schema_is_rejected(self):
        run = base_run()
        run["schema"] = 3
        self.assertIn("protocol-version", protocol.summarize(run).errors)

    def test_signed_overrun_is_not_clamped_before_comparison(self):
        run = base_run()
        run["frames"] = [{"vsyncNs": 90, "intendedVsyncNs": 90, "totalNs": 12_000_000,
                          "deadlineNs": 10_000_000, "firstDraw": False}]
        summary = protocol.summarize(run)
        self.assertAlmostEqual(1.0, summary.jank_ratio)


if __name__ == "__main__":
    unittest.main()
