import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/qa-results-audit.py"


class QaResultsAuditTest(unittest.TestCase):
    def run_audit(self, xml, *args):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "TEST-device.xml"
            path.write_text(xml)
            return subprocess.run(
                [sys.executable, str(SCRIPT), "--results", temp, *args],
                capture_output=True,
                text=True,
                timeout=10,
            )

    def valid_xml(self):
        return (
            '<testsuite tests="2" failures="0" errors="0" skipped="0">'
            '<testcase classname="dev.cinematv.TvLaunchSmokeTest" name="launch"/>'
            '<testcase classname="dev.cinematv.MainActivityPathSmokeTest" name="path"/>'
            "</testsuite>"
        )

    def test_valid_tv_lane_passes(self):
        result = self.run_audit(
            self.valid_xml(),
            "--require-executed", "TvLaunchSmokeTest,MainActivityPathSmokeTest",
            "--forbid-skipped",
            "--require-tv",
            "--characteristics", "emulator,tv",
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("tests=2", result.stdout)

    def test_skipped_required_class_fails(self):
        xml = (
            '<testsuite tests="1" failures="0" errors="0" skipped="1">'
            '<testcase classname="dev.cinematv.TvLaunchSmokeTest" name="launch"><skipped/></testcase>'
            "</testsuite>"
        )
        result = self.run_audit(xml, "--require-executed", "TvLaunchSmokeTest", "--forbid-skipped")
        self.assertEqual(1, result.returncode)
        self.assertIn("skipped", result.stdout)

    def test_failure_or_error_fails(self):
        for child in ("failure", "error"):
            with self.subTest(child=child):
                xml = (
                    '<testsuite tests="1">'
                    f'<testcase classname="dev.cinematv.TvLaunchSmokeTest" name="launch"><{child}/></testcase>'
                    "</testsuite>"
                )
                result = self.run_audit(xml)
                self.assertEqual(1, result.returncode)
                self.assertIn(child, result.stdout)

    def test_missing_required_class_fails(self):
        xml = (
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="dev.cinematv.OtherTest" name="run"/>'
            "</testsuite>"
        )
        result = self.run_audit(xml, "--require-executed", "TvLaunchSmokeTest")
        self.assertEqual(1, result.returncode)
        self.assertIn("missing:TvLaunchSmokeTest", result.stdout)

    def test_tv_identity_is_required(self):
        result = self.run_audit(self.valid_xml(), "--require-tv", "--characteristics", "emulator")
        self.assertEqual(1, result.returncode)
        self.assertIn("not-tv", result.stdout)

    def test_empty_results_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--results", temp],
                capture_output=True,
                text=True,
                timeout=10,
            )
        self.assertEqual(1, result.returncode)
        self.assertIn("no-junit-xml", result.stdout)


if __name__ == "__main__":
    unittest.main()
