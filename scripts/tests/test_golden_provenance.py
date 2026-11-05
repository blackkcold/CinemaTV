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
    "11-settings.png": [[410, 482, 555, 530], [455, 199, 515, 235]],
}
# Exact values from the individually reviewed 80a91e3 artifact bytes, not values
# derived from the current manifest under test. Tuples are raw SHA, baseline SHA.
CAPTURES = {'tv1080': {'01-home.png': ('1af2ee36b836e7ee6d317af6ebb747eba28af4ef1e98310a84b7083be2c118ac',
                            '384697bdfee784beb9bbe662524195fea49ffc0fb20ea1f74685693541d0ffa3'),
            '03-detail.png': ('0f52259bb6cfef6731bf5c49f896868cba6fbb37c118dde915b4ee86f5e5bfb8',
                              'e98299b0a1b190a9ecb1c4fea5e434907ef4d3460bfb5109ba12088e4637596b'),
            '05-movies.png': ('40916671a76b97c3afdc1c400731defa56ab1c6637cfb32d37004237e89f4905',
                              '405adfd5d58a5deab709b856b00fbdce5ebfb7c484302eb6388b08609ec8bacb'),
            '06-series.png': ('c11dd2ba0c122f5815f9f2b5864d903a4d3734584bd20566d1d1bdb75e376c67',
                              '29095fc4533d38cf7a3ef2f6daa4c1df89c06baf5fb6400a459b68e7934bef6b'),
            '07-discovery.png': ('d99e1bc153c5df83278bea408ef0088b786e78b13938ad7c7b694c2da2e00be4',
                                 '9e7a6c4c1f8d55ee5e89405dc2a9ab99ad58e2a856eced30abca8f78e278352a'),
            '11-settings.png': ('2574fa628d8fb3f3baceba7aaed604d5c439fa72dc4f6948cb564bf894dfa8cd',
                                'e63a35a868615d95b9c0f0cd924e37882ad4047a3aa0146c00a952e5e49ad2cd')},
 'tv4k': {'01-home.png': ('83ee2874bbc8a6072d296b247ace441b075bef5bc58721dc5d22b78d6a200b06',
                          'ffdf4474a01890c5c9de32d8f392baa52571e0f8253aa3407aa5c35e7e8fc908'),
          '03-detail.png': ('78aca7a4484b84d42174b158eb014316167edaa0251485d3e3b2d93ede5cf7ca',
                            '5c1c2669e71fa96cd5e8018c501f0ee68472138ed91cdb1e28de9d60a0e29111'),
          '05-movies.png': ('335bff55393f19c4f95280c800654e6aa274319ddec440e84b9c6af2c5ced0dc',
                            '1f86132bd1998c097447eb58c1b12ea2c529db6d5af6f740a3b226606c06c946'),
          '06-series.png': ('b9363447b8514efd99200356129ff5f4b6c222ad3f47783b49dc4718a1447d97',
                            'bdfaa745d93b92bbb10b8e4515ae951ba4cc152542cf3ea084a4930f109c8e87'),
          '07-discovery.png': ('587893a692c2c879fd22a441fe4565cdb9f3d25276a6c23182f7f7289b9ed680',
                               '33f93a82041452862af78bfa24c82ac6e7cb6eaaf642d0e1a7287a55cf286121'),
          '11-settings.png': ('be69fd0bc6fad07f2d9fb19d44a3fbf1a8ba06d830f9eee46f10d457dfe3eb2c',
                              'd86173e19bafc82bb9a6230ae6cd2f605df71df0bf3923082e88ba738f4bec36')}}

# Historical detail/settings overrides remain recorded in REVIEW.md even though
# all six current rows now use 80a91e3. Do not erase the earlier capture evidence.
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

    def test_six_screen_refresh_records_exact_reviewed_capture_provenance(self):
        artifacts = {"tv1080": (13398969985, [1920, 1080]), "tv4k": (13400124111, [3840, 2160])}
        for viewport, captures in CAPTURES.items():
            manifest = read_manifest(viewport)
            artifact, size = artifacts[viewport]
            self.assertEqual(list(captures), list(SCREENS))
            for row in manifest["screens"]:
                with self.subTest(viewport=viewport, screen=row["file"]):
                    self.assertEqual(row["sourceCommit"], SOURCE_COMMIT)
                    self.assertEqual(row["workflowRun"], WORKFLOW_RUN)
                    self.assertEqual(compare.baseline_provenance(manifest, row), {
                        "baselineSourceCommit": SOURCE_COMMIT, "baselineWorkflowRun": WORKFLOW_RUN,
                    })
                    self.assertEqual(row["artifactId"], artifact)
                    self.assertEqual(row["rawCaptureSize"], size)
                    self.assertEqual((row["rawCaptureSha256"], row["baselineSha256"]), captures[row["file"]])
                    self.assertEqual(row["reviewedDate"], REVIEWED_DATE)
                    self.assertGreater(len(row["reviewReason"]), 100)
            self.assertEqual(len({row["reviewReason"] for row in manifest["screens"]}), 6)

    def test_refresh_preserves_all_masks_and_original_manifest_defaults(self):
        for viewport in CAPTURES:
            manifest = read_manifest(viewport)
            self.assertEqual(manifest["sourceCommit"], "07d85420df3fd13a83e0d9b41b740927d5011bc0")
            self.assertEqual(manifest["workflowRun"], 41819223687)
            self.assertEqual(manifest["reviewedDate"], "2026-11-04")
            self.assertEqual(manifest["viewport"], viewport)
            self.assertEqual(manifest["normalizedSize"], [960, 540])
            self.assertEqual({row["file"]: row["masks"] for row in manifest["screens"]}, EXPECTED_MASKS)

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


if __name__ == "__main__":
    unittest.main()
