import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run_document(frames):
    return {
        "schema": 4, "protocolVersion": 4, "runId": "r", "sourceSha": "a" * 40,
        "apkSha256": "b" * 64, "buildType": "release", "device": "tv", "renderer": "lavapipe",
        "purpose": "input-causality", "sourceReady": True, "sceneReady": True,
        "refreshHz": 60.0, "activeWindowNs": 450_000_000,
        "metricCallbackDrops": 0, "truncated": False,
        "windows": [{"startNs": 5, "endNs": 450_000_005}],
        "inputs": [{"id": 1, "receivedNs": 5, "stateAppliedNs": 15, "responseDrawNs": 20,
                    "latencyNs": 15, "responseOutcome": "paired"}],
        "frames": frames,
    }


class AnalyzeTest(unittest.TestCase):
    def invoke(self, document):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "run.json"
            path.write_text(json.dumps(document))
            return subprocess.run([sys.executable, str(ROOT / "native-tv-perf/analyze.py"), str(path)],
                                  capture_output=True, text=True, timeout=10)

    def test_valid_run_exits_zero(self):
        result = self.invoke(run_document([
            {"vsyncNs": 20, "intendedVsyncNs": 20, "totalNs": 4_000_000, "deadlineNs": 10_000_000, "firstDraw": False},
            {"vsyncNs": 30, "intendedVsyncNs": 30, "totalNs": 5_000_000, "deadlineNs": 10_000_000, "firstDraw": False},
        ]))
        self.assertEqual(0, result.returncode, result.stderr)
        summary = json.loads(result.stdout)
        self.assertTrue(summary["valid"])
        self.assertEqual(1, summary["paired_inputs"])
        self.assertAlmostEqual(15.0, summary["latency_p95_ns"])

    def test_empty_frames_exit_nonzero(self):
        result = self.invoke(run_document([]))
        self.assertEqual(1, result.returncode)
        self.assertIn("no-frames", result.stdout)

    def test_unpaired_input_exits_nonzero(self):
        document = run_document([
            {"vsyncNs": 20, "intendedVsyncNs": 20, "totalNs": 4_000_000, "deadlineNs": 10_000_000, "firstDraw": False},
        ])
        document["inputs"] = [{"id": 1, "receivedNs": 5, "stateAppliedNs": 15, "responseOutcome": "no-frame-in-window"}]
        result = self.invoke(document)
        self.assertEqual(1, result.returncode)
        self.assertIn("not-enough-paired-inputs", result.stdout)


if __name__ == "__main__":
    unittest.main()
