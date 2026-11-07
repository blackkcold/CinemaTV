import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from PIL import Image
from review_baselines import prepare, api, BROWSER_PROJECTS


class BaselineReviewTest(unittest.TestCase):
    def fixture(self, root):
        image = io.BytesIO(); Image.new('RGB', BROWSER_PROJECTS['tv720'], 'black').save(image, format='PNG')
        raw = image.getvalue(); digest = hashlib.sha256(raw).hexdigest()
        metadata = json.dumps({'status': 'candidate-only-not-approved', 'sourceCommit': 'a' * 40,
                    'sha256': digest, 'image': 'home.png', 'environment': {'family': 'hybrid-browser-production',
                    'viewport': {'width': 1280, 'height': 720}, 'dpr': 1}}).encode()
        archive = root / 'input.zip'
        with zipfile.ZipFile(archive, 'w') as stream:
            stream.writestr('captures/home.png', raw)
            stream.writestr('captures/home.json', metadata)
            stream.writestr('production-results/results.json', json.dumps({'stats': {'expected': 56, 'unexpected': 0, 'flaky': 0, 'skipped': 0}, 'errors': []}))
        item = {'family': 'hybrid-browser', 'project': 'tv720', 'state': 'home',
                'imagePath': 'captures/home.png', 'metadataPath': 'captures/home.json',
                'imageSha256': digest, 'metadataSha256': hashlib.sha256(metadata).hexdigest(),
                'reason': 'Test-only review record of unchanged full raster and focus.'}
        artifact = {'id': 1, 'runId': 2, 'sourceCommit': 'a' * 40,
                    'zipSha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'images': [item]}
        review = {'status': 'explicitly-reviewed', 'reviewedBy': 'test', 'reviewedAt': '2026-11-07'}
        return archive, artifact, review, raw

    def test_original_bytes_and_explicit_provenance_are_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, artifact, review, raw = self.fixture(Path(tmp))
            values = prepare(archive, artifact, review)
            self.assertEqual(values['design/ui/goldens/hybrid-browser/tv720/home.png'], raw)
            approval = json.loads(values['design/ui/goldens/hybrid-browser/tv720/home.json'])
            self.assertEqual(approval['status'], 'reviewed-baseline')
            self.assertEqual(approval['sourceArtifactId'], 1)
            self.assertEqual(approval['sourceCommit'], 'a' * 40)

    def test_missing_review_and_changed_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, artifact, review, _ = self.fixture(Path(tmp))
            with self.assertRaises(ValueError): prepare(archive, artifact, {})
            artifact['images'][0]['imageSha256'] = '0' * 64
            with self.assertRaisesRegex(ValueError, 'bytes changed'): prepare(archive, artifact, review)

    def test_unknown_destinations_and_duplicate_approval_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, artifact, review, _ = self.fixture(Path(tmp))
            artifact['images'][0]['state'] = '../../main'
            with self.assertRaises(ValueError): prepare(archive, artifact, review)
            artifact['images'][0]['state'] = 'home'
            artifact['images'] *= 2
            with self.assertRaisesRegex(ValueError, 'Duplicate'): prepare(archive, artifact, review)

    def test_stale_source_and_wrong_archive_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, artifact, review, _ = self.fixture(Path(tmp))
            artifact['sourceCommit'] = 'b' * 40
            with self.assertRaisesRegex(ValueError, 'Stale'): prepare(archive, artifact, review)
            artifact['zipSha256'] = 'f' * 64
            with self.assertRaisesRegex(ValueError, 'digest mismatch'): prepare(archive, artifact, review)

    def test_api_cannot_mutate_a_ref_or_create_a_release(self):
        with patch('review_baselines.subprocess.run') as call:
            for endpoint in ('repos/blackkcold/CinemaTV/git/refs/heads/dev', 'repos/blackkcold/CinemaTV/releases', 'repos/elsewhere/other/git/blobs'):
                with self.assertRaises(ValueError): api(endpoint, {'arbitrary': True})
            call.assert_not_called()


if __name__ == '__main__': unittest.main()
