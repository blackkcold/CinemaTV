"""Baseline provenance tests need only the standard library, before CI installs Pillow."""
import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("compare_ui_goldens", ROOT / "scripts/compare-ui-goldens.py")
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)

SOURCE_COMMIT = "80a91e3ae7605a96717c7a238b37cefd81264104"
WORKFLOW_RUN = 41993059313
REVIEWED_DATE = "2026-11-05"
SCREENS = ("01-home.png", "03-detail.png", "05-movies.png", "06-series.png", "07-discovery.png", "11-settings.png")
EXPECTED_MASKS = {
    "01-home.png": [], "03-detail.png": [],
    "05-movies.png": [[410, 482, 555, 530]],
    "06-series.png": [[410, 482, 555, 530]],
    "07-discovery.png": [[410, 482, 555, 530]],
    "11-settings.png": [[410, 482, 555, 530], [455, 262, 515, 298]],
}
# Exact values from the individually reviewed 80a91e3 artifact bytes, not values
# derived from the current manifest under test. Tuples are raw SHA, baseline SHA.
# Current reviewed baseline: both viewports were recaptured after the reading-scrim
# change. Each row keeps the raw capture SHA and the normalized baseline SHA.
BASELINES = {
        "tv1080": {
        "source": "8532f98a9d44fbac9b1e0f6eee97dfb4d5d1aa09",
        "run": 45314161245,
        "artifact": 14885387790,
        "date": "2026-11-24",
        "size": [1920, 1080],
    },

        "tv4k": {
        "source": "5034b64649dae2caae460826efaaa89f03ee451a",
        "run": 45324367460,
        "artifact": 14889319771,
        "date": "2026-11-24",
        "size": [3840, 2160],
    },

}

CAPTURES = {
        "tv1080": {
        "01-home.png": ("23cf4aa6d06ed1c0afb43aa7cf5ab9b88be91e98f1e4e1adaad80421d3c3aa7b",
                        "623cdb2a5e0a1043d19694a905d42596517ba31dbeb2ea6b14ac2145a0844d63"),
        "03-detail.png": ("c839961a450c4d41a7fb1b57a24f167908a3554df2db7e43a3ad3950590f8c01",
                        "c1bff5f28ddf8cbbad47eced5317ccc995c60522599e733816554a1faa725905"),
        "05-movies.png": ("98c1c46091a2a3b820d0da0395720179ebf7b502a49a570a53f30a3f906a9e31",
                        "2e4c81ec0f0c200bb746aeadd5da4892d31a6cc93b8ddaf99c0136e5c602a518"),
        "06-series.png": ("660bbb5b37da21bee7eecf7a04fb0ddf45a70f93a6d5d6dabd09574affc5b026",
                        "5fb5362e0d893824d5adf0ed487eddf89fc701dd1a951d1d5cdef4abb3012784"),
        "07-discovery.png": ("399763ba433cf2e0ad0c20a1f4272465fcef7477d4b8f30feec63433dca9fd8d",
                        "045152253d6e5b67a3a6441c4e3ea3e7578e6d1c6a688e217460744aa6bc471b"),
        "11-settings.png": ("fe8f22905f593711961f3a702078e2d0cde627622c97ff87797a05863fcbe72b",
                        "dbbed3132fead147017704f7764b74180af9b5ade2c167ecac9b0444202cfb5f"),
    },

        "tv4k": {
        "01-home.png": ("9e257f07157cb34a8fd2270844053629134ac0514160a5117aaa00773455f529",
                        "1bc173beaa04d353167c17e412fe679f3c5443a95d322177be59b00fad50dcaa"),
        "03-detail.png": ("d645aadc731bc0418a42e07cb1160b446c15533d2a775fb2a11a7fe829d96c7e",
                        "ece633be82403ec24867521498642706c13e12280ddd650ae8aa1b8df6a0a5ce"),
        "05-movies.png": ("615bfae4d72bb71aaef002c3ecbdf0ec3945206625c147022ff602b0aadaf065",
                        "df290ae49b411889af160a95f125e613197fe61301915524524dfdbd32fb1bfe"),
        "06-series.png": ("9a3a814aca47437e17bb1bb76110f0fd8462e086dfae907fe1f9bfc10575f9e2",
                        "0234bc0bc937f1edecc107c1b99d02561f3c414b75bff70ae3d63cc7d2fe8414"),
        "07-discovery.png": ("caddf5fe463c808cd89cc0d31614721d80ef1be0e6500b647d17ed9fd7e5a9e8",
                        "87a38efa7fd77f4dc041decdb4ea94cb3aece6e42ee8ec6df7915bc9784d53ca"),
        "11-settings.png": ("9bfd85dd0c61ca12caf38a24945879b5b3cdaf895ac477c25e75ae426480102c",
                        "64f735fb15327e567aa32614af9dcb81c4b3980b89aec33798fe927c5583ee25"),
    },

}

