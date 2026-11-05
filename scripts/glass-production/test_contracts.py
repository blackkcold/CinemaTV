"""Host-side harness tests. Synthetic PNGs here validate the validator, not the app."""
import copy
import json
from pathlib import Path
import tempfile
import subprocess
from unittest.mock import Mock, patch
import unittest
import xml.etree.ElementTree as ET
from evidence import digest, verify_captures
from run_device import PRODUCTION, BUTTON, BUTTON_EDGE, MATERIAL, passed_instrumentation, reject_errors
from test_log import TestLogBoundary, TAG

ROOT = Path(__file__).resolve().parents[2]
SHA = "a" * 40


def output_for(tests):
    output = []
    for cls, method in tests:
        for code in (1, 0):
            output.extend((f"INSTRUMENTATION_STATUS: class={cls}", f"INSTRUMENTATION_STATUS: test={method}",
                           f"INSTRUMENTATION_STATUS: numtests={len(tests)}", f"INSTRUMENTATION_STATUS_CODE: {code}"))
    return "\n".join(output + [f"OK ({len(tests)} tests)", "INSTRUMENTATION_CODE: -1", ""])


class InstrumentationContract(unittest.TestCase):
    def test_only_exact_executed_tests_pass(self):
        expected = [PRODUCTION, BUTTON, BUTTON_EDGE, MATERIAL]
        self.assertTrue(passed_instrumentation(output_for(expected), expected))
        for changed in (output_for(expected).replace("STATUS_CODE: 0", "STATUS_CODE: -3", 1),
                        output_for(expected).replace("STATUS_CODE: 0", "STATUS_CODE: -4", 1),
                        output_for(expected).replace("STATUS_CODE: 0", "STATUS_CODE: -2", 1),
                        output_for(expected).replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                        output_for(expected[:-1]), output_for(expected + [PRODUCTION]),
                        "OK (3 tests)\nINSTRUMENTATION_CODE: -1\n", output_for(expected) + "Process crashed."):
            with self.subTest(changed=changed[-120:]):
                self.assertFalse(passed_instrumentation(changed, expected))

    def test_host_uses_actual_roots_with_no_capability_override(self):
        source = (ROOT / "app/src/debug/java/dev/cinematv/ProductionGlassTestActivity.kt").read_text()
        self.assertIn("if (landscape) TvRootApp(model) else MobileRootApp(model)", source)
        for required in ("CinemaNotoProvider", "LocalGlassDrawDiagnostics provides glassDraws", "LocalProductionGlassObserver", "LocalProductionSceneDecoded"):
            self.assertIn(required, source)
        for forbidden in ("LocalTvEffects provides", "windowReady = true", "rememberLayerBackdrop", "GlassControlMaterial(", "RuntimeShader("):
            self.assertNotIn(forbidden, source)
        tree = ET.parse(ROOT / "app/src/debug/AndroidManifest.xml")
        android = "{http://schemas.android.com/apk/res/android}"
        host = [node for node in tree.iter("activity") if node.get(android + "name") == ".ProductionGlassTestActivity"]
        self.assertEqual(1, len(host))
        self.assertEqual("false", host[0].get(android + "exported"))
        self.assertNotIn("ProductionGlassTestActivity", (ROOT / "app/src/main/AndroidManifest.xml").read_text())

    def test_real_pixels_and_policy_observations_are_required(self):
        source = (ROOT / "app/src/androidTest/java/dev/cinematv/ProductionGlassUiTest.kt").read_text()
        for required in ("TvWindowCapture.capture", "resolveProductionGlass(requested, actual.environment)",
                         "actual.environment.windowReady", "counts()[counter(effective)] > 0", "assertIsNotSelected()", "performTouchInput", "Espresso.pressBack()"):
            self.assertIn(required, source)
        for forbidden in ("captureToImage(", "PixelCopy", "forceRedraw(", "createBitmap(", "createScaledBitmap("):
            self.assertNotIn(forbidden, source)

    def test_core_lock_filters_external_jars_before_resolving_android_library(self):
        source = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn("incoming.artifactView", source)
        self.assertIn("componentFilter", source)
        self.assertIn("ModuleComponentIdentifier", source)
        self.assertIn("ArtifactTypeDefinition.JAR_TYPE", source)
        self.assertIn("expected.getValue(artifact.file.name)", source)
        self.assertNotIn("resolvedConfiguration.resolvedArtifacts", source)

    def test_api26_capture_uses_matching_physical_tv_framebuffer(self):
        workflow = (ROOT / ".github/workflows/production-glass.yml").read_text()
        self.assertIn("matrix.api == 26 && '-skin 1920x1080'", workflow)
        source = (ROOT / "app/src/androidTest/java/dev/cinematv/ProductionGlassUiTest.kt").read_text()
        self.assertIn('tab("电影").assertIsFocused().assertIsNotSelected()', source)
        self.assertIn('key(Key.DirectionDown)', source)

    def test_draw_scope_is_passive_and_captured_before_material_draw(self):
        diagnostics = (ROOT / "app/src/main/java/dev/cinematv/ui/tv/components/GlassBackdropFrame.kt").read_text()
        material = (ROOT / "app/src/main/java/dev/cinematv/ui/tv/components/GlassControlMaterial.kt").read_text()
        host = (ROOT / "app/src/debug/java/dev/cinematv/ProductionGlassTestActivity.kt").read_text()
        test = (ROOT / "app/src/androidTest/java/dev/cinematv/ProductionGlassUiTest.kt").read_text()
        self.assertIn("staticCompositionLocalOf<String?> { null }", diagnostics)
        self.assertIn("if (scope != null)", diagnostics)
        for forbidden in ("mutableStateOf", "mutableIntStateOf", "invalidate", "SnapshotStateMap"):
            self.assertNotIn(forbidden, diagnostics.replace("never invalidate", ""))
        body = material.split("fun Modifier.cinemaGlassMaterial(", 1)[1]
        self.assertLess(body.index("val diagnosticScope = LocalGlassDrawScope.current"), body.index(".drawWithContent"))
        self.assertIn("diagnosticScope, sourcePresent = source != null", material)
        self.assertNotIn("model.page", material)
        self.assertIn("LocalGlassDrawScope provides model.page.name", host)
        phone = test.split("private fun phoneRoutes", 1)[1].split("private fun capture", 1)[0]
        self.assertLess(phone.index("clearScope(Page.DETAIL.name)"), phone.index("performTouchInput { click() }"))
        self.assertIn("scopedCounts(Page.DETAIL.name).basicDraws > 0", phone)
        self.assertIn("detail.sourcePresentDraws", phone)
        self.assertNotIn("assertEquals(0, counts()[", phone)

    def test_ci_reuses_apks_without_rebuilding_or_media_suite(self):
        workflow = (ROOT / ".github/workflows/production-glass.yml").read_text()
        self.assertIn("branches: [feature/production-glass-v2]", workflow)
        self.assertNotIn("pull_request:", workflow)
        native = workflow.split("  native-ui:", 1)[1]
        for forbidden in ("gradle/actions", "connectedDebugAndroidTest", "run-device-qa.sh", "OfficialLibreTv", "TvUiRegressionTest"):
            self.assertNotIn(forbidden, native)
        script = (ROOT / "scripts/glass-production/run-matrix.sh").read_text()
        self.assertEqual(2, script.count("--kill-after=10s 10m"))
        self.assertIn('if [[ "$1" == 35 ]]', script)


