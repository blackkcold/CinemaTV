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

    def test_reviewed_files_match_recorded_bytes_and_dimensions(self):
        for viewport in ("tv1080", "tv4k"):
            folder = ROOT / "design/ui/goldens" / viewport
            manifest = json.loads((folder / "manifest.json").read_text())
            reviewed = [row for row in manifest["screens"] if "baselineSha256" in row]
            self.assertEqual([row["file"] for row in reviewed], ["03-detail.png", "11-settings.png"])
            for row in reviewed:
                with self.subTest(viewport=viewport, screen=row["file"]):
                    data = (folder / row["file"]).read_bytes()
                    self.assertEqual(hashlib.sha256(data).hexdigest(), row["baselineSha256"])
                    self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
                    self.assertEqual(struct.unpack(">II", data[16:24]), (960, 540))
                    self.assertRegex(row["rawCaptureSha256"], r"^[a-f0-9]{64}$")

    def test_settings_refresh_retains_its_separate_source_and_masks(self):
        for viewport in ("tv1080", "tv4k"):
            manifest = json.loads((ROOT / "design/ui/goldens" / viewport / "manifest.json").read_text())
            row = next(row for row in manifest["screens"] if row["file"] == "11-settings.png")
            self.assertEqual(row["masks"], [[410, 482, 555, 530], [455, 199, 515, 235]])
            self.assertEqual(row["sourceCommit"], "5576a108ee44f15d86adc2f215567cfeb79a31fb")
            self.assertEqual(row["workflowRun"], 41887458013)

    def test_detail_refresh_records_exact_reviewed_capture_provenance(self):
        captures = {
            "tv1080": (13363940974, [1920, 1080],
                       "e7f9ff92d5bc57b5e44c8af6d2dcd34d10487fc63e337cb56cc12d7468e7b1c1",
                       "54fcdb4ab13acaeb4896879dc9895e27149f4194d0307be9c26b0438a5ab6d84"),
            "tv4k": (13364155858, [3840, 2160],
                     "4449d164e4341c1924a73227944b1b5352fcc4a314d4a08a6ce87a90b0578d7a",
                     "749e2176e215464042463eed5c635e71cec1b4e495104d0f4df8db67c8e15f53"),
        }
        for viewport, (artifact, size, raw_sha, baseline_sha) in captures.items():
            with self.subTest(viewport=viewport):
                manifest = json.loads((ROOT / "design/ui/goldens" / viewport / "manifest.json").read_text())
                row = next(row for row in manifest["screens"] if row["file"] == "03-detail.png")
                self.assertEqual(compare.baseline_provenance(manifest, row), {
                    "baselineSourceCommit": "3b2e9842d9efa305f56f1cef0148b0fcd3fe0a45",
                    "baselineWorkflowRun": 41911066064,
                })
                self.assertEqual(row["artifactId"], artifact)
                self.assertEqual(row["rawCaptureSize"], size)
                self.assertEqual(row["rawCaptureSha256"], raw_sha)
                self.assertEqual(row["baselineSha256"], baseline_sha)
                self.assertEqual(row["reviewedDate"], "2026-11-04")
                self.assertEqual(row["masks"], [])

    def test_detail_refresh_preserves_all_other_pixels_masks_and_defaults(self):
        unchanged = {
            "tv1080": {
                "01-home.png": "43b2aed5e351fee2b69c8d657d1d745fa2963999f3189ef6a15156dd5e808528",
                "05-movies.png": "3f88da3db200d19b8750c70d064611b4966061410bc78afc2427f537829ff176",
                "06-series.png": "873f380ee5c6f35e31023888ca15677c65b8fd2c3bc1b919737b8b6eb723863b",
                "07-discovery.png": "3af6c8d0afec627262d473139ccf8a1fd97c40486eedd001e2b316ded649b610",
                "11-settings.png": "f4113041908110f04eb9c28cfe331076b829a17b7ce744310c2a76d7fafce58c",
            },
            "tv4k": {
                "01-home.png": "d04c9cb57a604ccb09fe2c6fdee7ce07705f4b3d08220d099b10522555d5035e",
                "05-movies.png": "b20494329c788c9f68255767b62bb924f33729d34ed42ad782eacffa3b620fde",
                "06-series.png": "a600789f47aff9de93e064037fdb34779445add0b8fcd7f3a4242de2fe13eef4",
                "07-discovery.png": "7d5fa9c958cf53da0e3d0aad87793187e461943b0526652d40a22eea1ce82b97",
                "11-settings.png": "9c4ce90776a49f733dd5d44d3da85630d59e6937ec0b9994cfeae1ef54e58083",
            },
        }
        expected_masks = {
            "01-home.png": [], "03-detail.png": [],
            "05-movies.png": [[410, 482, 555, 530]],
            "06-series.png": [[410, 482, 555, 530]],
            "07-discovery.png": [[410, 482, 555, 530]],
            "11-settings.png": [[410, 482, 555, 530], [455, 199, 515, 235]],
        }
        for viewport, files in unchanged.items():
            folder = ROOT / "design/ui/goldens" / viewport
            manifest = json.loads((folder / "manifest.json").read_text())
            self.assertEqual(manifest["sourceCommit"], "07d85420df3fd13a83e0d9b41b740927d5011bc0")
            self.assertEqual(manifest["workflowRun"], 41819223687)
            self.assertEqual(manifest["normalizedSize"], [960, 540])
            self.assertEqual({row["file"]: row["masks"] for row in manifest["screens"]}, expected_masks)
            for filename, sha in files.items():
                with self.subTest(viewport=viewport, screen=filename):
                    self.assertEqual(hashlib.sha256((folder / filename).read_bytes()).hexdigest(), sha)


if __name__ == "__main__":
    unittest.main()
