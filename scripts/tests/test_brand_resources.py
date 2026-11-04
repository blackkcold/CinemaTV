"""Source/resource contract for the approved original A1 identity (stdlib only)."""
import math
import hashlib
import json
import subprocess
from pathlib import Path
import struct
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res'
A = '{http://schemas.android.com/apk/res/android}'


class BrandResourcesTest(unittest.TestCase):
    def test_banner_is_static_approved_size(self):
        data = (RES / 'drawable-nodpi/tv_banner.png').read_bytes()
        self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
        self.assertEqual(struct.unpack('>II', data[16:24]), (320, 180))
        self.assertNotIn(b'acTL', data)

    def test_manifest_launcher_contract(self):
        app = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot().find('application')
        self.assertEqual(app.get(A + 'banner'), '@drawable/tv_banner')
        self.assertEqual(app.get(A + 'icon'), '@mipmap/ic_launcher')
        categories = {x.get(A + 'name') for x in app.findall('.//category')}
        self.assertTrue({'android.intent.category.LAUNCHER', 'android.intent.category.LEANBACK_LAUNCHER'} <= categories)
        for name in ('ic_launcher', 'ic_launcher_round'):
            icon = ET.parse(RES / f'mipmap-anydpi-v26/{name}.xml').getroot()
            self.assertEqual(icon.tag, 'adaptive-icon')
            self.assertEqual(icon.find('foreground').get(A + 'drawable'), '@drawable/ic_launcher_foreground')
            self.assertEqual(icon.find('background').get(A + 'drawable'), '@drawable/ic_launcher_bg')

    def test_foreground_exact_original_path_and_safe_circle(self):
        icon = ET.parse(RES / 'drawable/ic_launcher_foreground.xml').getroot()
        source = ET.parse(ROOT / 'design/branding/cinematv-player-mark.svg').getroot()
        self.assertEqual(icon.find('.//path').get(A + 'pathData'), source.find('.//{*}path').get('d'))
        group = icon.find('group')
        self.assertEqual(float(group.get(A + 'scaleX')), 2.65)
        self.assertEqual(float(group.get(A + 'scaleY')), 2.65)
        self.assertEqual(float(group.get(A + 'translateX')), 160.875)
        self.assertEqual(float(group.get(A + 'translateY')), 172.8)
        # All line/quadratic control points lie inside this convex disk; add
        # the round stroke radius for a conservative complete-path bound.
        points = [(65,146),(65,64),(65,49),(78,56),(197,121),(210,128),
                  (197,135),(78,200),(65,207),(65,192),(65,183)]
        max_radius = max(math.hypot(x - 132.5, y - 128) for x,y in points) + 11
        self.assertLess(max_radius * 2.65, 1024 * 33 / 108)

    def test_wordmark_native_no_font_or_animation(self):
        vector = ET.parse(RES / 'drawable/cinema_brand_lockup.xml').getroot()
        self.assertEqual(vector.tag, 'vector')
        self.assertEqual(len(vector.findall('.//path')), 10)
        source = (ROOT / 'design/branding/cinematv-lockup.svg').read_text()
        self.assertNotIn('<text', source)
        for path in vector.findall('.//path'):
            self.assertIn(path.get(A + 'strokeColor') or path.get(A + 'fillColor'), ['#f5f5f3'])
        code = (ROOT / 'app/src/main/java/dev/cinematv/ui/shared/CinemaBrandLockup.kt').read_text()
        self.assertIn('stringResource(R.string.app_name)', code)
        self.assertNotIn('focusable', code)
        self.assertNotIn('rememberInfiniteTransition', code)

    def test_startup_is_bounded_and_has_static_fallbacks(self):
        code = (ROOT / 'app/src/main/java/dev/cinematv/ui/shared/CinemaBrandStartup.kt').read_text()
        for contract in ('6000f, tween(6000', 'rememberSaveable', 'areAnimatorsEnabled()',
                         '!reducedMotion && !lowCost && !lowRam', '0.42f, 0f, 0.58f, 1f',
                         '-90f, 150f, 12000f', '-170f, 240f, 9000f', '-260f, 360f, 6000f'):
            self.assertIn(contract, code)
        for forbidden in ('pointerInput', '.focusable(', 'rememberInfiniteTransition', 'MediaPlayer', 'WebView'):
            self.assertNotIn(forbidden, code)
        for layer in ('slow', 'medium', 'fast'):
            data = (RES / f'drawable-nodpi/cinema_brand_{layer}.png').read_bytes()
            self.assertEqual(struct.unpack('>II', data[16:24]), (640, 360))
            self.assertEqual(data[25], 6)  # RGBA, transparent preblurred layer
            self.assertLess(len(data), 40000)

    def test_session_is_hoisted_above_navigation(self):
        activity = (ROOT / 'app/src/main/java/dev/cinematv/MainActivity.kt').read_text()
        self.assertIn('CinemaBrandSession(', activity)
        self.assertIn('reducedMotion = model.reducedMotion', activity)
        self.assertIn('lowCost = model.glassQuality == GlassQuality.BASIC', activity)
        for relative in ('ui/tv/components/FloatingNavigation.kt', 'ui/mobile/MobileScreens.kt'):
            code = (ROOT / 'app/src/main/java/dev/cinematv' / relative).read_text()
            self.assertIn('CinemaBrandStartup(', code)
            self.assertNotIn('CinemaBrandSession(', code)

    def test_reviewed_raster_hashes(self):
        manifest = json.loads((ROOT / 'design/branding/approved-raster-sha256.json').read_text())
        self.assertEqual(len(manifest['files']), 6)
        for relative, expected in manifest['files'].items():
            with self.subTest(asset=relative):
                self.assertEqual(hashlib.sha256((ROOT / relative).read_bytes()).hexdigest(), expected,
                                 'Raster drift: review pixels and explicitly approve replacement; do not auto-update hashes.')

    def test_brand_workflow_cannot_write_or_rerender_assets(self):
        workflow = (ROOT / '.github/workflows/brand-assets.yml').read_text()
        self.assertIn('contents: read', workflow)
        self.assertIn('persist-credentials: false', workflow)
        self.assertIn('pull_request:', workflow)
        self.assertIn("test_brand_resources.py", workflow)
        for forbidden in ('contents: write', 'git push', 'git commit', 'run: bash scripts/render-branding.sh'):
            self.assertNotIn(forbidden, workflow)

    def test_manual_renderer_requires_separate_review_destination(self):
        script = ROOT / 'scripts/render-branding.sh'
        for args in ([], [str(RES / 'drawable-nodpi')]):
            result = subprocess.run(['bash', str(script), *args], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)


if __name__ == '__main__':
    unittest.main()
