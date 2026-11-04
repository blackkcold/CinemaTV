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


class DeviceMatrixTest(unittest.TestCase):
    def test_default_retains_all_acceptance_targets_and_classes(self):
        self.assertEqual(matrix.select("all"), {"include": [
            {"name": "phone", "api": 30, "size": "1080x1920", "density": 420,
             "classes": "dev.cinematv.MobileNavigationSmokeTest,dev.cinematv.TvLaunchSmokeTest,dev.cinematv.MobilePlaybackSmokeTest,dev.cinematv.data.LocalMediaLibraryTest,dev.cinematv.data.RepositoryContractTest"},
            {"name": "tv1080", "api": 33, "size": "1920x1080", "density": 320,
             "classes": "dev.cinematv.TvUiRegressionTest"},
            {"name": "tv4k", "api": 33, "size": "3840x2160", "density": 640,
             "classes": "dev.cinematv.TvUiRegressionTest"},
        ]})

    def test_each_target_is_exact_subset(self):
        for target in matrix.DEVICES:
            self.assertEqual(matrix.select(target["name"])["include"], [target])

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
if name == "timeout":
    if os.environ.get("MOCK_REAL_TIMEOUT") == "1" and "gradle" in args:
        args[args.index("12m")] = "0.1s"
    os.execv(os.environ["REAL_TIMEOUT"], ["timeout", *args])
if name == "adb":
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
                    real_timeout=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binaries = root / "bin"
            binaries.mkdir()
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copy(SCRIPTS / "run-device-qa.sh", scripts)
            for name in ("adb", "gradle", "timeout", "free", "sudo", "ps"):
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
                       MOCK_REAL_TIMEOUT=str(int(real_timeout)))
            classes = "dev.cinematv.TvUiRegressionTest" if tv else "dev.cinematv.MobileNavigationSmokeTest"
            size, density = ("3840x2160", "640") if four_k else ("1920x1080", "320")
            result = subprocess.run(["bash", str(scripts / "run-device-qa.sh"), size, density, classes],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=10)
            self.files = {str(path.relative_to(root)): path.read_text()
                          for path in root.rglob("*") if path.is_file() and "bin" not in path.parts}
            self.calls = {name: [json.loads(line) for line in self.files.get(name + "-calls.jsonl", "").splitlines()]
                          for name in ("adb", "gradle", "timeout", "free", "sudo", "ps")}
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

    def test_gradle_retains_memory_and_worker_limits(self):
        self.run_harness()
        self.assertEqual(self.calls["gradle"], [[
            "--no-daemon", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8",
            "-Pkotlin.compiler.execution.strategy=in-process", ":app:connectedDebugAndroidTest",
            "-Pandroid.testInstrumentationRunnerArguments.class=dev.cinematv.MobileNavigationSmokeTest",
        ]])

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
