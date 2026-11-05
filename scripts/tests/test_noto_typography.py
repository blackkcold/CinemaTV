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
        for guard in ('valid(target, expectedSize, expectedHash)', 'digest(temporary) != expectedHash',
                      'total > expectedSize', 'startParameter.offline', 'instanceFollowRedirects = false',
                      'deadline', 'connectTimeout', 'readTimeout', "File.createTempFile(name + '.', '.part', cacheDirectory)"):
            self.assertIn(guard, script)

    def test_cjk_uses_weight_aware_descriptor_and_compose_regression(self):
        source = ROOT / 'app/src/main/java/dev/cinematv/ui/typography'
        loader = (source / 'CinemaNoto.kt').read_text()
        self.assertIn('FontFamily(PreloadedNotoFont(typeface))', loader)
        self.assertNotIn('else FontFamily(typeface)', loader)
        descriptor = (source / 'PreloadedNotoFont.kt').read_text()
        self.assertIn('override val weight: FontWeight = FontWeight.Normal', descriptor)
        self.assertIn('(font as PreloadedNotoFont).typeface', descriptor)
        self.assertNotIn('Typeface.Builder', descriptor)
        self.assertNotIn('assets.open', descriptor)
        regression = (ROOT / 'app/src/androidTest/java/dev/cinematv/ui/typography/NotoComposeWeightEvidence.kt').read_text()
        for required in ('CinemaText(', 'CinemaTvText(', 'FontSynthesis.None',
                         'bold.isBold', 'NotoCompositorCapture.capture(', 'boldInk > normalInk',
                         'regular/no-synthesis pixels differ', 'boundsInWindow'):
            self.assertIn(required, regression)
        self.assertNotIn('isFakeBoldText', regression)
        self.assertNotIn('captureToImage', regression)
        capture = (ROOT / 'app/src/androidTest/java/dev/cinematv/ui/typography/NotoCompositorCapture.kt').read_text()
        for required in ('getLocationOnScreen', 'getLocationInWindow', 'automation.takeScreenshot()',
                         'future.get(TIMEOUT_SECONDS', 'if (abandoned)', 'bitmap.recycle()',
                         'settleFreshFrame', 'isFrameAtOrAfterDraw(draw, targetEpoch, actualVsync)', 'activity.hasWindowFocus()',
                         'if (!waitForRoot) check(windowReady)',
                         'if (windowReady && lastPackage == activity.packageName) return'):
            self.assertIn(required, capture)
        self.assertNotIn('captureToImage', capture)
        self.assertIn('check(Build.VERSION.SDK_INT == 26)', capture)
        self.assertIn('PixelCopy.request(activity.window, Rect(windowBounds), bitmap', capture)
        self.assertIn('WindowManager.LayoutParams.FLAG_SECURE == 0', capture)
        self.assertIn('result.get() == PixelCopy.SUCCESS', capture)
        self.assertIn('windowBounds.width().toLong() * windowBounds.height() <= 2_000_000L', capture)
        self.assertIn('if (!published) bitmap.recycle()', capture)
        self.assertNotIn('clearFlags', capture)
        self.assertNotIn('addFlags', capture)
        self.assertIn('val windowRegionCapture = Build.VERSION.SDK_INT == 26', regression)
        self.assertIn('else NotoCompositorCapture.capture(activity, host.get(), location)', regression)
        self.assertIn('if (windowRegionCapture) JSONObject.NULL else captureWidth', regression)

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
        self.assertIn("rootProject.layout.projectDirectory.dir('.cache/cinematv-noto-fonts')", script)
        self.assertNotIn('outputs.dir(notoDownloadCache)', script)
        self.assertIn('copyVerified(cachedFont, target, expectedSize, expectedHash)', script)
        self.assertIn('valid(cachedFont, expectedSize, expectedHash)', script)
        self.assertFalse((ROOT / 'app/noto-fonts.gradle.kts').exists())
        self.assertIn('noCompress += listOf("ttc", "ttf")', build)

    def test_font_evidence_waits_for_real_window_entry_and_rejects_blended_background(self):
        host = (ROOT / 'app/src/debug/java/dev/cinematv/NotoTestActivity.kt').read_text()
        for required in ('CountDownLatch(1)', 'override fun onEnterAnimationComplete()',
                         'super.onEnterAnimationComplete()', 'enterAnimationFinished.countDown()',
                         'Looper.myLooper() != Looper.getMainLooper()',
                         'enterAnimationFinished.await(timeoutMillis, TimeUnit.MILLISECONDS) && !hasStopped',
                         'override fun onStop()', 'hasStopped = true'):
            self.assertIn(required, host)
        self.assertEqual(host.count('enterAnimationFinished.countDown()'), 1)
        self.assertNotIn('onResume', host)
        self.assertNotIn('Thread.sleep', host)
        manifest = (ROOT / 'app/src/debug/AndroidManifest.xml').read_text()
        self.assertIn('<activity android:name=".NotoTestActivity" android:exported="false" />', manifest)
        self.assertNotIn('NotoTestActivity', (ROOT / 'app/src/main/AndroidManifest.xml').read_text())
        test = (ROOT / 'app/src/androidTest/java/dev/cinematv/ui/typography/NotoFontsDeviceTest.kt').read_text()
        self.assertIn('val compose = AndroidComposeTestRule(', test)
        self.assertIn('ActivityScenarioRule<NotoTestActivity>(Intent(', test)
        self.assertLess(test.index('compose.activity.awaitEnterAnimationComplete(10_000)'),
                        test.index('NotoComposeWeightEvidence.capture('))
        self.assertIn('noAnimationLaunchStillReportsEnterTransitionComplete', test)
        self.assertIn('Intent.FLAG_ACTIVITY_NO_ANIMATION', test)
        self.assertIn('activity.awaitEnterAnimationComplete(10_000)', test)
        evidence = (ROOT / 'app/src/androidTest/java/dev/cinematv/ui/typography/NotoComposeWeightEvidence.kt').read_text()
        self.assertIn('backgroundGuards.getValue(tag).all { it == android.graphics.Color.BLACK }', evidence)
        self.assertLess(evidence.index('File(folder, "$label-capture.json").writeText'),
                        evidence.index('background is not opaque black'))
        self.assertIn('NotoCompositorCapture.settleFreshFrame(', evidence)
        self.assertIn('compose.waitForIdle()', evidence)
        capture = (ROOT / 'app/src/androidTest/java/dev/cinematv/ui/typography/NotoCompositorCapture.kt').read_text()
        self.assertIn('activity !is NotoTestActivity || !activity.hasStopped', capture)

    def test_font_frame_fence_binds_first_target_draw_to_public_actual_vsync(self):
        source = ROOT / 'app/src/androidTest/java/dev/cinematv/ui/typography'
        capture = (source / 'NotoCompositorCapture.kt').read_text()
        for required in ('object : Choreographer.FrameCallback', 'override fun doFrame(frameTimeNanos: Long)',
                         'epoch.frameTime.set(frameTimeNanos)', 'firstDraw.compareAndSet(null, DrawStamp(epoch, timestamp))',
                         'FrameMetrics.VSYNC_TIMESTAMP', 'draw.epoch == targetEpoch',
                         'metricsVsyncNanos >= draw.frameTimeNanos', 'removeFrameCallback(frameCallback)',
                         'tracker.disarm(epoch)'):
            self.assertIn(required, capture)
        for forbidden in ('INTENDED_VSYNC_TIMESTAMP', 'System.nanoTime()', 'getFrameTimeNanos', 'rendered()'):
            self.assertNotIn(forbidden, capture)
        self.assertLess(capture.index('addOnFrameMetricsAvailableListener'),
                        capture.index('choreographer!!.postFrameCallback(frameCallback)'))
        evidence = (source / 'NotoComposeWeightEvidence.kt').read_text()
        self.assertLess(evidence.index('drawContent()'), evidence.index('drawTracker.recordDraw(currentEpoch)'))
        for required in ('firstDrawFrameTimeNanos', 'matchedActualVsyncNanos', 'freshDrawEpoch'):
            self.assertIn(required, evidence)
        test = (source / 'NotoFontsDeviceTest.kt').read_text()
        self.assertIn('drawEpochFenceRejectsUnarmedAndStaleFrameMetrics', test)
        self.assertIn('Redraws must not move the first-draw fence', test)

    def test_ci_font_cache_uses_only_exact_lock_and_asset_path(self):
        expected_key = "noto-fonts-v2-${{ hashFiles('scripts/fonts/noto-fonts.lock.json') }}"
        for name in ('android.yml', 'tv-smoke.yml'):
            workflow = (ROOT / '.github/workflows' / name).read_text()
            blocks = re.findall(
                r'^      - name: Restore pinned offline Noto fonts\n(.*?)(?=^      - |\Z)',
                workflow, flags=re.MULTILINE | re.DOTALL)
            self.assertEqual(len(blocks), 1, name)
            block = blocks[0]
            self.assertIn('uses: actions/cache@v4', block)
            self.assertIn('path: .cache/cinematv-noto-fonts\n', block)
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