# The 2026-11-05 settings bytes, kept only because REVIEW.md's superseded ledger
# still quotes what that review saw.
LEGACY_SETTINGS_CAPTURE_HASHES = {
    "tv1080": ("2574fa628d8fb3f3baceba7aaed604d5c439fa72dc4f6948cb564bf894dfa8cd",
               "e63a35a868615d95b9c0f0cd924e37882ad4047a3aa0146c00a952e5e49ad2cd"),
    "tv4k": ("be69fd0bc6fad07f2d9fb19d44a3fbf1a8ba06d830f9eee46f10d457dfe3eb2c",
             "d86173e19bafc82bb9a6230ae6cd2f605df71df0bf3923082e88ba738f4bec36"),
}

# Only settings uses the later reviewed capture. Preserve the exact source and
# bytes above for the five unchanged screens and the superseded settings ledger.
SETTINGS_SOURCE_COMMIT = "ce0ee6c11a936c0e1b0297f132957838502b6f5f"
SETTINGS_WORKFLOW_RUN = 42183795256
SETTINGS_REVIEWED_DATE = "2026-11-06"
SETTINGS_CAPTURES = {
    "tv1080": {
        "artifactId": 13487249284,
        "rawCaptureSha256": "587954dc787a0705049f906ad588c8f80b338cc347946ac0452f5bf70abd4f8d",
        "baselineSha256": "4236126c5b52dbd53d23511da0079ff666a74adc1628751c3d8539a965440151",
        "zipSha256": "19741025b80fb71c6127b1fc0980e1bb9671da4e9c6f928013be59f5fcd3bd4d",
    },
    "tv4k": {
        "artifactId": 13488310708,
        "rawCaptureSha256": "24cb21b122f06cb5454353d1ea906fdcee1d03b9fbe139fc0a62664f16648f49",
        "baselineSha256": "c1f013ed41c77001199aebb5e5e7f76dcfec4f67746e0f87fbe385bba0d9641c",
        "zipSha256": "1805ac955ad7a4336a63e4675f2e0f6e5659a52af785d49595c92e51d0cc2c91",
    },
}

# Historical detail/settings overrides remain recorded in REVIEW.md. Do not
# erase earlier capture evidence when accepting a later per-screen refresh.
HISTORICAL_CAPTURES = [('tv1080/03-detail.png',
  '3b2e9842d9efa305f56f1cef0148b0fcd3fe0a45',
  41911066064,
  13363940974,
  '1920×1080',
  '2026-11-04',
  'e7f9ff92d5bc57b5e44c8af6d2dcd34d10487fc63e337cb56cc12d7468e7b1c1',
  '54fcdb4ab13acaeb4896879dc9895e27149f4194d0307be9c26b0438a5ab6d84'),
 ('tv1080/11-settings.png',
  '5576a108ee44f15d86adc2f215567cfeb79a31fb',
  41887458013,
  13353160716,
  '1920×1080',
  '2026-11-04',
  'f7d5cff8d2acaa509c22aaa4f112f96c3385286391f2c031044f38a9060068a6',
  'f4113041908110f04eb9c28cfe331076b829a17b7ce744310c2a76d7fafce58c'),
 ('tv4k/03-detail.png',
  '3b2e9842d9efa305f56f1cef0148b0fcd3fe0a45',
  41911066064,
  13364155858,
  '3840×2160',
  '2026-11-04',
  '4449d164e4341c1924a73227944b1b5352fcc4a314d4a08a6ce87a90b0578d7a',
  '749e2176e215464042463eed5c635e71cec1b4e495104d0f4df8db67c8e15f53'),
 ('tv4k/11-settings.png',
  '5576a108ee44f15d86adc2f215567cfeb79a31fb',
  41887458013,
  13353325309,
  '3840×2160',
  '2026-11-04',
  '51d44392904580a3b43c7a25b82351a8fb71c95547709d3627490883e4d4cffa',
  '9c4ce90776a49f733dd5d44d3da85630d59e6937ec0b9994cfeae1ef54e58083')]


