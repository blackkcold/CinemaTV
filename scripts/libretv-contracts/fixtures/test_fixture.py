"""Offline fixture integrity only: never an upstream acceptance claim."""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
UUID = '6402b77c-b61f-4a06-96ca-c8420a2becf4'
MEDIA = f'https://video.blender.org/object-storage/web_videos/{UUID}-240.mp4'

class StaticFixtureTests(unittest.TestCase):
    def setUp(self):
        self.raw = (ROOT / 'blender-bbb-static-cms.json').read_bytes()
        self.data = json.loads(self.raw)
        self.manifest = json.loads((ROOT / 'blender-bbb-static-cms.manifest.json').read_bytes())
        self.item = self.data['list'][0]

    def test_digest(self):
        self.assertEqual(hashlib.sha256(self.raw).hexdigest(), self.manifest['fixture']['sha256'])

    def test_single_controlled_record(self):
        self.assertEqual(len(self.data['list']), 1)
        for key in ['page', 'pagecount', 'limit', 'total']:
            self.assertEqual(self.data[key], 1)
        self.assertIn('not a dynamic source API', self.data['msg'])
        self.assertEqual(self.item['vod_id'], UUID)
        self.assertEqual(self.item['vod_name'], self.manifest['provider']['name'])
        self.assertEqual(self.item['vod_pic'], '')

    def test_exact_media_and_credits(self):
        self.assertEqual(self.item['vod_play_url'], 'Full film (240p MP4)$' + MEDIA)
        self.assertIn('Retain the complete ending credits.', self.item['vod_content'])
        self.assertIn('https://creativecommons.org/licenses/by/3.0/', self.item['vod_content'])
        self.assertIn('Janus Bager Kristensen', self.item['vod_content'])
        self.assertIn('240p', self.item['vod_remarks'])

    def test_verified_provenance(self):
        self.assertEqual(self.manifest['provider']['licence'], {'id': 1, 'label': 'Attribution'})
        self.assertEqual(self.manifest['provider']['channel']['name'], 'blender_open_movies')
        self.assertEqual(self.manifest['provider']['account']['name'], 'blender')
        self.assertEqual(self.manifest['media']['url'], MEDIA)
        self.assertEqual(self.manifest['media']['httpStatus'], 206)
        self.assertEqual({s['codec_name'] for s in self.manifest['media']['probe']['streams']}, {'h264', 'aac'})
        self.assertFalse(self.manifest['fixture']['mediaBundled'])

if __name__ == '__main__':
    unittest.main(verbosity=2)