class EvidenceContract(unittest.TestCase):
    def fixture(self, directory):
        from PIL import Image, ImageDraw
        # Deliberately synthetic host-test raster. Never copied to qa/ or artifacts.
        image = Image.new("RGB", (1920, 1080), "#102030")
        ImageDraw.Draw(image).rectangle((400, 80, 1700, 900), fill="#a0c0e0")
        captures = []
        for index, mode in enumerate(("ENHANCED", "BACKDROP", "BASIC")):
            ImageDraw.Draw(image).rectangle((500, 100, 600, 200), fill=(index * 80, 100, 200))
            for route in ("home", "navigation", "detail"):
                name = f"{mode.lower()}-{route}.png"
                image.save(directory / name)
                renderer = {"ENHANCED": "FULL", "BACKDROP": "BLUR", "BASIC": "BASIC"}[mode]
                draws = {"full": 0, "blur": 0, "basic": 0}
                draws[renderer.lower()] = 2
                captures.append({"file": name, "sha256": digest(directory / name), "sourceSha": SHA, "sdk": 35,
                                 "widthPx": 1920, "heightPx": 1080, "densityDpi": 320, "requested": mode,
                                 "renderer": renderer, "policyMode": renderer, "route": "HOME" if route != "detail" else "DETAIL",
                                 "reason": "REQUESTED_" + renderer, "failed": False, "draws": draws,
                                 "decodedScene": "android.resource://dev.cinematv/123", "foregroundVerified": True,
                                 "nativeWindowFrameFence": True, "environment": {"api": 35, "userReducedMotion": True,
                                     "hardwareAccelerated": True, "windowReady": True, "lowRam": False,
                                     "memoryClassMb": 256, "powerSave": False}})
        (directory / "focus-trace.txt").write_text("route=MOVIES\nroute=DETAIL\nBack\n")
        return {"completed": True, "sourceSha": SHA, "sdk": 35, "layout": "tv",
                "host": "dev.cinematv.ProductionGlassTestActivity", "root": "TvRootApp", "captures": captures}

    def test_phone_detail_requires_scoped_draws_and_no_inherited_scene(self):
        from PIL import Image, ImageDraw
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            manifest = self.fixture(directory)
            manifest.update(layout="phone", root="MobileRootApp")
            for index, capture in enumerate(manifest["captures"]):
                image = Image.new("RGB", (1080, 2400), "#102030")
                ImageDraw.Draw(image).rectangle((100, 100, 900, 2000), fill=(80 + index * 10, 170, 220))
                image.save(directory / capture["file"])
                capture.update(widthPx=1080, heightPx=2400, densityDpi=420, sha256=digest(directory / capture["file"]))
                if capture["file"].endswith("-detail.png"):
                    capture["globalDraws"] = dict(capture["draws"], basic=3)
                    capture.update(renderer="BASIC", drawScope="DETAIL", sourcePresentDraws=0,
                                   draws={"full": 0, "blur": 0, "basic": 3})
            def check(value):
                (directory / "captures.json").write_text(json.dumps(value))
                return verify_captures(directory, SHA, 35, "phone")
            # Legitimate old Home Full/Blur remains visible in globalDraws and
            # does not contaminate the new Detail's independently recorded scope.
            self.assertGreater(manifest["captures"][2]["globalDraws"]["full"], 0)
            self.assertEqual("passed", check(manifest)["result"])
            for key, value in (("drawScope", "HOME"), ("sourcePresentDraws", 1),
                               ("draws", {"full": 1, "blur": 0, "basic": 3}),
                               ("draws", {"full": 0, "blur": 0, "basic": 0})):
                broken = copy.deepcopy(manifest)
                broken["captures"][2][key] = value
                with self.subTest(field=key), self.assertRaises(AssertionError):
                    check(broken)

    def test_native_contract_and_invalid_receipts(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            manifest = self.fixture(directory)
            def check(value):
                (directory / "captures.json").write_text(json.dumps(value))
                return verify_captures(directory, SHA, 35, "tv")
            self.assertEqual("passed", check(manifest)["result"])
            changes = [("completed", False), ("sourceSha", "b" * 40)]
            for key, value in changes:
                broken = copy.deepcopy(manifest)
                broken[key] = value
                with self.assertRaises(AssertionError):
                    check(broken)
            for key, value in (("sha256", "0" * 64), ("foregroundVerified", False), ("nativeWindowFrameFence", False),
                               ("renderer", "BASIC"), ("widthPx", 960), ("failed", True)):
                broken = copy.deepcopy(manifest)
                broken["captures"][0][key] = value
                with self.subTest(field=key), self.assertRaises(AssertionError):
                    check(broken)
            broken = copy.deepcopy(manifest)
            broken["captures"][0]["draws"]["full"] = 0
            with self.assertRaises(AssertionError):
                check(broken)
            broken = copy.deepcopy(manifest)
            broken["captures"].pop()
            with self.assertRaises(AssertionError):
                check(broken)


class LogBoundaryContract(unittest.TestCase):
    def reader(self, directory, during="", marker_tag=TAG):
        calls = []
        process = Mock()
        process.poll.return_value = None
        def adb(*args):
            calls.append(args)
            if args[:2] == ("shell", "date"):
                return "11-05_02:45:30\n"
            if args[:2] == ("shell", "log"):
                marker = args[-1]
                path = directory / "live-test.log"
                with path.open("a") as stream:
                    if marker.startswith("START:"):
                        stream.write("11-05 02:45:29.999 1 1 E OldBoot : FATAL EXCEPTION old startup\n")
                    else:
                        stream.write(during)
                    stream.write(f"11-05 02:45:30.100 100 100 I {marker_tag}: {marker}\n")
                return ""
            raise AssertionError(f"Unexpected adb command: {args}")
        return adb, process, calls

    def test_marker_span_excludes_startup_and_never_clears_buffers(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            adb, process, calls = self.reader(directory)
            boundary = TestLogBoundary(directory, adb)
            with patch("test_log.subprocess.Popen", return_value=process) as popen:
                boundary.start()
                checked = boundary.finish()
                boundary.close()
            reject_errors(checked)
            self.assertNotIn("old startup", checked)
            self.assertIn("old startup", (directory / "live-test.log").read_text())
            self.assertEqual(checked, (directory / "device.log").read_text())
            self.assertFalse(any("-c" in command for command in calls))
            args = popen.call_args.args[0]
            self.assertEqual(["main", "system", "crash"], [args[i + 1] for i, value in enumerate(args) if value == "-b"])
            self.assertEqual("11-05 02:45:30.000", args[args.index("-T") + 1])
            self.assertEqual("complete", json.loads((directory / "log-boundary.json").read_text())["state"])

    def test_crash_inside_verified_span_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            adb, process, _ = self.reader(directory, "11-05 02:45:31.100 120 120 E AndroidRuntime: FATAL EXCEPTION main\n")
            boundary = TestLogBoundary(directory, adb)
            with patch("test_log.subprocess.Popen", return_value=process):
                boundary.start()
                checked = boundary.finish()
                boundary.close()
            with self.assertRaises(AssertionError):
                reject_errors(checked)

    def test_reader_failure_is_recorded_and_not_ignored(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            adb, process, _ = self.reader(directory)
            process.poll.return_value = 1
            boundary = TestLogBoundary(directory, adb)
            with patch("test_log.subprocess.Popen", return_value=process):
                with self.assertRaisesRegex(RuntimeError, "reader exited"):
                    boundary.start()
                boundary.close()
            receipt = json.loads((directory / "log-boundary.json").read_text())
            self.assertEqual("failed", receipt["state"])
            self.assertIn("status 1", receipt["error"])
            self.assertFalse((directory / "device.log").exists())

    def test_marker_failure_preserves_command_output(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            adb, process, _ = self.reader(directory)
            def denied(*args):
                if args[:2] == ("shell", "log"):
                    raise subprocess.CalledProcessError(1, args, output="log: permission denied")
                return adb(*args)
            boundary = TestLogBoundary(directory, denied)
            with patch("test_log.subprocess.Popen", return_value=process):
                with self.assertRaises(subprocess.CalledProcessError):
                    boundary.start()
                boundary.close()
            receipt = json.loads((directory / "log-boundary.json").read_text())
            self.assertEqual("log: permission denied", receipt["commandOutput"])
            self.assertEqual("failed", receipt["state"])

    def test_quoted_marker_wrong_tag_and_missing_end_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            boundary = TestLogBoundary(directory, Mock())
            (directory / "live-test.log").write_text(f"11-05 02:45:30.100 1 1 I OtherTag: {boundary.start_marker}\n")
            with self.assertRaises(RuntimeError):
                boundary.scoped(require_end=True)
            (directory / "live-test.log").write_text(f"11-05 02:45:30.100 1 1 I {TAG}: {boundary.start_marker}\n")
            with self.assertRaises(RuntimeError):
                boundary.scoped(require_end=True)
            boundary.started = True
            boundary.close()
            self.assertTrue((directory / "device-partial.log").exists())
            self.assertFalse((directory / "device.log").exists())
            self.assertEqual("incomplete", json.loads((directory / "log-boundary.json").read_text())["state"])


if __name__ == "__main__":
    unittest.main()
