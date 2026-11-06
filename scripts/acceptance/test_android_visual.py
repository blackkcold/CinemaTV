import tempfile
from pathlib import Path
import unittest
from PIL import Image, ImageDraw
from android_visual import image_difference, visual_environment


class AndroidVisualTest(unittest.TestCase):
    def test_comparison_uses_full_4k_raster_without_masks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            Image.new('RGB', (3840, 2160), 'black').save(root / 'a.png')
            image = Image.new('RGB', (3840, 2160), 'black')
            ImageDraw.Draw(image).rectangle((0, 0, 3839, 199), fill='white')
            image.save(root / 'b.png')
            result = image_difference(root / 'a.png', root / 'b.png', root / 'diff.png')
            self.assertFalse(result['passed'])
            self.assertFalse(result['downsampled'])
            self.assertEqual(result['masks'], [])
            with Image.open(root / 'diff.png') as diff: self.assertEqual(diff.size, (3840, 2160))

    def test_identical_pixels_pass_and_different_raster_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            Image.new('RGB', (32, 18), 'black').save(root / 'a.png')
            self.assertTrue(image_difference(root / 'a.png', root / 'a.png', root / 'diff.png')['passed'])
            Image.new('RGB', (16, 9), 'black').save(root / 'b.png')
            with self.assertRaises(ValueError): image_difference(root / 'a.png', root / 'b.png', root / 'diff.png')

    def test_metadata_rejects_scaled_1080p_and_wrong_family(self):
        item = {'family': 'hybrid-android-production', 'activity': 'dev.cinematv.MainActivity', 'api': 36,
                'syntheticContent': True, 'width': 3840, 'height': 2160, 'densityDpi': 640,
                'provider': '111.0', 'fingerprint': 'synthetic',
                'dom': {'width': 960, 'height': 540, 'dpr': 4, 'reducedMotion': 'true', 'focus': 'nav:HOME', 'overflow': 0, 'quality': 'BASIC'}}
        self.assertEqual(visual_environment(item, (3840, 2160), 640)['dpr'], 4)
        item['dom']['dpr'] = 2
        with self.assertRaises(ValueError): visual_environment(item, (3840, 2160), 640)
        item['family'] = 'native-components'
        with self.assertRaises(ValueError): visual_environment(item, (3840, 2160), 640)


if __name__ == '__main__': unittest.main()
