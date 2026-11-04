#!/usr/bin/env python3
"""Fast host-only tests. Android, Gradle and host diagnostics use local stubs."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("qa_device_matrix", SCRIPTS / "qa-device-matrix.py")
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)


# Exact relevant lines from the failed API 33 4K setup-windows.txt, before
# resize. A healthy full dumpsys window has no mDisplayFrozen=false field.
API33_READY_WINDOW = """    mSafeMode=false mSystemReady=true mSystemBooted=true
  mCurrentFocus=Window{73af1b9 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}
    mAwake=true mScreenOnEarly=true mScreenOnFully=true
    mKeyguardDrawComplete=true mWindowManagerDrawComplete=true
"""


class DeviceWindowReadinessTest(unittest.TestCase):
    def ready(self, window, boot="1"):
        source = (SCRIPTS / "run-device-qa.sh").read_text()
        predicate = source.split("device_window_ready() {", 1)[1].split("\nwait_for_device_window()", 1)[0]
        result = subprocess.run(["bash", "-euo", "pipefail", "-c",
                                 "device_window_ready() {" + predicate +
                                 '\ndevice_window_ready "$1" "$2"', "test", boot, window],
                                capture_output=True, text=True, timeout=2)
        return result.returncode == 0

    def test_api33_ready_without_display_frozen_field(self):
        self.assertNotIn("mDisplayFrozen", API33_READY_WINDOW)
        self.assertTrue(self.ready(API33_READY_WINDOW))
        self.assertTrue(self.ready(API33_READY_WINDOW + "mDisplayFrozen=false\n"))

    def test_explicit_freeze_or_missing_positive_readiness_is_rejected(self):
        self.assertFalse(self.ready(API33_READY_WINDOW + "mDisplayFrozen=true\n"))
        self.assertFalse(self.ready(API33_READY_WINDOW, boot="0"))
        for field in ("mAwake", "mScreenOnFully", "mWindowManagerDrawComplete"):
            for replacement in ("", field + "=false"):
                with self.subTest(field=field, replacement=replacement):
                    self.assertFalse(self.ready(API33_READY_WINDOW.replace(field + "=true", replacement)))
        self.assertFalse(self.ready(API33_READY_WINDOW.replace("mCurrentFocus=", "mPreviousFocus=")))


class DeviceMatrixTest(unittest.TestCase):
    def test_default_retains_all_acceptance_targets_and_classes(self):
        self.assertEqual(matrix.select("all"), {"include": [
            {"name": "phone", "target": "google_apis", "api": 30, "size": "1080x1920", "density": 420,
             "classes": "dev.cinematv.MobileNavigationSmokeTest,dev.cinematv.OfficialLibreTvPlaybackAcceptanceTest,dev.cinematv.TvLaunchSmokeTest,dev.cinematv.MobilePlaybackSmokeTest,dev.cinematv.data.LocalMediaLibraryTest,dev.cinematv.data.LocalWorkAssociationPersistenceTest,dev.cinematv.data.RepositoryContractTest,dev.cinematv.data.LibreTvProtocolTest,dev.cinematv.MobileWorkNavigationTest,dev.cinematv.MobileManualWorkAssociationTest,dev.cinematv.MobileLibraryCatalogControlsTest,dev.cinematv.MobileRecommendationDashboardTest,dev.cinematv.MobileGlassButtonTest,dev.cinematv.CinemaBrandStartupTest,dev.cinematv.ui.typography.NotoFontsDeviceTest"},
            {"name": "tv1080", "target": "default", "api": 33, "size": "1920x1080", "density": 320,
             "classes": "dev.cinematv.TvUiRegressionTest,dev.cinematv.OfficialLibreTvPlaybackAcceptanceTest,dev.cinematv.TvWorkNavigationTest,dev.cinematv.TvManualWorkAssociationTest,dev.cinematv.TvLibraryCatalogControlsTest,dev.cinematv.TvRecommendationDashboardTest,dev.cinematv.GlassMaterialSmokeTest,dev.cinematv.ui.typography.NotoFontsDeviceTest"},
            {"name": "tv4k", "target": "default", "api": 33, "size": "3840x2160", "density": 640,
             "classes": "dev.cinematv.TvUiRegressionTest,dev.cinematv.TvWorkNavigationTest,dev.cinematv.TvManualWorkAssociationTest,dev.cinematv.TvLibraryCatalogControlsTest,dev.cinematv.TvRecommendationDashboardTest,dev.cinematv.GlassMaterialSmokeTest,dev.cinematv.ui.typography.NotoFontsDeviceTest"},
            {"name": "compat26", "target": "google_apis", "api": 26, "size": "1920x1080", "density": 320,
             "classes": "dev.cinematv.ui.typography.NotoFontsDeviceTest,dev.cinematv.GlassMaterialSmokeTest#materialSurvivesFocusPressReducedMotionMissingArtAndFailure,dev.cinematv.CinemaBrandStartupTest"},
        ]})

    def test_catalog_library_navigation_and_protocol_suites_are_registered(self):
        phone = matrix.select("phone")["include"][0]["classes"].split(",")
        for suite in ("dev.cinematv.data.LibreTvProtocolTest", "dev.cinematv.MobileWorkNavigationTest",
                      "dev.cinematv.MobileLibraryCatalogControlsTest"):
            self.assertEqual(phone.count(suite), 1)
        for target in ("tv1080", "tv4k"):
            classes = matrix.select(target)["include"][0]["classes"].split(",")
            for suite in ("dev.cinematv.TvWorkNavigationTest", "dev.cinematv.TvLibraryCatalogControlsTest"):
                self.assertEqual(classes.count(suite), 1)
        root = SCRIPTS.parent / "app/src/androidTest/java/dev/cinematv"
        protocol = (root / "data/LibreTvProtocolTest.kt").read_text()
        self.assertGreaterEqual(protocol.count("@Test"), 15)
        controls = (root / "LibraryCatalogControlsUiTest.kt").read_text()
        for method in ("browseAndSearchLoadMore", "partialThenExpiredPage", "cleanupCancelBackConfirmAndStatusRemoval",
                       "unconfiguredCatalogNeverRequestsUpstream"):
            self.assertEqual(controls.count("@Test fun " + method + "("), 2)

    def test_manual_association_suites_are_registered_without_replacing_navigation(self):
        phone = matrix.select("phone")["include"][0]["classes"].split(",")
        for suite in ("dev.cinematv.data.LocalWorkAssociationPersistenceTest", "dev.cinematv.MobileManualWorkAssociationTest"):
            self.assertEqual(phone.count(suite), 1)
        for target in ("tv1080", "tv4k"):
            self.assertEqual(matrix.select(target)["include"][0]["classes"].split(",").count("dev.cinematv.TvManualWorkAssociationTest"), 1)

    def test_official_playback_runs_on_phone_and_one_tv_only(self):
        targets = [device["name"] for device in matrix.DEVICES
                   if "dev.cinematv.OfficialLibreTvPlaybackAcceptanceTest" in device["classes"].split(",")]
        self.assertEqual(targets, ["phone", "tv1080"])

    def test_each_target_is_exact_subset(self):
        for target in matrix.DEVICES:
            self.assertEqual(matrix.select(target["name"])["include"], [target])

    def test_workflow_offers_every_target_and_all_test_classes_exist(self):
        root = SCRIPTS.parent
        workflow = (root / ".github/workflows/tv-smoke.yml").read_text()
        self.assertIn("options: [all, phone, tv1080, tv4k, compat26]", workflow)
        for device in matrix.DEVICES:
            for selector in device["classes"].split(","):
                class_name, _, method = selector.partition("#")
                path = root / "app/src/androidTest/java" / (class_name.replace(".", "/") + ".kt")
                if not path.exists():
                    # Kotlin permits several independently runnable test classes per file.
                    candidates = [candidate for candidate in path.parent.glob("*.kt")
                                  if "class " + class_name.rsplit(".", 1)[1] + " {" in candidate.read_text()]
                    self.assertEqual(len(candidates), 1, class_name)
                    path = candidates[0]
                source = path.read_text()
                self.assertIn("package " + class_name.rsplit(".", 1)[0], source)
                if method:
                    self.assertIn("fun " + method + "(", source)
        compatibility = matrix.select("compat26")["include"][0]
        self.assertEqual(compatibility["api"], 26)
        self.assertNotIn("TvUiRegressionTest", compatibility["classes"])
        brand_targets = [device["name"] for device in matrix.DEVICES
                         if "dev.cinematv.CinemaBrandStartupTest" in device["classes"].split(",")]
        self.assertEqual(brand_targets, ["phone", "compat26"])

    def test_unknown_target_fails_closed(self):
        for target in ("unknown", "", "ALL", "phone,tv1080"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                matrix.select(target)

    def test_cli_emits_one_json_line(self):
        result = subprocess.run([sys.executable, str(SCRIPTS / "qa-device-matrix.py"), "all"],
                                capture_output=True, text=True, timeout=5, check=True)
        self.assertEqual(len(result.stdout.splitlines()), 1)
        self.assertEqual(json.loads(result.stdout), matrix.select("all"))

    def test_cli_rejects_invalid_or_missing_target(self):
        for arguments in ([], ["typo"], ["phone", "tv4k"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run([sys.executable, str(SCRIPTS / "qa-device-matrix.py"), *arguments],
                                        capture_output=True, text=True, timeout=5)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(result.stdout)


# Each executable writes complete argument lists, then implements only its fixture.
STUB = r'''
import json, os, signal, sys, time
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with Path(name + "-calls.jsonl").open("a") as log:
    log.write(json.dumps(args) + "\n")
if name == "sleep":
    # Only virtualize the shell readiness sample interval. Python log/Gradle
    # waits and the real timeout executable retain their real clocks.
    if args != ["1"]:
        sys.exit("Unexpected readiness sleep: " + repr(args))
    sys.exit(0)
if name == "timeout":
    if os.environ.get("MOCK_REAL_TIMEOUT") == "1" and "gradle" in args:
        args[args.index("gradle") - 1] = "0.1s"
    os.execv(os.environ["REAL_TIMEOUT"], ["timeout", *args])
if name == "adb":
    if args == ["shell", "getprop", "sys.boot_completed"]:
        print("1")
    if args == ["shell", "dumpsys", "window"]:
        print(os.environ.get("MOCK_WINDOW", "mCurrentFocus=Window{123 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}\nmAwake=true mScreenOnFully=true mWindowManagerDrawComplete=true"))
        sys.exit(0)
    if args == ["get-state"]:
        print("device")
        sys.exit(int(os.environ["MOCK_DIAGNOSTIC_STATUS"]))
    if args[:3] == ["shell", "wm", os.environ.get("MOCK_SETUP_COMMAND", "size")]:
        sys.exit(int(os.environ["MOCK_SETUP_STATUS"]))
    if args[:2] == ["shell", "find"] and os.environ.get("MOCK_DEVICE_EVIDENCE") == "1":
        print("/sdcard/Android/media/dev.cinematv/ui-evidence\r")
    if args[:1] == ["pull"]:
        directory = Path("qa/ui-evidence")
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "partial.png").write_text("device evidence")
    if args[:1] == ["logcat"] and "-d" not in args:
        def stop(*_):
            Path("live-log-stopped").write_text("stopped")
            sys.exit(0)
        signal.signal(signal.SIGTERM, stop)
        Path("live-log-started").write_text(str(os.getpid()))
        print("CinemaTVQa: started", flush=True)
        while True:
            time.sleep(1)
    if "-d" in args or args[:2] == ["shell", "dumpsys"]:
        sys.exit(int(os.environ["MOCK_DIAGNOSTIC_STATUS"]))
if name == "gradle":
    # Ensure the background reader is running, to test cleanup on every path.
    for _ in range(100):
        if Path("live-log-started").exists():
            break
        time.sleep(.01)
    if os.environ.get("MOCK_REAL_TIMEOUT") == "1":
        time.sleep(30)
    if os.environ.get("MOCK_EXPORTED_EVIDENCE") == "1":
        directory = Path("app/build/outputs/connected_android_test_additional_output/device/ui-evidence")
        directory.mkdir(parents=True)
        (directory / "captured.png").write_text("exported evidence")
    if os.environ.get("MOCK_CONFLICT_EVIDENCE") == "1":
        for name in ("first", "second"):
            directory = Path("app/build/outputs/connected_android_test_additional_output") / name / "ui-evidence"
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "same.png").write_text(name)
    sys.exit(int(os.environ["MOCK_GRADLE_STATUS"]))
if name in ("free", "sudo"):
    print("host diagnostic fixture")
    sys.exit(int(os.environ["MOCK_DIAGNOSTIC_STATUS"]))
if name == "ps":
    print("3100 1 Sl qemu-system-x86")
    print("3224 1 Sl adb")
'''


class DeviceRunnerTest(unittest.TestCase):
    def run_harness(self, *, gradle=0, evidence=0, goldens=0, setup=0, setup_command="size",
                    diagnostics=0, tv=False, four_k=False, exported=False, device_evidence=False,
                    real_timeout=False, window=None, conflict_evidence=False, official=None, viewport=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binaries = root / "bin"
            binaries.mkdir()
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copy(SCRIPTS / "run-device-qa.sh", scripts)
            shutil.copy(SCRIPTS / "merge-ui-evidence.py", scripts)
            for name in ("adb", "gradle", "timeout", "free", "sudo", "ps", "sleep"):
                path = binaries / name
                path.write_text(f"#!{sys.executable}\n" + STUB)
                path.chmod(0o755)
            for script, key in [("validate-ui-evidence.py", "MOCK_EVIDENCE_STATUS"),
                                ("compare-ui-goldens.py", "MOCK_GOLDENS_STATUS")]:
                (scripts / script).write_text(
                    'import json, os, sys\nfrom pathlib import Path\n'
                    'Path(Path(__file__).name + "-args.json").write_text(json.dumps(sys.argv[1:]))\n'
                    f'sys.exit(int(os.environ["{key}"]))\n')
            env = dict(os.environ, PATH=f"{binaries}:{os.environ['PATH']}",
                       REAL_TIMEOUT=shutil.which("timeout"),
                       MOCK_GRADLE_STATUS=str(gradle), MOCK_EVIDENCE_STATUS=str(evidence),
                       MOCK_GOLDENS_STATUS=str(goldens), MOCK_SETUP_STATUS=str(setup),
                       MOCK_SETUP_COMMAND=setup_command, MOCK_DIAGNOSTIC_STATUS=str(diagnostics),
                       MOCK_EXPORTED_EVIDENCE=str(int(exported)),
                       MOCK_DEVICE_EVIDENCE=str(int(device_evidence)),
                       MOCK_REAL_TIMEOUT=str(int(real_timeout)),
                       MOCK_CONFLICT_EVIDENCE=str(int(conflict_evidence)))
            if window is not None:
                env["MOCK_WINDOW"] = window
            classes = "dev.cinematv.TvUiRegressionTest" if tv else "dev.cinematv.MobileNavigationSmokeTest"
            if official is not None:
                classes += ",dev.cinematv.OfficialLibreTvPlaybackAcceptanceTest"
                credentials = root / "synthetic-credential.json"
                credentials.write_text("disposable host fixture; no actual credentials")
                env["CINEMA_OFFICIAL_CREDENTIAL_FILE"] = str(credentials)
                official_scripts = scripts / "libretv-contracts"
                official_scripts.mkdir()
                (official_scripts / "run_android_test.py").write_text(
                    'from pathlib import Path\nimport sys\n'
                    'with Path("official-invocations").open("a") as out: out.write("once\\n")\n'
                    f'sys.exit({official})\n')
            size, density = ("3840x2160", "640") if four_k else ("1920x1080", "320")
            if viewport is not None:
                size = viewport
            result = subprocess.run(["bash", str(scripts / "run-device-qa.sh"), size, density, classes],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=10)
            self.files = {str(path.relative_to(root)): path.read_text()
                          for path in root.rglob("*") if path.is_file() and "bin" not in path.parts}
            self.calls = {name: [json.loads(line) for line in self.files.get(name + "-calls.jsonl", "").splitlines()]
                          for name in ("adb", "gradle", "timeout", "free", "sudo", "ps", "sleep")}
            stages = self.files["qa/stages.tsv"]
            return result.returncode, stages, "qa/device.log" in self.files, bool(self.calls["gradle"])

    def test_phone_success(self):
        code, stages, diagnostics, invoked = self.run_harness()
        self.assertEqual(code, 0)
        self.assertIn("instrumentation\t0\t", stages)
        self.assertIn("device-total\t0\t", stages)
        self.assertTrue(invoked)
        self.assertFalse(diagnostics)
        self.assertNotIn("goldens\t", stages)
        self.assertIn("live-log-stopped", self.files)
        for path in ("qa/host-memory-before.txt", "qa/host-memory-after.txt", "qa/android-crash.log"):
            self.assertIn(path, self.files)

    def test_tv_success_keeps_both_evidence_gates(self):
        code, stages, _, _ = self.run_harness(tv=True)
        self.assertEqual(code, 0)
        self.assertIn("evidence\t0\t", stages)
        self.assertIn("goldens\t0\t", stages)
        self.assertLess(stages.index("evidence\t"), stages.index("goldens\t"))

    def test_4k_uses_correct_viewport_and_reviewed_goldens(self):
        code, _, _, _ = self.run_harness(tv=True, four_k=True)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(self.files["validate-ui-evidence.py-args.json"]),
                         ["--directory", "qa/ui-evidence", "--size", "3840x2160", "--density", "640"])
        self.assertEqual(json.loads(self.files["compare-ui-goldens.py-args.json"]),
                         ["--baseline", "design/ui/goldens/tv4k", "--candidate", "qa/ui-evidence", "--output", "qa/golden"])

    def test_instrumentation_failure_and_timeout_remain_failures(self):
        for status in (7, 124, 137, 143):
            with self.subTest(status=status):
                code, stages, diagnostics, _ = self.run_harness(gradle=status, tv=True)
                self.assertEqual(code, status)
                self.assertIn(f"instrumentation\t{status}\t", stages)
                self.assertNotIn("goldens\t", stages)
                self.assertTrue(diagnostics)
                self.assertIn("live-log-stopped", self.files)

    def test_official_runs_once_after_primary_failure_without_masking_it(self):
        for primary, official in ((7, 0), (7, 23), (124, 0), (7, 124)):
            with self.subTest(primary=primary, official=official):
                code, stages, diagnostics, _ = self.run_harness(gradle=primary, official=official, tv=True)
                self.assertEqual(code, primary)
                self.assertEqual(self.files["official-invocations"], "once\n")
                self.assertEqual(stages.count("official-instrumentation\t"), 1)
                self.assertIn(f"instrumentation\t{primary}\t", stages)
                self.assertIn(f"official-instrumentation\t{official}\t", stages)
                self.assertIn(f"device-total\t{primary}\t", stages)
                self.assertLess(stages.index("instrumentation\t"), stages.index("official-instrumentation\t"))
                self.assertNotIn("goldens\t", stages)
                self.assertTrue(diagnostics)
                self.assertIn("live-log-stopped", self.files)
                self.assertIn(["shell", "run-as", "dev.cinematv", "rm", "-f", "files/official-acceptance.json"], self.calls["adb"])
                self.assertNotIn("OfficialLibreTvPlaybackAcceptanceTest", " ".join(self.calls["gradle"][0]))

    def test_official_failure_blocks_success_after_primary_passes(self):
        code, stages, diagnostics, _ = self.run_harness(official=23, tv=True)
        self.assertEqual(code, 23)
        self.assertIn("instrumentation\t0\t", stages)
        self.assertIn("official-instrumentation\t23\t", stages)
        self.assertNotIn("goldens\t", stages)
        self.assertTrue(diagnostics)

    def test_official_success_does_not_bypass_evidence_gate(self):
        code, stages, _, _ = self.run_harness(official=0, tv=True, evidence=23)
        self.assertEqual(code, 23)
        self.assertIn("official-instrumentation\t0\t", stages)
        self.assertIn("evidence\t23\t", stages)
        self.assertNotIn("goldens\t", stages)

    def test_both_test_stages_success_still_require_existing_gates(self):
        code, stages, _, _ = self.run_harness(official=0, tv=True)
        self.assertEqual(code, 0)
        for stage in ("instrumentation", "official-instrumentation", "evidence-export", "evidence", "goldens", "device-total"):
            self.assertIn(stage + "\t0\t", stages)
        self.assertEqual(self.files["official-invocations"], "once\n")

    def test_real_process_timeout_remains_failure_and_stops_trace(self):
        code, stages, diagnostics, _ = self.run_harness(real_timeout=True)
        self.assertEqual(code, 124)
        self.assertIn("instrumentation\t124\t", stages)
        self.assertTrue(diagnostics)
        self.assertIn("live-log-stopped", self.files)

    def test_evidence_failure_blocks_golden_comparison(self):
        code, stages, diagnostics, _ = self.run_harness(tv=True, evidence=23)
        self.assertEqual(code, 23)
        self.assertIn("evidence\t23\t", stages)
        self.assertNotIn("goldens\t", stages)
        self.assertTrue(diagnostics)

    def test_golden_failure_is_not_hidden_by_cleanup(self):
        code, stages, diagnostics, _ = self.run_harness(tv=True, goldens=42, diagnostics=19)
        self.assertEqual(code, 42)
        self.assertIn("goldens\t42\t", stages)
        self.assertIn("device-total\t42\t", stages)
        self.assertTrue(diagnostics)

    def test_setup_failure_does_not_start_gradle_or_later_setup(self):
        for command in ("size", "density"):
            with self.subTest(command=command):
                code, stages, diagnostics, invoked = self.run_harness(setup=9, setup_command=command)
                self.assertEqual(code, 9)
                self.assertIn("device-setup\t9\t", stages)
                self.assertIn("device-total\t9\t", stages)
                self.assertFalse(invoked)
                self.assertTrue(diagnostics)
                self.assertFalse(any(args[:2] == ["shell", "settings"] for args in self.calls["adb"]))

    def test_wake_avoids_input_dispatch_and_waits_around_resize(self):
        code, _, _, invoked = self.run_harness()
        self.assertEqual(code, 0)
        self.assertTrue(invoked)
        calls = self.calls["adb"]
        wake = calls.index(["shell", "svc", "power", "stayon", "true"])
        size = calls.index(["shell", "wm", "size", "1920x1080"])
        checks = [i for i, args in enumerate(calls) if args == ["shell", "getprop", "sys.boot_completed"]]
        self.assertEqual(len(checks), 4)
        self.assertEqual(self.calls["sleep"], [["1"], ["1"]])
        self.assertLess(wake, checks[0])
        self.assertLess(checks[0], size)
        self.assertLess(size, checks[2])
        self.assertFalse(any(args[:2] == ["shell", "input"] for args in calls))

    def test_actual_api33_ready_snapshot_reaches_instrumentation(self):
        code, _, _, invoked = self.run_harness(window=API33_READY_WINDOW)
        self.assertEqual(code, 0)
        self.assertTrue(invoked)
        self.assertEqual(self.calls["sleep"], [["1"], ["1"]])

    def test_large_window_dump_does_not_sigpipe_under_pipefail(self):
        window = ("mCurrentFocus=Window{123 u0 com.android.launcher3/.Launcher}\n"
                  "mAwake=true mScreenOnFully=true mWindowManagerDrawComplete=true\n" + "window detail\n" * 6000)
        code, _, _, invoked = self.run_harness(window=window)
        self.assertEqual(code, 0)
        self.assertTrue(invoked)

    def test_anr_is_preserved_and_fails_before_instrumentation(self):
        for package in ("com.google.android.apps.nexuslauncher", "dev.cinematv"):
            with self.subTest(package=package):
                window = f"mCurrentFocus=Window{{123 u0 Application Not Responding: {package}}}"
                code, _, _, invoked = self.run_harness(window=window)
                self.assertNotEqual(code, 0)
                self.assertFalse(invoked)
                self.assertIn(window, self.files["qa/setup-windows.txt"])
                self.assertFalse(any("force-stop" in args or "input" in args for args in self.calls["adb"]))

    def test_pending_anr_rejects_stale_and_recovered_launcher_before_dialog(self):
        # API33 dumpsys snapshot from the failed TV1080 boot: the ANR is
        # recorded before the "Application Not Responding" window is created.
        window = ("WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n"
                  "  ANR time: Nov 4, 2026 5:13:40 PM\n"
                  "  Application at fault: ActivityRecord{12ad4ac u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}\n"
                  "  Reason: Application does not have a focused window\n" +
                  API33_READY_WINDOW + "WINDOW MANAGER DISPLAY CONTENTS\n" + API33_READY_WINDOW)
        self.assertNotIn("Application Not Responding:", window)
        code, stages, _, invoked = self.run_harness(window=window)
        self.assertNotEqual(code, 0)
        self.assertFalse(invoked)
        self.assertIn("device-setup\t1\t", stages)
        self.assertIn("Application at fault:", self.files["qa/setup-windows.txt"])
        self.assertFalse(any(args[:3] == ["shell", "wm", "size"] for args in self.calls["adb"]))
        self.assertFalse(any("force-stop" in args or "input" in args for args in self.calls["adb"]))

    def test_gradle_retains_memory_and_worker_limits(self):
        self.run_harness()
        self.assertEqual(self.calls["gradle"], [[
            "--no-daemon", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8",
            "-Pkotlin.compiler.execution.strategy=in-process", ":app:connectedDebugAndroidTest",
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.cinematv.MobileNavigationSmokeTest",
        ]])

    def test_only_4k_receives_fifteen_minute_instrumentation_budget(self):
        for viewport, tv, expected in (("1080x1920", False, "12m"), ("1920x1080", True, "12m"),
                                       ("1920x1080", False, "12m"), ("3840x2160", True, "15m")):
            with self.subTest(viewport=viewport, tv=tv):
                code, _, _, _ = self.run_harness(viewport=viewport, tv=tv)
                self.assertEqual(code, 0)
                invocation = [args for args in self.calls["timeout"] if "gradle" in args]
                self.assertEqual(len(invocation), 1)
                self.assertEqual(invocation[0][:4], ["--signal=TERM", "--kill-after=30s", expected, "gradle"])

    def test_4k_budget_retains_failure_and_actual_timeout_propagation(self):
        for status, real_timeout in ((7, False), (124, True)):
            with self.subTest(status=status, real_timeout=real_timeout):
                code, stages, diagnostics, _ = self.run_harness(four_k=True, tv=True,
                    gradle=0 if real_timeout else status, real_timeout=real_timeout)
                self.assertEqual(code, status)
                self.assertIn(f"instrumentation\t{status}\t", stages)
                self.assertIn(f"device-total\t{status}\t", stages)
                self.assertNotIn("goldens\t", stages)
                self.assertTrue(diagnostics)
                self.assertIn("live-log-stopped", self.files)
                invocation = [args for args in self.calls["timeout"] if "gradle" in args]
                self.assertEqual(invocation[0][:4], ["--signal=TERM", "--kill-after=30s", "15m", "gradle"])

    def test_process_budgets_keep_kill_grace_and_stage_limits(self):
        self.run_harness(tv=True, gradle=7, device_evidence=True)
        calls = self.calls["timeout"]
        self.assertTrue(all(any(arg.startswith("--kill-after=") for arg in args) for args in calls))
        self.assertTrue(any(args[:4] == ["--signal=TERM", "--kill-after=30s", "12m", "gradle"] for args in calls))
        self.assertTrue(any(args[:4] == ["--signal=TERM", "--kill-after=5s", "14m", "adb"] for args in calls))
        self.assertTrue(any(args[:4] == ["--signal=TERM", "--kill-after=5s", "18m", "python3"] for args in calls))
        for args in calls:
            if "adb" in args and "logcat" not in args and "pull" not in args:
                budget = "5s" if "get-state" in args else "20s"
                self.assertEqual(args[:4], ["--signal=TERM", "--kill-after=5s", budget, "adb"])

    def test_exit_metadata_has_no_process_arguments_or_environments(self):
        code, _, _, _ = self.run_harness(gradle=7)
        self.assertEqual(code, 7)
        self.assertEqual(self.calls["ps"], [["-eo", "pid=,ppid=,stat=,comm="]])
        metadata = self.files["qa/emulator-state.txt"]
        self.assertIn("harness_exit_status=7", metadata)
        self.assertIn("adb_state=device", metadata)
        self.assertIn("3100 1 Sl qemu-system-x86", metadata)
        self.assertNotIn("3224", metadata)

    def test_exported_evidence_is_copied_before_validation(self):
        code, stages, _, _ = self.run_harness(tv=True, exported=True)
        self.assertEqual(code, 0)
        self.assertEqual(self.files["qa/ui-evidence/captured.png"], "exported evidence")
        self.assertLess(stages.index("evidence-export\t"), stages.index("evidence\t"))
        self.assertFalse(any(args[:1] == ["pull"] for args in self.calls["adb"]))

    def test_conflicting_class_evidence_fails_export_before_goldens(self):
        code, stages, diagnostics, invoked = self.run_harness(tv=True, conflict_evidence=True)
        self.assertTrue(invoked)
        self.assertEqual(code, 1)
        self.assertIn("evidence-export\t1\t", stages)
        self.assertNotIn("goldens\t", stages)
        self.assertTrue(diagnostics)
        self.assertNotIn("qa/ui-evidence/same.png", self.files)

    def test_failure_recovers_partial_device_evidence(self):
        code, _, _, _ = self.run_harness(gradle=7, device_evidence=True)
        self.assertEqual(code, 7)
        self.assertEqual(self.files["qa/ui-evidence/partial.png"], "device evidence")
        self.assertIn("qa/host-kernel.log", self.files)

    def test_usage_error_starts_no_device_work(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(["bash", str(SCRIPTS / "run-device-qa.sh")], cwd=directory,
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 2)
            self.assertIn("Usage:", result.stderr)
            self.assertFalse((Path(directory) / "qa").exists())


if __name__ == "__main__":
    unittest.main()
