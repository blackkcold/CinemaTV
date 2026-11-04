"""Source contract tests run before Gradle/network; full asset verifier runs after build."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class NotoTypographyContractTest(unittest.TestCase):
    def test_downloads_are_immutable_size_bounded_and_hashed(self):
        lock = json.loads((ROOT / 'scripts/fonts/noto-fonts.lock.json').read_text())
        self.assertEqual(len(lock['fonts']), 3)
        self.assertLess(sum(x['size'] for x in lock['fonts']), 21_000_000)
        for entry in lock['fonts']:
            self.assertRegex(entry['url'], r'^https://raw\.githubusercontent\.com/notofonts/[^/]+/[a-f0-9]{40}/')
            self.assertRegex(entry['sha256'], r'^[a-f0-9]{64}$')
            self.assertGreater(entry['size'], 100_000)
        script = (ROOT / 'app/noto-fonts.gradle.kts').read_text()
        for guard in ('digest(target) == expectedHash', 'digest(temporary) == expectedHash',
                      'total <= expectedSize', 'isOffline', 'instanceFollowRedirects = false',
                      'deadline', 'connectTimeout', 'readTimeout'):
            self.assertIn(guard, script)

    def test_generated_assets_and_licenses_are_packaged(self):
        self.assertIn('apply(from = "noto-fonts.gradle.kts")', (ROOT / 'app/build.gradle.kts').read_text())
        for name in ('OFL-NotoSans.txt', 'OFL-NotoSansCJK.txt'):
            self.assertIn('SIL OPEN FONT LICENSE Version 1.1', (ROOT / 'app/src/main/assets/fonts' / name).read_text())
        build = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/notoAssets"))', build)
        self.assertIn('tasks.named("preBuild") { dependsOn("prepareNotoFonts") }', build)
        script = (ROOT / 'app/noto-fonts.gradle.kts').read_text()
        self.assertNotIn('com.android.', script)
        self.assertNotIn('sourceSets', script)

    def test_both_surfaces_use_provider_and_font_only_theme_hook(self):
        source = ROOT / 'app/src/main/java/dev/cinematv'
        self.assertIn('CinemaNotoProvider {', (source / 'MainActivity.kt').read_text())
        for file in ('ui/mobile/MobileScreens.kt', 'ui/tv/design/TvTheme.kt'):
            self.assertIn('withCinemaNoto(cinemaUiFontFamily())', (source / file).read_text())
        for file in ('ui/mobile/MobileScreens.kt', 'ui/tv/components/CinemaHero.kt',
                     'ui/tv/components/FocusMediaCard.kt', 'ui/tv/pages/DetailScreen.kt'):
            self.assertRegex((source / file).read_text(), r'import dev\.cinematv\.ui\.typography\.Cinema(?:Tv)?Text as Text')


if __name__ == '__main__':
    unittest.main()
