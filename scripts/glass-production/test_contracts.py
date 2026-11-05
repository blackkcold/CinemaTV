"""Host-side harness tests. Synthetic PNGs here validate the validator, not the app."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from evidence import digest, verify_captures
from run_device import PRODUCTION, BUTTON, BUTTON_EDGE, MATERIAL, passed_instrumentation

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


if __name__ == "__main__":
    unittest.main()