def read_manifest(viewport):
    return json.loads((ROOT / "design/ui/goldens" / viewport / "manifest.json").read_text())


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
        for viewport in CAPTURES:
            folder = ROOT / "design/ui/goldens" / viewport
            manifest = read_manifest(viewport)
            self.assertEqual([row["file"] for row in manifest["screens"]], list(SCREENS))
            self.assertEqual(sorted(p.name for p in folder.glob("*.png")), sorted(SCREENS))
            for row in manifest["screens"]:
                with self.subTest(viewport=viewport, screen=row["file"]):
                    data = (folder / row["file"]).read_bytes()
                    self.assertEqual(hashlib.sha256(data).hexdigest(), row["baselineSha256"])
                    self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
                    self.assertEqual(struct.unpack(">II", data[16:24]), (960, 540))
                    # Validate every PNG chunk/CRC and the complete RGB scanline
                    # stream without needing Pillow in the pre-install CI stage.
                    cursor, chunks, pixels = 8, [], bytearray()
                    while cursor < len(data):
                        length = struct.unpack(">I", data[cursor:cursor + 4])[0]
                        kind = data[cursor + 4:cursor + 8]
                        payload = data[cursor + 8:cursor + 8 + length]
                        crc_end = cursor + 12 + length
                        self.assertLessEqual(crc_end, len(data))
                        crc = struct.unpack(">I", data[crc_end - 4:crc_end])[0]
                        self.assertEqual(zlib.crc32(kind + payload) & 0xffffffff, crc)
                        chunks.append(kind)
                        if kind == b"IHDR":
                            self.assertEqual(payload, struct.pack(">IIBBBBB", 960, 540, 8, 2, 0, 0, 0))
                        if kind == b"IDAT":
                            pixels.extend(payload)
                        if kind == b"IEND":
                            self.assertEqual(length, 0)
                            self.assertEqual(crc_end, len(data))
                        cursor = crc_end
                    self.assertEqual(chunks[0], b"IHDR")
                    self.assertEqual(chunks.count(b"IHDR"), 1)
                    self.assertEqual(chunks[-1], b"IEND")
                    decoder = zlib.decompressobj()
                    raster = decoder.decompress(bytes(pixels)) + decoder.flush()
                    self.assertTrue(decoder.eof)
                    self.assertEqual(decoder.unused_data, b"")
                    self.assertEqual(len(raster), 540 * (1 + 960 * 3))
                    self.assertTrue(all(value in range(5) for value in raster[::1 + 960 * 3]))

    def test_each_screen_records_its_exact_reviewed_capture_provenance(self):
        for viewport, captures in CAPTURES.items():
            manifest = read_manifest(viewport)
            baseline = BASELINES[viewport]
            self.assertEqual(list(captures), list(SCREENS))
            for row in manifest["screens"]:
                with self.subTest(viewport=viewport, screen=row["file"]):
                    self.assertEqual(row["sourceCommit"], baseline["source"])
                    self.assertEqual(row["workflowRun"], baseline["run"])
                    self.assertEqual(compare.baseline_provenance(manifest, row), {
                        "baselineSourceCommit": baseline["source"], "baselineWorkflowRun": baseline["run"],
                    })
                    self.assertEqual(row["artifactId"], baseline["artifact"])
                    self.assertEqual(row["rawCaptureSize"], baseline["size"])
                    self.assertEqual((row["rawCaptureSha256"], row["baselineSha256"]), captures[row["file"]])
                    self.assertEqual(row["reviewedDate"], baseline["date"])
                    self.assertGreater(len(row["reviewReason"]), 100)
            self.assertEqual(len({row["reviewReason"] for row in manifest["screens"]}), 6)

    def test_refresh_preserves_reviewed_masks_and_viewport_defaults(self):
        for viewport in CAPTURES:
            manifest = read_manifest(viewport)
            baseline = BASELINES[viewport]
            self.assertEqual(manifest["sourceCommit"], baseline["source"])
            self.assertEqual(manifest["workflowRun"], baseline["run"])
            self.assertEqual(manifest["reviewedDate"], baseline["date"])
            self.assertEqual(manifest["viewport"], viewport)
            self.assertEqual(manifest["normalizedSize"], [960, 540])
            self.assertEqual({row["file"]: row["masks"] for row in manifest["screens"]}, EXPECTED_MASKS)

    def test_settings_moves_existing_port_mask_without_expanding_it(self):
        original = [455, 199, 515, 235]
        for viewport in CAPTURES:
            row = next(row for row in read_manifest(viewport)["screens"] if row["file"] == "11-settings.png")
            self.assertEqual(len(row["masks"]), 2)
            self.assertEqual(row["masks"][0], [410, 482, 555, 530])
            relocated = row["masks"][1]
            self.assertEqual([new - old for new, old in zip(relocated, original)], [0, 63, 0, 63])
            self.assertEqual((relocated[2] - relocated[0], relocated[3] - relocated[1]),
                             (original[2] - original[0], original[3] - original[1]))

    def test_comparison_limits_and_normalization_are_not_weakened(self):
        self.assertEqual(compare.SIZE, (960, 540))
        tree = ast.parse((ROOT / "scripts/compare-ui-goldens.py").read_text())
        defaults = {}
        for node in ast.walk(tree):
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and node.func.attr == "add_argument" and node.args
                    and isinstance(node.args[0], ast.Constant)):
                for keyword in node.keywords:
                    if keyword.arg == "default":
                        defaults[node.args[0].value] = ast.literal_eval(keyword.value)
        self.assertEqual(defaults["--mean-limit"], 0.020)
        self.assertEqual(defaults["--changed-limit"], 0.08)
        normalization = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "normalized")
        code = ast.unparse(normalization)
        self.assertIn("image.convert('RGB').resize(SIZE, Image.Resampling.LANCZOS)", code)

    def test_detail_and_settings_historical_records_are_preserved(self):
        review = (ROOT / "design/ui/goldens/REVIEW.md").read_text()
        # Exact protection for both pre-existing reviews, including their failure
        # caveats. New refreshes append evidence rather than rewrite those claims.
        historical = review.split("\n## Production glass and motion refresh · 2026-11-05", 1)[0]
        self.assertEqual(hashlib.sha256(historical.encode()).hexdigest(),
                         "84fe038a1af826bb5c50ebef07bdc38bf2954ebaa62dfbeec6617bca063ca41c")
        ledger = review.split("### Superseded per-screen provenance", 1)[1]
        for record in HISTORICAL_CAPTURES:
            with self.subTest(capture=record[0]):
                line = next(line for line in ledger.splitlines() if f"`{record[0]}`" in line)
                for value in record[1:]:
                    self.assertIn(str(value), line)

    def test_review_keeps_failure_boundary_and_artifact_identity_explicit(self):
        review = (ROOT / "design/ui/goldens/REVIEW.md").read_text().split(
            "## Production glass and motion refresh · 2026-11-05", 1)[1]
        for required in (SOURCE_COMMIT, str(WORKFLOW_RUN), "13398969985", "13400124111",
                         "1408c53d8e9e15e88956d70039be9eba768c4d76ab5b3b81320e7fd574861e3e",
                         "104fd1d4e938a5b451adefb4941394b24648ae114675a0d2297b8a8da51dfc41",
                         "19 of 21", "six native button tests", "ten native motion tests",
                         "The source run remains failed", "12× font scale", "before any Down input",
                         "4 pixels at 1080p, 8 pixels at 4K", "not a new scroll request",
                         "not added to the six-screen baseline set", "no masks baked into the PNG",
                         "final new tree must still pass the complete exact-commit acceptance matrix"):
            self.assertIn(required, review)
        for name in SCREENS:
            self.assertIn(f"`{name}`", review)

    def test_settings_review_preserves_prior_history_and_exact_capture_evidence(self):
        review = (ROOT / "design/ui/goldens/REVIEW.md").read_text()
        previous, current = review.split("\n## Settings destinations refresh · 2026-11-06", 1)
        self.assertEqual(hashlib.sha256(previous.encode()).hexdigest(),
                         "a66732365a117846778255728ab69694fbca739a2c974c8e4c7bd3ed81715949")
        for required in (SETTINGS_SOURCE_COMMIT, str(SETTINGS_WORKFLOW_RUN),
                         "6f3d64d375dc82d5ca467fb38db59ab5643354c0", "62 of 62", "18 of 18",
                         "The source run remains failed", "63 normalized pixels", "no area expansion",
                         "0.033476/0.070604", "0.032889/0.066644", "0.000007/0",
                         "Pillow 13.0.0", "no masks baked into the PNG", "fresh exact-commit acceptance"):
            self.assertIn(required, current)
        for viewport, capture in SETTINGS_CAPTURES.items():
            with self.subTest(viewport=viewport):
                for value in capture.values():
                    self.assertIn(str(value), current)
                ledger = current.split("### Superseded 2026-11-05 settings captures", 1)[1]
                line = next(line for line in ledger.splitlines() if f"`{viewport}/11-settings.png`" in line)
                old_artifact = {"tv1080": 13398969985, "tv4k": 13400124111}[viewport]
                for value in (SOURCE_COMMIT, str(WORKFLOW_RUN), REVIEWED_DATE, str(old_artifact),
                              *LEGACY_SETTINGS_CAPTURE_HASHES[viewport]):
                    self.assertIn(value, line)


if __name__ == "__main__":
    unittest.main()
