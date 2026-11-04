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
        script = (ROOT / 'app/noto-fonts.gradle').read_text()
        for guard in ('digest(target) == expectedHash', 'digest(temporary) != expectedHash',
                      'total > expectedSize', 'startParameter.offline', 'instanceFollowRedirects = false',
                      'deadline', 'connectTimeout', 'readTimeout', '${name}.part'):
            self.assertIn(guard, script)

    def test_generated_assets_and_licenses_are_packaged(self):
        self.assertIn('apply(from = "noto-fonts.gradle")', (ROOT / 'app/build.gradle.kts').read_text())
        for name in ('OFL-NotoSans.txt', 'OFL-NotoSansCJK.txt'):
            self.assertIn('SIL OPEN FONT LICENSE Version 1.1', (ROOT / 'app/src/main/assets/fonts' / name).read_text())
        build = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/notoAssets"))', build)
        self.assertIn('tasks.named("preBuild") { dependsOn("prepareNotoFonts") }', build)
        script = (ROOT / 'app/noto-fonts.gradle').read_text()
        self.assertNotIn('com.android.', script)
        self.assertNotIn('sourceSets', script)
        self.assertFalse((ROOT / 'app/noto-fonts.gradle.kts').exists())
        self.assertIn('noCompress += listOf("ttc", "ttf")', build)

    def test_ci_font_cache_uses_only_exact_lock_and_asset_path(self):
        expected_key = "noto-fonts-v1-${{ hashFiles('scripts/fonts/noto-fonts.lock.json') }}"
        for name in ('android.yml', 'tv-smoke.yml'):
            workflow = (ROOT / '.github/workflows' / name).read_text()
            blocks = re.findall(
                r'^      - name: Restore pinned offline Noto fonts\n(.*?)(?=^      - |\Z)',
                workflow, flags=re.MULTILINE | re.DOTALL)
            self.assertEqual(len(blocks), 1, name)
            block = blocks[0]
            self.assertIn('uses: actions/cache@v4', block)
            self.assertIn('path: app/build/generated/notoAssets/fonts\n', block)
            self.assertIn('key: ' + expected_key + '\n', block)
            self.assertNotIn('restore-keys:', block)
            self.assertNotIn('if:', block)

    def test_font_cache_does_not_skip_verification_or_standalone_build(self):
        for name, build_step in (('android.yml', 'Build, lint and test'),
                                 ('tv-smoke.yml', 'Compile instrumentation before allocating emulator memory')):
            workflow = (ROOT / '.github/workflows' / name).read_text()
            verify = '      - name: Verify exact Noto font bytes before further gates\n        run: python3 scripts/fonts/verify-noto-fonts.py\n'
            self.assertEqual(workflow.count(verify), 1, name)
            self.assertLess(workflow.index('Restore pinned offline Noto fonts'), workflow.index(build_step))
            self.assertLess(workflow.index(build_step), workflow.index(verify))
            block = re.search(r'^      - name: ' + re.escape(build_step) +
                              r'\n(.*?)(?=^      - |\Z)', workflow,
                              flags=re.MULTILINE | re.DOTALL).group(1)
            self.assertNotIn('cache-hit', block)
            self.assertNotIn('if:', block)
            self.assertIn(':app:assembleDebug', block)
            if name == 'tv-smoke.yml':
                self.assertLess(workflow.index(verify), workflow.index('      - name: Enable KVM'))
                self.assertIn('  workflow_dispatch:', workflow)
                self.assertIn('  schedule:', workflow)
                self.assertIn('  workflow_call:', workflow)

    def test_both_surfaces_use_provider_and_font_only_theme_hook(self):
        source = ROOT / 'app/src/main/java/dev/cinematv'
        self.assertIn('CinemaNotoProvider {', (source / 'MainActivity.kt').read_text())
        host = (ROOT / 'app/src/debug/java/dev/cinematv/TvTestActivity.kt').read_text()
        self.assertIn('CinemaNotoProvider {', host)
        self.assertIn('bundledFontsReady = fonts.regional.size == NotoRegion.entries.size', host)
        regression = (ROOT / 'app/src/androidTest/java/dev/cinematv/TvUiRegressionTest.kt').read_text()
        self.assertIn('private fun awaitBundledFonts()', regression)
        self.assertIn('private fun screenshot(name: String, waitForArt: Boolean = true) {\n        awaitBundledFonts()', regression)
        for file in ('ui/mobile/MobileScreens.kt', 'ui/tv/design/TvTheme.kt'):
            self.assertIn('withCinemaNoto(cinemaUiFontFamily())', (source / file).read_text())
        for file in ('ui/mobile/MobileScreens.kt', 'ui/tv/components/CinemaHero.kt',
                     'ui/tv/components/FocusMediaCard.kt', 'ui/tv/pages/DetailScreen.kt'):
            self.assertRegex((source / file).read_text(), r'import dev\.cinematv\.ui\.typography\.Cinema(?:Tv)?Text as Text')


if __name__ == '__main__':
    unittest.main()
