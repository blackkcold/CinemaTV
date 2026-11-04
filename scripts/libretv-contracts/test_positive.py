"""Offline safety/integrity tests, not upstream acceptance."""
import importlib.util
from pathlib import Path
import unittest
from urllib.request import Request

spec = importlib.util.spec_from_file_location('positive', Path(__file__).with_name('positive.py'))
positive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(positive)

class PositiveHarnessTests(unittest.TestCase):
    def test_only_immutable_authorized_fixture(self):
        url = 'https://raw.githubusercontent.com/blackkcold/CinemaTV/' + 'a' * 40 + '/scripts/libretv-contracts/fixtures/blender-bbb-static-cms.json'
        self.assertEqual(positive.validate_url(url), url)
        for wrong in [url.replace('a' * 40, 'main'), url + '?key=value', url.replace('CinemaTV', 'other'), url.replace('https:', 'http:'), 'http://127.0.0.1/cms', url.replace('raw.githubusercontent.com', 'example.org')]:
            with self.subTest(url=wrong), self.assertRaises(ValueError):
                positive.validate_url(wrong)

    def test_redirects_rejected(self):
        self.assertIsNone(positive.NoRedirect().redirect_request(Request('https://example.org'), None, 302, '', {}, 'http://127.0.0.1'))

    def test_env_remains_isolated(self):
        env = positive.base.child_env(12345, 'synthetic')
        self.assertEqual(set(env), {'PATH', 'HOSTNAME', 'PORT', 'NODE_ENV', 'NEXT_TELEMETRY_DISABLED', 'PASSWORD'})
        self.assertEqual(env['HOSTNAME'], '127.0.0.1')

    def test_fixed_permitted_media(self):
        self.assertEqual(positive.MEDIA, 'https://video.blender.org/object-storage/web_videos/6402b77c-b61f-4a06-96ca-c8420a2becf4-240.mp4')

if __name__ == '__main__':
    unittest.main(verbosity=2)
