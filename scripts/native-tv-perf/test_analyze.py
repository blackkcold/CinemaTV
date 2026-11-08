import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run_document(frames):
    return {
        "schema": 3, "runId": "r", "sourceSha": "a" * 40, "apkSha256": "b" * 64,
        "buildType": "release", "device": "tv", "renderer": "lavapipe",
        "requestedTier": "FULL", "actualTier": "FULL", "sceneReady": True,
        "metricCallbackDrops": 0, "truncated": False,
        "windows": [{"startNs": 1, "endNs": 2}],
        "inputs": [{"id": 1, "receivedNs": 1, "responseDrawNs": 2}],
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
        result = self.invoke(run_document([{"vsyncNs": 1, "intendedVsyncNs": 1,
                                            "totalNs": 4_000_000, "deadlineNs": 10_000_000}]))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(json.loads(result.stdout)["valid"])

    def test_empty_frames_exit_nonzero(self):
        result = self.invoke(run_document([]))
        self.assertEqual(1, result.returncode)
        self.assertIn("no-frames", result.stdout)


if __name__ == "__main__":
    unittest.main()
