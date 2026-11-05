import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch
from analyze import analyze, frame_summary, percentile
from evidence import verify_build, sha256, APK_NAME, PACKAGE
from protocol import CYCLE, EXPECTED_RUNS, QUALITY, WARMUP_COUNT

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DRIVER_SPEC = importlib.util.spec_from_file_location("glass_performance_driver", HERE / "drive-performance.py")
DRIVER = importlib.util.module_from_spec(DRIVER_SPEC)
DRIVER_SPEC.loader.exec_module(DRIVER)


def fixture():
    state = {"runId": "r1-basic", "actualMode": "BASIC", "failed": False, "foreground": True, "animationsEnabled": True,
             "ready": True, "fontsReady": True, "fontFailures": [], "busy": False,
             "decodedScene": "android.resource://dev.cinematv.glassbenchmark/1", "route": "HOME",
             "draws": {"full": 0, "blur": 0, "basic": 100}}
    windows, native = [], []
    before = "HOME"
    for i, (code, route, _) in enumerate(CYCLE * 2):
        index = i + WARMUP_COUNT
        windows.append({"index": index, "key": code, "measured": True, "routeBefore": before,
                        "startNs": (i + 1) * 1_000_000_000, "endNs": (i + 1) * 1_000_000_000 + 450_000_000})
        native.append({"index": index, "key": code, "measured": True, "routeBefore": before, "receivedNs": (i + 1) * 1_000_000_000, "drawsBefore": {"full": 0, "blur": 0, "basic": i * 10},
                       "after": dict(state, route=route, draws={"full": 0, "blur": 0, "basic": (i + 1) * 10})})
        before = route
    frames = [{"vsyncNs": w["startNs"] + n * 20_000_000, "totalNs": 10_000_000, "gpuNs": 8_000_000,
               "deadlineNs": 16_666_667, "firstDraw": False} for w in windows for n in range(1, 10)]
    frames += [{"vsyncNs": 1, "totalNs": 999_000_000, "firstDraw": True},
               {"vsyncNs": 1_900_000_000, "totalNs": 999_000_000, "firstDraw": False}]
    return {"package": PACKAGE, "refreshHz": 60, "width": 1920, "height": 1080, "densityDpi": 320, "schema": 2, "buildType": "glassBenchmark", "debuggable": False, "screenrecordConcurrent": False,
            "root": "TvRootApp", "viewModel": "CinemaViewModel", "provider": "ProductionGlassProvider", "windows": windows,
            "inputs": native,
            "start": state, "end": state, "requested": "BASIC", "expectedMode": "BASIC", "policyViolations": [],
            "metricCallbackDrops": 0, "truncated": False, "frames": frames, "runId": "r1-basic", "sourceSha": "a" * 40, "apkSha256": "b" * 64}


