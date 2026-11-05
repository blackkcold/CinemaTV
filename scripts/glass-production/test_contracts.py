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
from test_log import TestLogBoundary, TAG, READY_ATTEMPTS, READY_ATTEMPT_SECONDS

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
    def reader(self, directory, during="", marker_tag=TAG, drop=None, duplicate=None, on_emit=None):
        calls = []
        process = Mock()
        process.poll.return_value = None
        def adb(*args, **options):
            calls.append(args)
            if args[:2] == ("shell", "date"):
                return "11-05_02:45:30\n"
            if args[:2] == ("shell", "log"):
                marker = args[-1]
                if marker.startswith("READY:"):
                    self.assertEqual(READY_ATTEMPT_SECONDS, options["timeout"])
                if on_emit is not None:
                    on_emit(marker, process)
                path = directory / "live-test.log"
                with path.open("a") as stream:
                    if marker.startswith("START:"):
                        stream.write("11-05 02:45:29.999 1 1 E OldBoot : FATAL EXCEPTION old startup\n")
                    elif marker.startswith("END:"):
                        stream.write(during)
                    if drop is None or not drop(marker):
                        line = f"11-05 02:45:30.100 100 100 I {marker_tag}: {marker}\n"
                        stream.write(line * (2 if duplicate is not None and duplicate(marker) else 1))
                return ""
            raise AssertionError(f"Unexpected adb command: {args}")
        return adb, process, calls

    def clock(self):
        # Advance only on requested sleeps, so missing-marker tests exercise the
        # production deadlines/retries deterministically without wall-clock waits.
        now = [0.0]
        def sleep(seconds):
            self.assertGreater(seconds, 0)
            now[0] += seconds
        return now, patch("test_log.time.monotonic", side_effect=lambda: now[0]), patch("test_log.time.sleep", side_effect=sleep)

    def emitted(self, calls, kind):
        return [call[-1] for call in calls if call[:2] == ("shell", "log") and call[-1].startswith(kind + ":")]

    def test_first_ready_can_be_lost_without_reemitting_start(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            adb, process, calls = self.reader(directory, drop=lambda marker: marker.startswith("READY:") and marker.endswith(":1"))
            boundary = TestLogBoundary(directory, adb)
            now, monotonic, sleep = self.clock()
            with patch("test_log.subprocess.Popen", return_value=process), monotonic, sleep:
                boundary.start()
                checked = boundary.finish()
                boundary.close()
            ready = self.emitted(calls, "READY")
            self.assertEqual(2, len(ready))
            self.assertNotEqual(ready[0], ready[1])
            self.assertEqual([boundary.start_marker], self.emitted(calls, "START"))
            self.assertEqual([boundary.end_marker], self.emitted(calls, "END"))
            self.assertEqual(READY_ATTEMPT_SECONDS, now[0])
            receipt = json.loads((directory / "log-boundary.json").read_text())
            self.assertEqual("complete", receipt["state"])
            self.assertEqual(ready[1], receipt["readyMarker"])
            self.assertEqual(["not-observed", "observed", "observed", "observed"],
                             [entry["observation"] for entry in receipt["markerCommands"]])
            self.assertTrue(all(entry["commandState"] == "succeeded" for entry in receipt["markerCommands"]))
            self.assertNotIn("READY:", checked)

    def test_unavailable_ready_is_bounded_and_never_starts_tests(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            adb, process, calls = self.reader(directory, drop=lambda marker: marker.startswith("READY:"))
            boundary = TestLogBoundary(directory, adb)
            now, monotonic, sleep = self.clock()
            with patch("test_log.subprocess.Popen", return_value=process), monotonic, sleep:
                with self.assertRaisesRegex(RuntimeError, "READY handshake not established"):
                    boundary.start()
                boundary.close()
            self.assertFalse(boundary.started)
            self.assertEqual(READY_ATTEMPTS, len(set(self.emitted(calls, "READY"))))
            self.assertEqual([], self.emitted(calls, "START"))
            self.assertEqual([], self.emitted(calls, "END"))
            self.assertAlmostEqual(READY_ATTEMPTS * READY_ATTEMPT_SECONDS, now[0])
            receipt = json.loads((directory / "log-boundary.json").read_text())
            self.assertEqual("failed", receipt["state"])
            self.assertEqual(READY_ATTEMPTS, len(receipt["markerCommands"]))
            self.assertTrue(all(entry["observation"] == "not-observed" for entry in receipt["markerCommands"]))
            self.assertFalse((directory / "device.log").exists())

    def test_ready_attempt_budget_includes_shell_command_time(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            now, monotonic, sleep = self.clock()
            def slow_command(marker, process):
                now[0] += READY_ATTEMPT_SECONDS * .75
            adb, process, calls = self.reader(directory, drop=lambda marker: True, on_emit=slow_command)
            boundary = TestLogBoundary(directory, adb)
            with patch("test_log.subprocess.Popen", return_value=process), monotonic, sleep:
                with self.assertRaisesRegex(RuntimeError, "READY handshake not established"):
                    boundary.start()
                boundary.close()
            self.assertEqual(READY_ATTEMPTS, len(self.emitted(calls, "READY")))
            self.assertAlmostEqual(READY_ATTEMPTS * READY_ATTEMPT_SECONDS, now[0])

    def test_old_or_delayed_ready_cannot_satisfy_current_probe(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            previous = TestLogBoundary(directory, Mock())
            boundary = TestLogBoundary(directory, None)
            def old_probe(marker, process):
                if marker.startswith("READY:"):
                    with (directory / "live-test.log").open("a") as stream:
                        # A prior viewport marker never counts. Nor does late
                        # delivery of attempt 1 during attempts 2 and 3.
                        old = f"READY:{previous.token}:1" if marker.endswith(":1") else f"READY:{boundary.token}:1"
                        stream.write(f"11-05 02:45:30.100 100 100 I {TAG}: {old}\n")
            adb, process, calls = self.reader(directory, drop=lambda marker: True, on_emit=old_probe)
            boundary.adb = adb
            _, monotonic, sleep = self.clock()
            with patch("test_log.subprocess.Popen", return_value=process), monotonic, sleep:
                with self.assertRaisesRegex(RuntimeError, "READY handshake not established"):
                    boundary.start()
                boundary.close()
            self.assertEqual(READY_ATTEMPTS, len(self.emitted(calls, "READY")))
            self.assertFalse(boundary.started)

    def test_reader_exit_after_ready_write_fails_even_with_marker_present(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            def exit_reader(marker, process):
                process.poll.return_value = 7
            adb, process, calls = self.reader(directory, on_emit=exit_reader)
            boundary = TestLogBoundary(directory, adb)
            with patch("test_log.subprocess.Popen", return_value=process):
                with self.assertRaisesRegex(RuntimeError, "reader exited.*status 7"):
                    boundary.start()
                boundary.close()
            self.assertEqual(1, len(self.emitted(calls, "READY")))
            self.assertEqual([], self.emitted(calls, "START"))
            self.assertIn(self.emitted(calls, "READY")[0], (directory / "live-test.log").read_text())
            self.assertEqual("failed", boundary.receipt["markerCommands"][0]["observation"])

    def test_ready_duplicates_are_not_retried(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            adb, process, calls = self.reader(directory, duplicate=lambda marker: marker.startswith("READY:"))
            boundary = TestLogBoundary(directory, adb)
            with patch("test_log.subprocess.Popen", return_value=process):
                with self.assertRaisesRegex(RuntimeError, "Duplicate"):
                    boundary.start()
                boundary.close()
            self.assertEqual(1, len(self.emitted(calls, "READY")))
            self.assertEqual([], self.emitted(calls, "START"))
            self.assertEqual("failed", boundary.receipt["state"])

    def test_missing_or_duplicate_formal_boundaries_fail_without_retries(self):
        for kind in ("START", "END"):
            for failure in ("missing", "duplicate"):
                with self.subTest(kind=kind, failure=failure), tempfile.TemporaryDirectory() as temp:
                    directory = Path(temp)
                    matches = lambda marker: marker.startswith(kind + ":")
                    adb, process, calls = self.reader(directory, drop=matches if failure == "missing" else None,
                                                      duplicate=matches if failure == "duplicate" else None)
                    boundary = TestLogBoundary(directory, adb)
                    _, monotonic, sleep = self.clock()
                    with patch("test_log.subprocess.Popen", return_value=process), monotonic, sleep:
                        with self.assertRaisesRegex(RuntimeError, "not observed|Duplicate"):
                            boundary.start()
                            boundary.finish()
                        if kind == "END" and failure == "duplicate":
                            with self.assertRaisesRegex(RuntimeError, "duplicate"):
                                boundary.close()
                        else:
                            boundary.close()
                    self.assertEqual(1, len(self.emitted(calls, "READY")))
                    self.assertEqual([boundary.start_marker], self.emitted(calls, "START"))
                    self.assertEqual([boundary.end_marker] if kind == "END" else [], self.emitted(calls, "END"))
                    self.assertEqual("failed", boundary.receipt["state"])
                    self.assertFalse((directory / "device.log").exists())

    def test_repeated_start_or_finish_cannot_send_another_boundary(self):
        for repeat in ("start", "finish"):
            with self.subTest(repeat=repeat), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                adb, process, calls = self.reader(directory)
                boundary = TestLogBoundary(directory, adb)
                with patch("test_log.subprocess.Popen", return_value=process):
                    boundary.start()
                    if repeat == "finish":
                        boundary.finish()
                    with self.assertRaisesRegex(RuntimeError, "Cannot restart|Cannot repeat"):
                        getattr(boundary, repeat)()
                    boundary.close()
                self.assertEqual(1, len(self.emitted(calls, "START")))
                self.assertEqual(1 if repeat == "finish" else 0, len(self.emitted(calls, "END")))
                self.assertEqual("failed", boundary.receipt["state"])

    def test_reader_exit_or_late_duplicate_at_cleanup_invalidates_completion(self):
        for failure in ("reader-exit", "duplicate"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                adb, process, _ = self.reader(directory)
                boundary = TestLogBoundary(directory, adb)
                with patch("test_log.subprocess.Popen", return_value=process):
                    boundary.start()
                    boundary.finish()
                    if failure == "reader-exit":
                        process.poll.return_value = 0
                    else:
                        with (directory / "live-test.log").open("a") as stream:
                            stream.write(f"11-05 02:45:31.100 100 100 I {TAG}: {boundary.end_marker}\n")
                    with self.assertRaisesRegex(RuntimeError, "reader exited|duplicate"):
                        boundary.close()
                self.assertEqual("failed", boundary.receipt["state"])

    def test_reversed_or_duplicate_span_is_invalid_even_for_partial_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            boundary = TestLogBoundary(directory, Mock())
            for markers in ((boundary.end_marker, boundary.start_marker),
                            (boundary.start_marker, boundary.end_marker, boundary.end_marker)):
                (directory / "live-test.log").write_text("".join(
                    f"11-05 02:45:30.100 100 100 I {TAG}: {marker}\n" for marker in markers))
                for require_end in (True, False):
                    with self.subTest(markers=markers, require_end=require_end), self.assertRaises(RuntimeError):
                        boundary.scoped(require_end)

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
            errors = ("11-05 02:45:31.100 120 120 E AndroidRuntime: FATAL EXCEPTION main\n"
                      "11-05 02:45:31.101 120 120 E ActivityManager: ANR in dev.cinematv\n")
            adb, process, _ = self.reader(directory, errors,
                                          drop=lambda marker: marker.startswith("READY:") and marker.endswith(":1"))
            boundary = TestLogBoundary(directory, adb)
            _, monotonic, sleep = self.clock()
            with patch("test_log.subprocess.Popen", return_value=process), monotonic, sleep:
                boundary.start()
                checked = boundary.finish()
                boundary.close()
            self.assertIn(errors, checked)
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
            def denied(*args, **options):
                if args[:2] == ("shell", "log"):
                    raise subprocess.CalledProcessError(1, args, output="log: permission denied")
                return adb(*args, **options)
            boundary = TestLogBoundary(directory, denied)
            with patch("test_log.subprocess.Popen", return_value=process):
                with self.assertRaises(subprocess.CalledProcessError):
                    boundary.start()
                boundary.close()
            receipt = json.loads((directory / "log-boundary.json").read_text())
            self.assertEqual("log: permission denied", receipt["commandOutput"])
            self.assertEqual("failed", receipt["state"])
            self.assertEqual(1, len(receipt["markerCommands"]))
            self.assertTrue(receipt["markerCommands"][0]["marker"].startswith("READY:"))
            self.assertEqual("failed", receipt["markerCommands"][0]["commandState"])
            self.assertFalse(boundary.started)

    def test_quoted_marker_wrong_tag_and_missing_end_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            boundary = TestLogBoundary(directory, Mock())
            for prefix in ("11-05 02:45:30.100 1 1 I OtherTag: ",
                           f"11-05 02:45:30.100 1 1 I OtherTag: quoted I {TAG}: "):
                (directory / "live-test.log").write_text(prefix + boundary.start_marker + "\n")
                with self.subTest(prefix=prefix), self.assertRaises(RuntimeError):
                    boundary.scoped(require_end=False)
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
