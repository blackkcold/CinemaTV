"""Offline contracts for the published README gallery (standard library only)."""
import hashlib
import json
from pathlib import Path
import re
import struct
import unittest
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
IMAGES = ROOT / 'docs/images'


class ReadmeAssetsTest(unittest.TestCase):
    def test_gallery_bytes_and_provenance(self):
        manifest = json.loads((IMAGES / 'manifest.json').read_text())
        self.assertRegex(manifest['release'], r'^v[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9.-]+)?$')
        self.assertEqual(manifest['release_url'],
                         'https://github.com/blackkcold/CinemaTV/releases/tag/' + manifest['release'])
        self.assertRegex(manifest['evidence_sha256'], r'^[0-9a-f]{64}$')
        self.assertRegex(manifest['source_run'], r'^https://github.com/blackkcold/CinemaTV/actions/runs/[0-9]+$')
        names = [entry['file'] for entry in manifest['images']]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(set(names), {path.name for path in IMAGES.glob('*.png')})
        for entry in manifest['images']:
            with self.subTest(file=entry['file']):
                self.assertEqual(Path(entry['file']).name, entry['file'])
                data = (IMAGES / entry['file']).read_bytes()
                self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
                self.assertEqual(hashlib.sha256(data).hexdigest(), entry['sha256'])
                width, height = struct.unpack('>II', data[16:24])
                self.assertGreater(width, 800)
                self.assertGreater(height, 600)
                self.assertEqual(entry['modifications'], 'none')
                self.assertRegex(entry['checkout_sha'], r'^[0-9a-f]{40}$')
                self.assertRegex(entry['checkout_tree'], r'^[0-9a-f]{40}$')
                self.assertTrue(entry['archive_entry'].endswith('.png'))

    def test_relative_links_and_image_alternatives(self):
        for relative in ('README.md', 'docs/INSTALL.md', 'docs/images/README.md'):
            path = ROOT / relative
            body = path.read_text()
            links = re.findall(r'\]\(([^\s)]+)\)|(?:src|href)="([^"]+)"', body)
            for markdown, html in links:
                target = markdown or html
                parsed = urlsplit(target)
                if parsed.scheme or parsed.netloc or not parsed.path:
                    continue
                resolved = (path.parent / unquote(parsed.path)).resolve()
                with self.subTest(document=relative, target=target):
                    self.assertTrue(resolved.is_relative_to(ROOT))
                    self.assertTrue(resolved.exists(), f'Missing relative target: {target}')
            for image in re.findall(r'<img\b[^>]*>', body):
                self.assertRegex(image, r'\balt="[^"\s][^"]*"')

    def test_readme_uses_documented_release_images(self):
        body = (ROOT / 'README.md').read_text()
        manifest = json.loads((IMAGES / 'manifest.json').read_text())
        self.assertIn(manifest['release'], body)
        images = set(re.findall(r'src="docs/images/([^"/]+)"', body))
        self.assertEqual(images, {entry['file'] for entry in manifest['images']})
        self.assertIn('docs/images/README.md', body)
        self.assertIn('https://img.shields.io/github/v/release/blackkcold/CinemaTV?label=stable', body)
        self.assertNotIn('include_prereleases', body)
        self.assertNotIn('/releases/latest', body)


if __name__ == '__main__':
    unittest.main()
