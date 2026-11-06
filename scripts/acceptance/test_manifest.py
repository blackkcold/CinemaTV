import copy
from pathlib import Path
import tempfile
import unittest
from manifest import digest, inventory, safe_file, validate_receipts, verify_inventory


class ManifestTest(unittest.TestCase):
    def fixture(self):
        identity = {'commit': 'a' * 40, 'tree': 'b' * 40}
        builds = [{'schema': 1, 'kind': 'build', 'source': identity, 'configuration': c,
                   'files': {'app-debug.apk': {'sha256': str(i) * 64},
                             'app-debug-androidTest.apk': {'sha256': str(i + 2) * 64}}}
                  for i, c in enumerate(('preview-debug', 'dev-debug'), 1)]
        plan = {name: {'configuration': builds[i]['configuration'], 'minimumCases': 1}
                for i, name in enumerate(('native', 'hybrid4k'))}
        receipts = [{'schema': 1, 'kind': 'device', 'lane': name, 'source': identity,
                     'configuration': builds[i]['configuration'], 'status': 'passed',
                     'apkSha256': builds[i]['files']['app-debug.apk']['sha256'],
                     'testApkSha256': builds[i]['files']['app-debug-androidTest.apk']['sha256'],
                     'startedAt': f'2026-11-06T12:0{i * 2}:00+00:00',
                     'finishedAt': f'2026-11-06T12:0{i * 2 + 1}:00+00:00',
                     'completedTests': ['Case#method']}
                    for i, name in enumerate(plan)]
        return identity, plan, builds, receipts

    def test_complete_serial_result_does_not_authorize_release(self):
        result = validate_receipts(*self.fixture())
        self.assertFalse(result['publicationAllowed'])
        self.assertFalse(result['productionSigned'])
        self.assertFalse(result['physicalDeviceCertified'])

    def test_old_commit_even_with_same_tree_rejected(self):
        a, b, c, d = self.fixture()
        d[0]['source'] = {**a, 'commit': 'c' * 40}
        with self.assertRaises(ValueError): validate_receipts(a, b, c, d)

    def test_missing_lane_rejected(self):
        a, b, c, d = self.fixture()
        with self.assertRaises(ValueError): validate_receipts(a, b, c, d[:-1])

    def test_duplicate_lane_rejected(self):
        a, b, c, d = self.fixture()
        with self.assertRaises(ValueError): validate_receipts(a, b, c, d + d[:1])

    def test_failed_lane_rejected(self):
        a, b, c, d = self.fixture(); d[1]['status'] = 'failed'
        with self.assertRaises(ValueError): validate_receipts(a, b, c, d)

    def test_apk_configuration_and_hashes_cannot_be_substituted(self):
        for field in ('configuration', 'apkSha256', 'testApkSha256'):
            with self.subTest(field=field):
                a, b, c, d = self.fixture(); d[0][field] = d[1][field]
                with self.assertRaises(ValueError): validate_receipts(a, b, c, d)

    def test_empty_or_duplicate_execution_rejected(self):
        for cases in ([], ['Case#method', 'Case#method']):
            a, b, c, d = self.fixture(); d[0]['completedTests'] = cases
            with self.assertRaises(ValueError): validate_receipts(a, b, c, d)

    def test_parallel_intervals_rejected(self):
        a, b, c, d = self.fixture(); d[1]['startedAt'] = d[0]['startedAt']
        with self.assertRaisesRegex(ValueError, 'overlapped'): validate_receipts(a, b, c, d)

    def test_reversed_device_order_rejected(self):
        a, b, c, d = self.fixture()
        for field in ('startedAt', 'finishedAt'): d[0][field], d[1][field] = d[1][field], d[0][field]
        with self.assertRaisesRegex(ValueError, 'sequence'): validate_receipts(a, b, c, d)

    def test_missing_timezone_or_negative_interval_rejected(self):
        for start in ('2026-11-06T12:00:00', '2026-11-07T12:00:00+00:00'):
            a, b, c, d = self.fixture(); d[0]['startedAt'] = start
            with self.assertRaises(ValueError): validate_receipts(a, b, c, d)

    def test_missing_or_duplicate_builds_rejected(self):
        for mode in ('missing', 'duplicate'):
            a, b, c, d = self.fixture()
            c = c[:1] if mode == 'missing' else c + c[:1]
            with self.assertRaises(ValueError): validate_receipts(a, b, c, d)

    def test_inventory_detects_changed_bytes_and_size(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); path = root / 'log.txt'; path.write_text('original')
            files = inventory(root); verify_inventory(root, files)
            path.write_text('tampered')
            with self.assertRaises(ValueError): verify_inventory(root, files)
            path.write_text('short')
            with self.assertRaises(ValueError): verify_inventory(root, files)

    def test_paths_and_symlinks_cannot_escape_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'ok').write_text('ok')
            for name in ('../escape', '/tmp/escape', 'a\\b', ''):
                with self.subTest(name=name), self.assertRaises(ValueError): safe_file(root, name)
            (root / 'link').symlink_to(root / 'ok')
            with self.assertRaises(ValueError): safe_file(root, 'link')
            with self.assertRaises(ValueError): inventory(root)


if __name__ == '__main__': unittest.main()
