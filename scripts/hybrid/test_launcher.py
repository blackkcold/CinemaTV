"""Exercise the real shell startup barrier with an ADB protocol stub, never an emulator."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import unittest
import zipfile


LAUNCHER = Path(__file__).with_name("run-tv-emulator.sh").resolve()
ADB_STUB = r'''#!/usr/bin/env python3
import json, os, pathlib, sys, time
root = pathlib.Path(os.environ["TV_STUB_ROOT"])
scenario = json.loads((root / "scenario.json").read_text())
statefile = root / "state.json"
state = json.loads(statefile.read_text()) if statefile.exists() else {"sample": -1, "unlock": 0}
args = sys.argv[3:]
if args == ["get-state"]:
    state["sample"] += 1
samples = scenario.get("samples", [{}])
sample = samples[min(state["sample"], len(samples) - 1)]
with (root / "calls.jsonl").open("a") as log:
    log.write(json.dumps({"sample": state["sample"], "args": args}) + "\n")
if args == ["shell", "input", "keyevent", "82"]:
    codes = scenario.get("unlock_codes", [0])
    code = codes[min(state["unlock"], len(codes) - 1)]
    state["unlock"] += 1
    statefile.write_text(json.dumps(state))
    if code:
        print("input service temporarily unavailable", file=sys.stderr)
    sys.exit(code)
statefile.write_text(json.dumps(state))
if args == ["get-state"]:
    if sample.get("hang"):
        time.sleep(30)
    if sample.get("offline"):
        print("adb: device offline", file=sys.stderr)
        sys.exit(1)
    print("device")
elif args == ["shell", "getprop", "sys.boot_completed"]:
    print(sample.get("boot", "1"))
elif args[:3] == ["shell", "service", "check"]:
    service = args[3]
    print("Service " + service + ": " + ("not found" if sample.get("missing") == service else "found"))
'''


class EmulatorLauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.adb = self.root / "adb"
        self.adb.write_text(ADB_STUB)
        self.adb.chmod(0o755)
        self.evidence = self.root / "startup"
        self.evidence.mkdir()

    def run_barrier(self, scenario, budget=5, alive=True):
        (self.root / "scenario.json").write_text(json.dumps(scenario))
        shell = f'''
source {shlex.quote(str(LAUNCHER))}
TV_ADB={shlex.quote(str(self.adb))}
TV_SERIAL=emulator-5554
TV_STARTUP_EVIDENCE={shlex.quote(str(self.evidence))}
TV_EMULATOR_PID={"$$" if alive else "2147483647"}
tv_wait_ready {budget} 0
printf 'harness-started\\n'
'''
        started = time.monotonic()
        result = subprocess.run(["bash", "-c", shell], text=True, capture_output=True,
                                timeout=8, env={**os.environ, "TV_STUB_ROOT": str(self.root)})
        self.elapsed = time.monotonic() - started
        calls = self.root / "calls.jsonl"
        self.calls = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
        return result

    def unlock_samples(self):
        return [call["sample"] for call in self.calls
                if call["args"] == ["shell", "input", "keyevent", "82"]]

    def test_offline_and_missing_services_never_unlock_early(self):
        result = self.run_barrier({"samples": [
            {"offline": True}, {"boot": "0"}, {"missing": "input"},
            {"missing": "window"}, {"missing": "activity"}, {}, {},
        ]})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.unlock_samples(), [6])
        self.assertIn("harness-started", result.stdout)

    def test_transport_reconnect_resets_consecutive_ready_samples(self):
        result = self.run_barrier({"samples": [{}, {"offline": True}, {}, {}]})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.unlock_samples(), [3])

    def test_not_found_is_not_treated_as_found(self):
        result = self.run_barrier({"samples": [{"missing": "input"}]}, budget=2)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.unlock_samples(), [])
        self.assertNotIn("harness-started", result.stdout)

    def test_unlock_failure_rechecks_services_before_retry(self):
        result = self.run_barrier({"unlock_codes": [255, 0]})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.unlock_samples(), [1, 3])
        self.assertIn("unlock_exit_status=255", (self.evidence / "readiness.txt").read_text())

    def test_persistent_unlock_failure_is_bounded_and_preserves_diagnostics(self):
        result = self.run_barrier({"unlock_codes": [255]}, budget=2)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("harness-started", result.stdout)
        self.assertLess(self.elapsed, 4)
        self.assertIn("input service temporarily unavailable", (self.evidence / "readiness.txt").read_text())

    def test_hanging_adb_cannot_exceed_startup_deadline(self):
        result = self.run_barrier({"samples": [{"hang": True}]}, budget=2)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.unlock_samples(), [])
        self.assertLess(self.elapsed, 4)

    def test_emulator_exit_fails_before_any_adb_input(self):
        result = self.run_barrier({}, alive=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls, [])

    def test_cleanup_preserves_harness_failure_and_removes_only_owned_directory(self):
        owned = self.root / "owned-avd"
        owned.mkdir()
        unrelated = self.root / "unrelated-avd"
        unrelated.mkdir()
        shell = f'''
source {shlex.quote(str(LAUNCHER))}
TV_STARTUP_EVIDENCE={shlex.quote(str(self.evidence))}
TV_WORKDIR={shlex.quote(str(owned))}
TV_EMULATOR_PID=
trap tv_finish EXIT
exit 42
'''
        result = subprocess.run(["bash", "-c", shell], text=True, capture_output=True, timeout=8)
        self.assertEqual(result.returncode, 42)
        self.assertEqual((self.evidence / "status.txt").read_text(), "launcher_exit_status=42\n")
        self.assertFalse(owned.exists())
        self.assertTrue(unrelated.exists())

    def test_unsupported_image_is_rejected_before_provisioning(self):
        result = subprocess.run(["bash", str(LAUNCHER), "36", "x86", "bundle", "evidence",
                                 "hybrid", "3840x2160", "640"], capture_output=True, text=True, timeout=3)
        self.assertEqual(result.returncode, 2)

    def test_cleanup_terminates_owned_emulator_process_group(self):
        pidfile = self.root / "emulator.pid"
        shell = f'''
source {shlex.quote(str(LAUNCHER))}
TV_STARTUP_EVIDENCE={shlex.quote(str(self.evidence))}
TV_WORKDIR=
TV_ADB=/bin/false
TV_SERIAL=emulator-5554
setsid sleep 60 &
TV_EMULATOR_PID=$!
printf '%s' "$TV_EMULATOR_PID" > {shlex.quote(str(pidfile))}
trap tv_finish EXIT
exit 42
'''
        result = subprocess.run(["bash", "-c", shell], text=True, capture_output=True, timeout=8)
        self.assertEqual(result.returncode, 42)
        with self.assertRaises(ProcessLookupError):
            os.kill(int(pidfile.read_text()), 0)

    def run_provisioning(self, build="15004761"):
        """Run the complete launcher with local SDK/archive fixtures and real cleanup."""
        sdk = self.root / "sdk"
        sdkbin = sdk / "cmdline-tools/latest/bin"
        sdkbin.mkdir(parents=True)
        (sdk / "platform-tools").mkdir()
        (sdk / "platform-tools/adb").symlink_to(self.adb)
        (self.root / "scenario.json").write_text("{}")
        calls = self.root / "provisioning.jsonl"
        common = '''#!/usr/bin/env python3
import json, os, pathlib, shutil, sys, time
root = pathlib.Path(os.environ["TV_STUB_ROOT"])
with (root / "provisioning.jsonl").open("a") as log:
    log.write(json.dumps({"tool": pathlib.Path(sys.argv[0]).name, "args": sys.argv[1:]}) + "\\n")
'''
        (sdkbin / "sdkmanager").write_text(common)
        (sdkbin / "avdmanager").write_text(common + '''
avd = pathlib.Path(os.environ["ANDROID_AVD_HOME"]) / "cinematv-ci.avd"
avd.mkdir()
(avd / "config.ini").write_text("hw.device.name=tv_1080p\\n")
''')
        for executable in sdkbin.iterdir():
            executable.chmod(0o755)
        emulator = common + '''
if "-version" in sys.argv:
    if "-no-window" not in sys.argv or "-no-audio" not in sys.argv:
        print("qemu-system-x86_64: libpulse.so.0: cannot open shared object file", file=sys.stderr)
        sys.exit(127)
    print("Android emulator version 36.4.10.0 (build_id " + os.environ["TV_STUB_BUILD"] + ")")
else:
    time.sleep(60)
'''
        with zipfile.ZipFile(self.root / "emulator.zip", "w") as archive:
            member = zipfile.ZipInfo("emulator/emulator")
            member.create_system = 3
            member.external_attr = 0o100755 << 16
            archive.writestr(member, emulator)
        fakebin = self.root / "bin"
        fakebin.mkdir()
        curl = fakebin / "curl"
        curl.write_text(common + '''
assert sys.argv[-1] == "https://dl.google.com/android/repository/emulator-linux_x64-15004761.zip"
shutil.copyfile(root / "emulator.zip", sys.argv[sys.argv.index("--output") + 1])
''')
        curl.chmod(0o755)
        harness = self.root / "scripts/hybrid/run-device.sh"
        harness.parent.mkdir(parents=True)
        harness.write_text("#!/usr/bin/env bash\nprintf 'harness-started\\n'\nexit 42\n")
        environment = {**os.environ, "TV_STUB_ROOT": str(self.root), "TV_STUB_BUILD": build,
                       "ANDROID_HOME": str(sdk), "RUNNER_TEMP": str(self.root),
                       "PATH": str(fakebin) + os.pathsep + os.environ["PATH"]}
        result = subprocess.run(["bash", str(LAUNCHER), "36", "x86_64", "bundle",
                                 str(self.root / "device-evidence"), "hybrid", "3840x2160", "640"],
                                cwd=self.root, env=environment, text=True, capture_output=True, timeout=15)
        return result, [json.loads(line) for line in calls.read_text().splitlines()]

    def test_headless_version_preflight_precedes_avd_and_harness(self):
        result, calls = self.run_provisioning()
        self.assertEqual(result.returncode, 42, result.stderr)
        self.assertIn("harness-started", result.stdout)
        version = next(i for i, call in enumerate(calls)
                       if call["tool"] == "emulator" and "-version" in call["args"])
        avd = next(i for i, call in enumerate(calls) if call["tool"] == "avdmanager")
        boot = next(i for i, call in enumerate(calls)
                    if call["tool"] == "emulator" and "-avd" in call["args"])
        self.assertLess(version, avd)
        self.assertLess(avd, boot)
        self.assertFalse((self.root / "device-evidence").exists())
        self.assertEqual(list(self.root.glob("cinema-tv-emulator.*")), [])

    def test_wrong_emulator_build_stops_before_avd_creation(self):
        result, calls = self.run_provisioning(build="150047610")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("harness-started", result.stdout)
        self.assertFalse(any(call["tool"] == "avdmanager" for call in calls))


if __name__ == "__main__":
    unittest.main()
