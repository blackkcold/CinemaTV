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
from protocol import CYCLE, EXPECTED_RUNS, QUALITY, WARMUP_COUNT, DEFAULT_PROFILE, POSTER_CYCLE, PROFILES, cycle_for, poster_origin, poster_step

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DRIVER_SPEC = importlib.util.spec_from_file_location("glass_performance_driver", HERE / "drive-performance.py")
DRIVER = importlib.util.module_from_spec(DRIVER_SPEC)
DRIVER_SPEC.loader.exec_module(DRIVER)


def fixture():
    state = {"profile": DEFAULT_PROFILE, "runId": "r1-basic", "actualMode": "BASIC", "failed": False, "foreground": True, "animationsEnabled": True,
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
    return {"profile": DEFAULT_PROFILE, "package": PACKAGE, "refreshHz": 60, "width": 1920, "height": 1080, "densityDpi": 320, "schema": 2, "buildType": "glassBenchmark", "debuggable": False, "screenrecordConcurrent": False,
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
            (root / "setup.json").write_text(json.dumps({"profile": DEFAULT_PROFILE, "status": "ready"}))
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
                runs.append({"profile": DEFAULT_PROFILE, "runId": run_id, "tier": tier, "result": "collected", "appHwuiPipeline": ["Pipeline=Skia (OpenGL)"]})
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

def poster_fixture():
    data = copy.deepcopy(fixture())
    profile = "poster-motion"
    data["profile"] = profile
    origin = dict(data["start"], profile=profile, bookmarkRegion="hero", bookmarkItem="primary",
                  featuredKeys=[f"movie-{i}" for i in range(5)], tvShowKeys=[f"show-{i}" for i in range(5)],
                  selectedKey="", historyCount=0, movieCount=24, tvShowCount=24)
    data["start"], data["end"] = copy.deepcopy(origin), copy.deepcopy(origin)
    data["inputs"], data["windows"] = [], []
    indices = (0, 1, 2, 3, 4, 3, 2, None, 2, 1, 0, None)
    before = "HOME"
    plan = ((19, "HOME", "prepare"), (20, "HOME", "prepare")) + POSTER_CYCLE * 3
    for index, (code, route, _) in enumerate(plan):
        state = copy.deepcopy(origin)
        state["route"] = route
        offset = (index - 2) % 12
        if index >= 2:
            if offset == 7:
                state.update(bookmarkRegion="detail", bookmarkItem="play", selectedKey=origin["featuredKeys"][2])
            elif offset != 11:
                state.update(bookmarkRegion="rail:为你精选", bookmarkItem=origin["featuredKeys"][indices[offset]])
        state["draws"]["basic"] = (index + 1) * 10
        native = {"index": index, "key": code, "measured": index >= 14, "routeBefore": before,
                  "receivedNs": (index + 1) * 1_000_000_000,
                  "drawsBefore": {"full": 0, "blur": 0, "basic": index * 10}, "after": state}
        data["inputs"].append(native)
        if native["measured"]:
            data["windows"].append({k: native[k] for k in ("index", "key", "measured", "routeBefore")})
            data["windows"][-1].update(startNs=native["receivedNs"], endNs=native["receivedNs"] + 450_000_000)
        before = route
    data["frames"] = [{"vsyncNs": w["startNs"] + n * 20_000_000, "totalNs": 10_000_000, "gpuNs": 8_000_000,
                      "deadlineNs": 16_666_667, "firstDraw": False} for w in data["windows"] for n in range(1, 10)]
    return data


class PosterProfileTest(unittest.TestCase):
    def test_exact_profiles_and_counts(self):
        self.assertEqual([19, 22, 22, 21, 23, 4, 20, 22, 21, 23, 4], [v[0] for v in cycle_for(DEFAULT_PROFILE)])
        self.assertEqual([20, 22, 22, 22, 22, 21, 21, 23, 4, 21, 21, 19], [v[0] for v in cycle_for("poster-motion")])
        self.assertEqual(13, WARMUP_COUNT)
        result = frame_summary(poster_fixture(), "poster-motion")
        self.assertTrue(result["validForComparison"])
        self.assertEqual("poster-motion", result["profile"])
        self.assertEqual(24, result["nativeInputWindows"])
        self.assertEqual(10.8, result["activeWindowSeconds"])
        self.assertEqual(216, result["renderedFrameSamples"])
        self.assertEqual(9, len(EXPECTED_RUNS))

    def test_missing_unknown_and_cross_profile_are_rejected(self):
        for data, profile in ((fixture(), "poster-motion"), (poster_fixture(), DEFAULT_PROFILE), (fixture(), "typo")):
            with self.subTest(profile=profile), self.assertRaises(AssertionError):
                frame_summary(data, profile)
        for target in ("top", "start", "end", "warmup", "input"):
            data = poster_fixture()
            value = data if target == "top" else data[target] if target in ("start", "end") else data["inputs"][0 if target == "warmup" else 14]["after"]
            del value["profile"]
            with self.subTest(target=target), self.assertRaises(AssertionError):
                frame_summary(data, "poster-motion")
        legacy = fixture()
        del legacy["profile"]
        with self.assertRaises(AssertionError):
            frame_summary(legacy)

    def test_native_path_mutations_rejected_in_both_cycles_and_warmup(self):
        for index in range(2, 38):
            for field in ("key", "routeBefore", "route", "identity"):
                data = poster_fixture()
                value = data["inputs"][index]
                if field in ("key", "routeBefore"):
                    value[field] = 999 if field == "key" else "PLAYER"
                elif field == "route":
                    value["after"]["route"] = "PLAYER"
                elif (index - 2) % 12 == 7:
                    value["after"]["selectedKey"] = "wrong-selected"
                else:
                    value["after"]["bookmarkItem"] = "wrong-bookmark"
                with self.subTest(index=index, field=field), self.assertRaises(AssertionError):
                    frame_summary(data, "poster-motion")

    def test_catalog_origin_and_rail_mutations_rejected(self):
        for target in ("start", "end", "warmup", "input"):
            for field in ("featuredKeys", "tvShowKeys", "movieCount", "tvShowCount", "historyCount", "bookmarkRegion"):
                data = poster_fixture()
                state = data[target] if target in ("start", "end") else data["inputs"][2 if target == "warmup" else 14]["after"]
                if field.endswith("Keys"):
                    state[field][0] = state[field][1]
                else:
                    state[field] = 1 if field != "bookmarkRegion" else "wrong-region"
                with self.subTest(target=target, field=field), self.assertRaises(AssertionError):
                    frame_summary(data, "poster-motion")
        data = poster_fixture()
        data["inputs"][18]["after"]["featuredKeys"].reverse()
        with self.assertRaisesRegex(AssertionError, "Catalog identity/order"):
            frame_summary(data, "poster-motion")

    def test_wrong_warmup_counts_order_and_timing_rejected(self):
        for mutation in ("missing-warmup", "duplicate-warmup", "wrong-preparation", "missing-window", "extra-window", "duplicate-input", "short-window", "wrong-index", "wrong-clock", "short-interval"):
            data = poster_fixture()
            if mutation == "missing-warmup": data["inputs"].pop(0)
            elif mutation == "duplicate-warmup": data["inputs"].insert(0, copy.deepcopy(data["inputs"][0]))
            elif mutation == "wrong-preparation": data["inputs"][0]["key"] = 20
            elif mutation == "missing-window": data["windows"].pop()
            elif mutation == "extra-window": data["windows"].append(copy.deepcopy(data["windows"][-1]))
            elif mutation == "duplicate-input": data["inputs"].append(copy.deepcopy(data["inputs"][-1]))
            elif mutation == "short-window": data["windows"][0]["endNs"] -= 1
            elif mutation == "wrong-index": data["windows"][0]["index"] -= 1
            elif mutation == "wrong-clock": data["inputs"][14]["receivedNs"] -= 1
            elif mutation == "short-interval":
                data["windows"][1]["startNs"] -= 400_000_000
                data["windows"][1]["endNs"] -= 400_000_000
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                frame_summary(data, "poster-motion")

    def test_driver_warmup_uses_native_keys_and_refuses_wrong_origin(self):
        data = poster_fixture()
        def receipt(run_id, index, code, route, label="prepare"):
            self.assertEqual(data["inputs"][index]["key"], code)
            return {"native": data["inputs"][index]}
        with patch.object(DRIVER, "key", side_effect=receipt) as key, patch.object(DRIVER, "foreground", return_value="pid"):
            self.assertEqual(14, len(DRIVER.warmup("r1-basic", "pid", "poster-motion")))
            self.assertEqual(14, key.call_count)
            data["inputs"][1]["after"]["bookmarkItem"] = "wrong"
            with self.assertRaises(AssertionError):
                DRIVER.warmup("r1-basic", "pid", "poster-motion")

    def test_one_selected_profile_runs_nine_not_eighteen_trials(self):
        with tempfile.TemporaryDirectory() as temp:
            for profile in PROFILES:
                with patch.object(DRIVER, "run_one", return_value={"result": "collected"}) as run:
                    self.assertEqual(9, len(DRIVER.collect_runs(Path(temp), {}, profile)))
                    self.assertEqual(EXPECTED_RUNS, [(c.args[1], c.args[2]) for c in run.call_args_list])
                    self.assertTrue(all(c.args[4] == profile for c in run.call_args_list))

    def test_cli_rejects_unknown_profile_before_device_access(self):
        for script in ("drive-performance.py", "analyze.py"):
            result = subprocess.run(["python3", str(HERE / script), "--profile", "unknown"], capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
            self.assertIn("invalid choice", result.stderr)

    def test_poster_matrix_requires_profile_and_identical_catalog_everywhere(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "build-receipt.json").write_text(json.dumps({"sourceSha": "a" * 40, "apkSha256": "b" * 64}))
            (root / "runtime.json").write_text(json.dumps({"surfaceFlingerGles": ["GLES: test"], "surfaceFlingerRendererVerified": True, "requestedConfiguration": "test"}))
            (root / "setup.json").write_text(json.dumps({"profile": "poster-motion", "status": "ready"}))
            runs = []
            for run_id, tier in EXPECTED_RUNS:
                data = poster_fixture()
                data.update(runId=run_id, expectedMode=tier, requested=QUALITY[tier])
                def counts(value):
                    return {mode.lower(): value if tier == mode else 0 for mode in ("FULL", "BLUR", "BASIC")}
                for state in [data["start"], data["end"], *(v["after"] for v in data["inputs"])]:
                    state.update(runId=run_id, actualMode=tier, draws=counts(sum(state["draws"].values())))
                for value in data["inputs"]:
                    value["drawsBefore"] = counts(sum(value["drawsBefore"].values()))
                directory = root / run_id
                directory.mkdir()
                (directory / "frames.json").write_text(json.dumps(data))
                (directory / "inputs.json").write_text(json.dumps([{"native": value} for value in data["inputs"]]))
                runs.append({"profile": "poster-motion", "runId": run_id, "tier": tier, "result": "collected", "appHwuiPipeline": ["Pipeline=Skia (OpenGL)"]})
            (root / "runs.json").write_text(json.dumps(runs))
            summary = analyze(root, "poster-motion")
            self.assertTrue(summary["completeComparison"])
            self.assertEqual("poster-motion", summary["profile"])
            self.assertTrue(all(run["profile"] == "poster-motion" for run in summary["runs"]))
            self.assertFalse(analyze(root)["completeComparison"])
            for mutation in ("setup", "run", "frame", "warmup-host", "catalog"):
                path = root / "setup.json" if mutation == "setup" else root / "runs.json" if mutation == "run" else root / "r1-full" / ("inputs.json" if mutation == "warmup-host" else "frames.json")
                original = path.read_text()
                value = json.loads(original)
                if mutation in ("setup", "frame"):
                    value.pop("profile")
                elif mutation == "run":
                    value[0]["profile"] = DEFAULT_PROFILE
                elif mutation == "warmup-host":
                    value[0]["native"]["key"] = 20
                else:
                    # Internally valid but different catalog in just one trial.
                    for state in [value["start"], value["end"], *(v["after"] for v in value["inputs"])]:
                        state["featuredKeys"] = ["other-" + k for k in state["featuredKeys"]]
                        if state["bookmarkRegion"] == "rail:为你精选":
                            state["bookmarkItem"] = "other-" + state["bookmarkItem"]
                        if state["selectedKey"]:
                            state["selectedKey"] = "other-" + state["selectedKey"]
                    host_path = root / "r1-full/inputs.json"
                    original_host = host_path.read_text()
                    host_path.write_text(json.dumps([{"native": v} for v in value["inputs"]]))
                path.write_text(json.dumps(value))
                with self.subTest(mutation=mutation):
                    self.assertFalse(analyze(root, "poster-motion")["completeComparison"])
                path.write_text(original)
                if mutation == "catalog":
                    host_path.write_text(original_host)

    def test_native_input_log_payload_stays_below_android_entry_limit(self):
        # Production entryId is "entry:" plus a 64-character SHA-256 digest. Include
        # every current status field and conservative lengths/counters, not short
        # fixture aliases, to guard against reintroducing 48-key logcat truncation.
        native = copy.deepcopy(poster_fixture()["inputs"][14])
        state = native["after"]
        state.update(runId="r" * 80, requestNonce="n" * 32,
                     featuredKeys=["entry:" + str(i).zfill(64) for i in range(5)],
                     tvShowKeys=["entry:" + str(i + 5).zfill(64) for i in range(5)],
                     selectedKey="entry:" + "s" * 64, bookmarkItem="entry:" + "b" * 64,
                     bookmarkRegion="rail:为你精选", requested="ENHANCED", expectedMode="FULL",
                     actualMode="FULL", reason="OPTICAL_RUNTIME_FAILURE" * 2,
                     densityDpi=320, widthPx=1920, heightPx=1080,
                     decodedScene="android.resource://dev.cinematv.glassbenchmark/2131234567",
                     environment={"api": 35, "lowRam": False, "memoryClassMb": 256,
                                  "hardwareAccelerated": True, "windowReady": True,
                                  "powerSave": False, "thermal": "CRITICAL",
                                  "userReducedMotion": False, "systemAnimationsEnabled": True})
        focus = dict(observed=True, navigationHasFocus=True,
                     navigationAcquisition=9223372036854775807,
                     restoreRoute="DETAIL:entry:" + "f" * 64,
                     restoreState="Cancelled", restoreGeneration=9223372036854775807,
                     restoreStartedAtNanos=9223372036854775807,
                     restoreRequestedAtNanos=9223372036854775807,
                     restoreFinishedAtNanos=9223372036854775807)
        state["focusOwner"] = copy.deepcopy(focus)
        native["focusOwnerBefore"] = copy.deepcopy(focus)
        native.update(deviceId=-1, source=257, receivedNs=999999999999999999)
        for counts in (state["draws"], native["drawsBefore"]):
            counts.update(full=2147483647, blur=2147483647, basic=2147483647)
        payload = "input " + json.dumps(native, ensure_ascii=True, separators=(",", ":"))
        self.assertLess(len(payload.encode("utf-8")), 3500, "Leave margin under Android's approximately 4 KB log payload")

    def test_activity_metadata_is_passive_and_origin_guarded(self):
        activity = (ROOT / "app/src/glassBenchmark/java/dev/cinematv/GlassBenchmarkActivity.kt").read_text()
        for source in ('requireNotNull(intent.getStringExtra("benchmark_profile"))', 'JSONArray(model.featured.take(5).map { it.presentationKey })', 'JSONArray(model.tvShows.take(5).map { it.presentationKey })',
                       'model.selected?.presentationKey', 'model.history().size', 'if (!posterOrigin || !ready()',
                       'startStatus.optString("bookmarkItem") == "primary"'):
            self.assertIn(source, activity)
        for field in ("observed", "navigationHasFocus", "navigationAcquisition", "restoreRoute", "restoreState",
                      "restoreGeneration", "restoreStartedAtNanos", "restoreRequestedAtNanos", "restoreFinishedAtNanos"):
            self.assertIn(f'.put("{field}",', activity)
        self.assertIn('.put("focusOwnerBefore", focusJson())', activity)
        self.assertIn('.put("focusOwner", focusJson())', activity)
        for banned in ("LocalMotion", "mainClock", "requestFocus()", "focusCoordinator.remember", "selected =", "scrollToItem"):
            self.assertNotIn(banned, activity)
        workflow = (ROOT / ".github/workflows/production-glass-performance.yml").read_text()
        self.assertIn("options: [glass-controls, poster-motion]", workflow)
        self.assertEqual(2, workflow.count('--profile "$BENCHMARK_PROFILE"'))
        self.assertNotIn("matrix:", workflow)
        self.assertIn("contains(github.event.head_commit.message, '[poster-performance]') && 'poster-motion' || 'glass-controls'", workflow)
        self.assertIn("'[glass-performance]'", workflow)


if __name__ == "__main__":
    unittest.main()
