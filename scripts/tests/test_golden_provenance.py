"""Baseline provenance tests need only the standard library, before CI installs Pillow."""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("compare_ui_goldens", ROOT / "scripts/compare-ui-goldens.py")
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)


class GoldenProvenanceTest(unittest.TestCase):
    def test_original_screen_retains_manifest_defaults(self):
        self.assertEqual(compare.baseline_provenance({"sourceCommit": "original", "workflowRun": 1}, {}),
                         {"baselineSourceCommit": "original", "baselineWorkflowRun": 1})

    def test_reviewed_screen_overrides_only_its_own_source(self):
        self.assertEqual(compare.baseline_provenance({"sourceCommit": "original", "workflowRun": 1},
                         {"sourceCommit": "reviewed", "workflowRun": 2}),
                         {"baselineSourceCommit": "reviewed", "baselineWorkflowRun": 2})

    def test_missing_legacy_workflow_is_not_invented(self):
        self.assertIsNone(compare.baseline_provenance({"sourceCommit": "legacy"}, {})["baselineWorkflowRun"])

    def test_reviewed_settings_files_match_recorded_bytes_and_dimensions(self):
        for viewport in ("tv1080", "tv4k"):
            folder = ROOT / "design/ui/goldens" / viewport
            manifest = json.loads((folder / "manifest.json").read_text())
            reviewed = [row for row in manifest["screens"] if "baselineSha256" in row]
            self.assertEqual([row["file"] for row in reviewed], ["11-settings.png"])
            row = reviewed[0]
            data = (folder / row["file"]).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), row["baselineSha256"])
            self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
            self.assertEqual(struct.unpack(">II", data[16:24]), (960, 540))
            self.assertEqual(row["masks"], [[410, 482, 555, 530], [455, 199, 515, 235]])
            self.assertEqual(row["sourceCommit"], "5576a108ee44f15d86adc2f215567cfeb79a31fb")
            self.assertEqual(row["workflowRun"], 41887458013)
            self.assertRegex(row["rawCaptureSha256"], r"^[a-f0-9]{64}$")


if __name__ == "__main__":
    unittest.main()
