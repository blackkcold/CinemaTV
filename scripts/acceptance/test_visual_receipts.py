import json
from pathlib import Path
import struct
import tempfile
import unittest
from manifest import digest
from visual_receipts import ANDROID_STATES, VISUAL_CASE, png_dimensions, validate_android


class VisualReceiptTest(unittest.TestCase):
    def fixture(self, folder, strict=False):
        identity = {'commit': 'a' * 40, 'tree': 'b' * 40}
        build = {'files': {'app-debug.apk': {'sha256': '1' * 64}, 'app-debug-androidTest.apk': {'sha256': '2' * 64}}}
        receipt = {'sourceCommit': identity['commit'], 'sourceTree': identity['tree'], 'files': build['files'],
                   'status': 'passed-android-visual-contracts', 'completedTests': [VISUAL_CASE],
                   'visualStates': list(ANDROID_STATES), 'comparisonStatus': 'passed-reviewed-comparison' if strict else 'candidate-only-not-approved'}
        self.save(folder / 'android-visual-receipt.json', receipt)
        comparisons = [{'state': name, 'passed': True, 'downsampled': False, 'masks': [],
                        'meanLimit': 0.010, 'changedLimit': 0.02, 'meanError': 0, 'changedRatio': 0} for name in ANDROID_STATES] if strict else []
        self.save(folder / 'android-visual-comparison.json', {'sourceCommit': identity['commit'], 'lane': 'hybrid4k',
                  'mode': 'reviewed-comparison' if strict else 'candidate-only-not-approved', 'comparisons': comparisons})
        directory = folder / 'visual-evidence'; directory.mkdir()
        baseline = folder / 'baselines/hybrid4k'; baseline.mkdir(parents=True)
        # Header-only fixture for the receipt layer. The producer's Pillow gate
        # separately fully decodes and validates actual compositor PNG files.
        header = b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', 3840, 2160)
        for name in ANDROID_STATES:
            image = directory / f'{name}.png'; image.write_bytes(header)
            env = {'family': 'hybrid-android-production', 'api': 36, 'provider': '111.0', 'fingerprint': 'test',
                   'reducedMotion': True, 'width': 3840, 'height': 2160, 'densityDpi': 640,
                   'cssWidth': 960, 'cssHeight': 540, 'dpr': 4}
            self.save(directory / f'{name}.capture.json', {'sourceCommit': identity['commit'], 'sourceTree': identity['tree'],
                      'apkSha256': build['files']['app-debug.apk']['sha256'], 'environment': env, 'sha256': digest(image)})
            (baseline / f'{name}.png').write_bytes(header)
            self.save(baseline / f'{name}.json', {'status': 'reviewed-baseline', 'environment': env, 'sha256': digest(image)})
        return identity, build

    def save(self, path, value): path.write_text(json.dumps(value))
    def mutate(self, path, **fields):
        value = json.loads(path.read_text()); value.update(fields); self.save(path, value)

    def test_candidate_capture_is_not_reviewed_acceptance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); identity, build = self.fixture(root)
            result = validate_android(root, 'hybrid4k', identity, build, capture=True)
            self.assertEqual(result['status'], 'candidate-only-not-approved')
            self.assertFalse(result['physicalDeviceCertified'])
            with self.assertRaises(ValueError): validate_android(root, 'hybrid4k', identity, build, capture=False)

    def test_reviewed_full_raster_receipts_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); identity, build = self.fixture(root, strict=True)
            result = validate_android(root, 'hybrid4k', identity, build, baseline_root=root / 'baselines')
            self.assertEqual(result['fullRaster'], [3840, 2160])
            self.assertEqual(len(result['captures']), 12)

    def test_stale_receipt_and_another_apk_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); identity, build = self.fixture(root)
            path = root / 'android-visual-receipt.json'
            self.mutate(path, sourceCommit='c' * 40)
            with self.assertRaises(ValueError): validate_android(root, 'hybrid4k', identity, build, True)
            self.mutate(path, sourceCommit=identity['commit'], files={'other.apk': {}})
            with self.assertRaises(ValueError): validate_android(root, 'hybrid4k', identity, build, True)

    def test_missing_visual_case_or_image_is_not_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); identity, build = self.fixture(root)
            path = root / 'android-visual-receipt.json'; self.mutate(path, completedTests=[])
            with self.assertRaises(ValueError): validate_android(root, 'hybrid4k', identity, build, True)
            self.mutate(path, completedTests=[VISUAL_CASE])
            (root / 'visual-evidence/home.png').unlink()
            with self.assertRaises(OSError): validate_android(root, 'hybrid4k', identity, build, True)

    def test_rescaled_image_cannot_claim_4k(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); identity, build = self.fixture(root)
            image = root / 'visual-evidence/home.png'
            image.write_bytes(image.read_bytes()[:16] + struct.pack('>II', 1920, 1080))
            self.mutate(root / 'visual-evidence/home.capture.json', sha256=digest(image))
            with self.assertRaises(ValueError): validate_android(root, 'hybrid4k', identity, build, True)

    def test_masked_relaxed_or_failed_comparisons_are_rejected(self):
        for mutation in ({'masks': [[0, 0, 20, 20]]}, {'downsampled': True}, {'meanLimit': 0.1}, {'passed': False}, {'meanError': 0.5}):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); identity, build = self.fixture(root, strict=True)
                path = root / 'android-visual-comparison.json'; value = json.loads(path.read_text())
                value['comparisons'][0].update(mutation); self.save(path, value)
                with self.assertRaises(ValueError): validate_android(root, 'hybrid4k', identity, build, baseline_root=root / 'baselines')

    def test_unreviewed_baseline_and_environment_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); identity, build = self.fixture(root, strict=True)
            path = root / 'baselines/hybrid4k/home.json'
            self.mutate(path, status='candidate-only-not-approved')
            with self.assertRaises(ValueError): validate_android(root, 'hybrid4k', identity, build, baseline_root=root / 'baselines')
            self.mutate(path, status='reviewed-baseline', environment={})
            with self.assertRaises(ValueError): validate_android(root, 'hybrid4k', identity, build, baseline_root=root / 'baselines')

    def test_invalid_png_header_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'invalid.png'; path.write_bytes(b'not png')
            with self.assertRaises(ValueError): png_dimensions(path)


if __name__ == '__main__': unittest.main()