class ProtocolTest(unittest.TestCase):
    def test_active_windows_exclude_startup_and_idle_without_cross_window_intervals(self):
        result = frame_summary(fixture())
        self.assertTrue(result["validForComparison"])
        self.assertEqual(198, result["renderedFrameSamples"])
        self.assertEqual(2, result["excludedStartupIdleOrFirstDrawSamples"])
        self.assertEqual(10, result["windowRenderTotalMs"]["p95"])
        self.assertEqual(20, result["appRenderIntervalMs"]["max"])
        self.assertEqual(9.9, result["activeWindowSeconds"])

    def test_callback_loss_is_invalid_data_not_dropped_frames(self):
        data = fixture()
        data["metricCallbackDrops"] = 2
        result = frame_summary(data)
        self.assertFalse(result["validForComparison"])
        self.assertEqual(2, result["metricCallbackDrops"])
        self.assertEqual(0, result["platformDeadlineExceededCount"])
        self.assertNotIn("fps", json.dumps(result).lower())
        self.assertEqual(50, result["appRenderCadenceHz"]["median"])

    def test_no_frame_window_blocks_comparison(self):
        data = fixture()
        data["frames"] = [f for f in data["frames"] if f["vsyncNs"] < 22_000_000_000]
        self.assertFalse(frame_summary(data)["validForComparison"])

    def test_recording_and_debuggable_runs_rejected(self):
        for field in ("debuggable", "screenrecordConcurrent"):
            data = fixture()
            data[field] = True
            with self.assertRaises(AssertionError):
                frame_summary(data)

    def test_overlapping_windows_rejected(self):
        data = fixture()
        data["windows"][1].update(startNs=data["windows"][0]["startNs"], endNs=data["windows"][0]["endNs"])
        with self.assertRaises(AssertionError):
            frame_summary(data)

    def test_fallback_and_unexpected_optical_draws_block_comparison(self):
        for change in ("fallback", "unexpected-draw"):
            data = copy.deepcopy(fixture())
            if change == "fallback":
                data["end"]["actualMode"] = "BLUR"
            else:
                data["end"]["draws"]["full"] = 1
            self.assertFalse(frame_summary(data)["validForComparison"])

    def test_apk_receipt_verifies_exact_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root / APK_NAME).write_bytes(b"same binary")
            (root / "build-receipt.json").write_text(json.dumps({"schema": 1, "sourceSha": "a" * 40,
                "package": PACKAGE, "debuggable": False, "apk": APK_NAME, "buildType": "glassBenchmark", "apkSha256": sha256(root / APK_NAME)}))
            (root / "apk-badging.txt").write_text(f"package: name='{PACKAGE}'\n")
            verify_build(root, "a" * 40)
            (root / APK_NAME).write_bytes(b"changed")
            with self.assertRaises(AssertionError):
                verify_build(root, "a" * 40)

    def test_harness_keeps_actual_production_root_and_separate_package(self):
        activity = (ROOT / "app/src/glassBenchmark/java/dev/cinematv/GlassBenchmarkActivity.kt").read_text()
        self.assertIn("TvRootApp(model)", activity)
        self.assertIn("CinemaNotoProvider", activity)
        self.assertIn('packageName == "dev.cinematv.glassbenchmark"', activity)
        for banned in ("LocalProductionGlassScene provides", "rememberInfiniteTransition", "postInvalidateOnAnimation", "requestFocus()"):
            self.assertNotIn(banned, activity)
        server = (ROOT / "app/src/glassBenchmark/java/dev/cinematv/GlassBenchmarkFixtureServer.kt").read_text()
        self.assertIn('InetAddress.getByName("127.0.0.1")', server)
        self.assertNotIn('android.resource://dev.cinematv/', server)
        self.assertIn('android.resource://${context.packageName}/', server)

    def test_no_benchmark_entry_in_production_manifest(self):
        self.assertNotIn("GlassBenchmarkActivity", (ROOT / "app/src/main/AndroidManifest.xml").read_text())

    def test_preceding_boundary_is_reported_separately(self):
        data = fixture()
        data["frames"].append({"vsyncNs": 999_000_000, "totalNs": 40_000_000, "firstDraw": False})
        result = frame_summary(data)
        self.assertEqual(1, result["precedingBoundarySamples"])
        self.assertEqual(10, result["windowRenderTotalMs"]["max"])
        self.assertEqual(40, result["withPrecedingBoundaryTotalMs"]["max"])

    def test_setup_failure_still_produces_incomplete_summary(self):
        with tempfile.TemporaryDirectory() as root:
            result = analyze(Path(root))
            self.assertFalse(result["completeComparison"])
            self.assertTrue(result["setupIssues"])
            self.assertTrue((Path(root) / "summary.json").exists())

    def test_wrong_native_key_or_route_rejected(self):
        for field in ("key", "route"):
            data = fixture()
            if field == "key":
                data["inputs"][0]["key"] = 999
            else:
                data["inputs"][0]["after"]["route"] = "PLAYER"
            with self.assertRaises(AssertionError):
                frame_summary(data)

    def test_unready_scene_blocks_comparison(self):
        data = fixture()
        data["inputs"][0]["after"]["ready"] = False
        self.assertFalse(frame_summary(data)["validForComparison"])

    def test_zero_input_recording_delta_does_not_reject_rendernode_animation(self):
        data = fixture()
        data["inputs"][0]["after"]["draws"]["basic"] = 0
        result = frame_summary(data)
        self.assertTrue(result["validForComparison"])
        self.assertEqual(0, result["perWindow"][0]["materialRecordingTraversalDeltaTo520msSnapshot"]["basic"])
        self.assertEqual(9, result["perWindow"][0]["renderedSamples"])

    def test_initial_and_whole_run_material_recording_remain_required(self):
        for phase in ("start", "end"):
            data = copy.deepcopy(fixture())
            data[phase]["draws"]["basic"] = 0
            self.assertFalse(frame_summary(data)["validForComparison"])

    def test_negative_recording_delta_still_blocks_comparison(self):
        data = fixture()
        data["inputs"][0]["drawsBefore"]["basic"] = 11
        self.assertFalse(frame_summary(data)["validForComparison"])

    def test_complete_matrix_and_repeated_trial_rejection(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "build-receipt.json").write_text(json.dumps({"sourceSha": "a" * 40, "apkSha256": "b" * 64}))
            (root / "runtime.json").write_text(json.dumps({"surfaceFlingerGles": ["GLES: test"], "surfaceFlingerRendererVerified": True, "requestedConfiguration": "test"}))
            (root / "setup.json").write_text(json.dumps({"status": "ready"}))
            runs = []
            for run_id, tier in EXPECTED_RUNS:
                data = copy.deepcopy(fixture())
                data.update(runId=run_id, expectedMode=tier, requested=QUALITY[tier])
                def counts(value):
                    return {"full": value if tier == "FULL" else 0, "blur": value if tier == "BLUR" else 0, "basic": value if tier == "BASIC" else 0}
                for state in [data["start"], data["end"], *(v["after"] for v in data["inputs"])]:
                    state["runId"] = run_id
                    state["actualMode"] = tier
                    state["draws"] = counts(sum(state["draws"].values()))
                for value in data["inputs"]:
                    value["drawsBefore"] = counts(sum(value["drawsBefore"].values()))
                directory = root / run_id
                directory.mkdir()
                (directory / "frames.json").write_text(json.dumps(data))
                (directory / "inputs.json").write_text(json.dumps([{"native": value} for value in data["inputs"]]))
                runs.append({"runId": run_id, "tier": tier, "result": "collected", "appHwuiPipeline": ["Pipeline=Skia (OpenGL)"]})
            (root / "runs.json").write_text(json.dumps(runs))
            self.assertTrue(analyze(root)["completeComparison"])
            old_frames = (root / "r2-full/frames.json").read_text()
            old_inputs = (root / "r2-full/inputs.json").read_text()
            (root / "r2-full/frames.json").write_text((root / "r1-full/frames.json").read_text())
            (root / "r2-full/inputs.json").write_text((root / "r1-full/inputs.json").read_text())
            self.assertFalse(analyze(root)["completeComparison"])
            (root / "r2-full/frames.json").write_text(old_frames)
            (root / "r2-full/inputs.json").write_text(old_inputs)
            runs[5] = runs[0]  # Same tier count, but the first trial cannot count twice.
            (root / "runs.json").write_text(json.dumps(runs))
            self.assertFalse(analyze(root)["completeComparison"])

    def test_native_keys_forward_through_public_window_callback(self):
        source = (ROOT / "app/src/glassBenchmark/java/dev/cinematv/GlassBenchmarkActivity.kt").read_text()
        self.assertIn("object : Window.Callback by delegate", source)
        self.assertIn("observeNativeKey(event)\n                return delegate.dispatchKeyEvent(event)", source)
        self.assertNotIn("super.dispatchKeyEvent", source)
        self.assertNotIn('@SuppressLint("RestrictedApi")', source)
        self.assertIn("KeyEvent.KEYCODE_BACK", source)

    def test_status_waits_for_exact_async_nonce_and_rejects_stale(self):
        nonce = "a" * 32
        previous = {"runId": "r1-basic", "requestNonce": "b" * 32, "ready": True}
        wrong_run = {"runId": "r2-basic", "requestNonce": nonce, "ready": True}
        fresh = {"runId": "r1-basic", "requestNonce": nonce, "ready": True}
        with patch.object(DRIVER.uuid, "uuid4") as uid, \
             patch.object(DRIVER, "records", side_effect=[[previous], [wrong_run], [previous, fresh]]), \
             patch.object(DRIVER, "control", return_value="intent delivered to running top") as control, \
             patch.object(DRIVER.time, "sleep") as sleep:
            uid.return_value.hex = nonce
            self.assertIs(fresh, DRIVER.status("r1-basic"))
            control.assert_called_once_with("benchmark_status", nonce=nonce)
            self.assertEqual(2, sleep.call_count)

    def test_status_timeout_reports_whether_native_callback_arrived(self):
        nonce = "a" * 32
        with patch.object(DRIVER.uuid, "uuid4") as uid, \
             patch.object(DRIVER, "records", return_value=[{"runId": "r1-basic", "requestNonce": nonce}]), \
             patch.object(DRIVER, "control", return_value="delivered"), \
             patch.object(DRIVER.time, "monotonic", side_effect=[0, 16]):
            uid.return_value.hex = nonce
            with self.assertRaisesRegex(AssertionError, "nativeControlReceived=True"):
                DRIVER.status("r1-basic")

    def test_native_receipt_parser_handles_brief_and_observed_threadtime(self):
        data = {"runId": "r1-basic", "requestNonce": "a" * 32, "ready": True}
        for prefix in ("I/ProductionGlassBenchmark( 2561): ", "11-05 03:40:34.324  2561  2561 I ProductionGlassBenchmark: "):
            self.assertEqual([data], DRIVER.parse_records(prefix + "status " + json.dumps(data), "status", "r1-basic"))
            self.assertEqual([], DRIVER.parse_records(prefix + "status " + json.dumps(data), "status", "r2-basic"))
        self.assertEqual([], DRIVER.parse_records("I/OtherTag( 2561): status " + json.dumps(data), "status", "r1-basic"))

    def test_control_failure_stops_matrix_but_tier_fallback_does_not(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            blocked = {"runId": "r1-full", "tier": "FULL", "result": "blocked", "failureClass": "control_infrastructure"}
            with patch.object(DRIVER, "run_one", return_value=blocked) as run:
                self.assertEqual(1, len(DRIVER.collect_runs(out, {})))
                self.assertEqual(1, run.call_count)
            blocked["failureClass"] = "tier_or_measurement"
            with patch.object(DRIVER, "run_one", return_value=blocked) as run:
                self.assertEqual(9, len(DRIVER.collect_runs(out, {})))
                self.assertEqual(9, run.call_count)

    def test_control_adb_failure_keeps_partial_output_and_aborts(self):
        errors = [subprocess.TimeoutExpired(["adb"], 25, output=b"partial timeout output"),
                  subprocess.CalledProcessError(1, ["adb"], output="partial failure output")]
        for error in errors:
            DRIVER.CONTROL_TRACE.clear()
            with patch.object(DRIVER, "adb", side_effect=error):
                with self.assertRaises(DRIVER.ControlHandshakeError):
                    DRIVER.control("benchmark_status", nonce="a" * 32)
            self.assertIn("partial", DRIVER.CONTROL_TRACE[-1]["output"])
            self.assertIn(type(error).__name__, DRIVER.CONTROL_TRACE[-1]["error"])

    def test_percentile(self):
        self.assertIsNone(percentile([], .95))
        self.assertEqual(2.5, percentile([1, 2, 3, 4], .5))


if __name__ == "__main__":
    unittest.main()
