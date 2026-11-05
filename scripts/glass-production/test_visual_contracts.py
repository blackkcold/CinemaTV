"""Host tests only; mock/synthetic evidence is never app or release evidence."""
from contextlib import ExitStack
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import run_visual as visual
from run_device import passed_instrumentation

ROOT = Path(__file__).resolve().parents[2]
SHA = "a" * 40


def junit_output(expected):
    lines = []
    for cls, method in expected:
        for status in (1, 0):
            lines.extend((f"INSTRUMENTATION_STATUS: class={cls}", f"INSTRUMENTATION_STATUS: test={method}",
                          f"INSTRUMENTATION_STATUS_CODE: {status}"))
    return "\n".join(lines + [f"OK ({len(expected)} tests)", "INSTRUMENTATION_CODE: -1", ""])


class VisualInventory(unittest.TestCase):
    def test_current_canonical_and_focused_methods_are_all_required(self):
        expected = visual.expected_tests()
        self.assertEqual(3, len({cls for cls, _ in expected}))
        self.assertEqual(1, sum(cls.endswith(".TvUiRegressionTest") for cls, _ in expected))
        self.assertEqual(len(expected), len(set(expected)))
        for cls in visual.CLASSES:
            source = (ROOT / f"app/src/androidTest/java/dev/cinematv/{cls}.kt").read_text()
            self.assertEqual(len(re.findall(r"@Test\s+fun\s+", source)), sum(name.endswith("." + cls) for name, _ in expected))

    def test_comments_strings_and_nested_comments_do_not_invent_tests(self):
        source = '''package dev.cinematv
        class Example {
            // @Test fun fake() {}
            /* nested /* @Test fun fake2() {} */ comment */
            val literal = "@Test fun fake3() {}"
            val raw = """@Test fun fake4() {}"""
            @Test fun real() { val braces = "{}" }
            @Test public fun second(): Unit {}
        }
        '''
        self.assertEqual(["real", "second"], visual.test_methods(source, "Example"))

    def test_unsupported_ignored_nested_empty_and_duplicate_fail_closed(self):
        cases = ("", "@Test fun real() {} @Test fun real() {}", "@Ignore @Test fun real() {}",
                 "@Test(timeout=10) fun real() {}", "@Test fun real(argument: Int) {}",
                 "class Nested { @Test fun nested() {} }", "@org.junit.Test fun fullName() {}")
        for methods in cases:
            with self.subTest(methods=methods), self.assertRaises(AssertionError):
                visual.test_methods("package dev.cinematv\nclass Example { " + methods + " }", "Example")
        with self.assertRaises(AssertionError):
            visual.test_methods("package dev.cinematv\nclass Example {}\nclass Other { @Test fun wrong() {} }", "Example")

    def test_exact_junit_rejects_none_skip_missing_duplicate_crash(self):
        expected = visual.expected_tests()
        output = junit_output(expected)
        self.assertTrue(passed_instrumentation(output, expected))
        for bad in ("OK (0 tests)\nINSTRUMENTATION_CODE: -1", junit_output(expected[:-1]),
                    junit_output(expected + [expected[0]]), output.replace("STATUS_CODE: 0", "STATUS_CODE: -3", 1),
                    output.replace("STATUS_CODE: 0", "STATUS_CODE: -4", 1), output + "Process crashed."):
            with self.subTest(bad=bad[-100:]):
                self.assertFalse(passed_instrumentation(bad, expected))

    def test_workflow_is_isolated_build_once_without_release_receipt(self):
        workflow = (ROOT / ".github/workflows/glass-visual-review.yml").read_text()
        self.assertIn("branches: [feature/glass-visual-review]", workflow)
        for forbidden in ("pull_request:", "workflow_call:", "schedule:", "preview-release.py", "promotion-receipt"):
            self.assertNotIn(forbidden, workflow)
        self.assertEqual(1, workflow.count(":app:assembleDebug :app:assembleDebugAndroidTest"))
        for stage in ("verify-production-source.py", ":backdrop:testDebugUnitTest", ":app:testDebugUnitTest", ":backdrop:lintDebug", ":app:lintDebug", "verify-noto-fonts.py", "apksigner", "evidence.py build"):
            self.assertIn(stage, workflow)
        native = workflow.split("  visual-review:\n", 1)[1]
        for forbidden in ("gradle/actions", "gradle --", "connectedDebugAndroidTest", "run-device-qa", "android_acceptance.py", "screenrecord"):
            self.assertNotIn(forbidden, native)
        for required in ("api-level: 33", "target: default", "emulator-build: '15004761'", "disable-animations: false", "timeout: 710s", "timeout: 1070s", "--kill-after=10s"):
            self.assertIn(required, native)
        self.assertEqual(("1920x1080", 320, 700), visual.VIEWPORTS["tv1080"])
        self.assertEqual(("3840x2160", 640, 1060), visual.VIEWPORTS["tv4k"])


class VisualEvidence(unittest.TestCase):
    def test_export_preserves_original_bytes_and_rejects_unsafe_names(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            def adb(*args, **kwargs):
                if "ls" in args:
                    return "01-home.png viewport.json\n"
                self.assertEqual(("exec-out", "run-as", "dev.cinematv", "cat"), args[:4])
                self.assertTrue(args[-1].startswith("files/visual-review/ui-evidence/"))
                self.assertTrue(kwargs["binary"])
                return b"exact-original-test-bytes"
            visual.export_evidence(directory, adb)
            self.assertEqual(b"exact-original-test-bytes", (directory / "01-home.png").read_bytes())
            for names in ("", "../escape.png", "01-home.png 01-home.png", "x.png;evil"):
                with self.subTest(names=names), self.assertRaises(AssertionError):
                    visual.export_evidence(directory, Mock(return_value=names))

    def test_old_golden_differences_are_reported_at_unchanged_thresholds(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "scripts").mkdir()
            shutil.copy(ROOT / "scripts/compare-ui-goldens.py", root / "scripts")
            baseline = root / "design/ui/goldens/tv1080"
            baseline.mkdir(parents=True)
            candidate = root / "candidate"
            candidate.mkdir()
            (baseline / "manifest.json").write_text(json.dumps({"sourceCommit": SHA, "screens": [{"file": "01-home.png"}]}))
            # Host-only synthetic fixtures exercise reporting, not Android rendering.
            Image.new("RGB", (16, 9), "black").save(baseline / "01-home.png")
            with patch.object(visual, "ROOT", root):
                for color, status in (("white", "differs"), ("black", "matches")):
                    Image.new("RGB", (16, 9), color).save(candidate / "01-home.png")
                    output = root / color
                    output.mkdir()
                    report = visual.golden_report("tv1080", candidate, output, 10)
                    self.assertEqual(status, report["status"])
                    self.assertTrue(report["informationalOnly"])
                    self.assertFalse(report["releaseGatePassed"])
                (candidate / "01-home.png").unlink()
                output = root / "missing"
                output.mkdir()
                with self.assertRaises(AssertionError):
                    visual.golden_report("tv1080", candidate, output, 10)

    def simulate(self, root, failure=None):
        expected = [("dev.cinematv.TvUiRegressionTest", "canonical")]
        calls = []
        log = Mock()
        log.finish.return_value = "11-05 test log with verified boundaries\n"
        if failure == "close":
            log.close.side_effect = RuntimeError("late duplicate marker")
        def verify(*args):
            calls.append("verify")
            if failure == "build":
                raise AssertionError("wrong APK hash")
            return {"sourceSha": SHA, "apks": {"first": "hash"}}
        def adb(*args, **kwargs):
            calls.append(args)
            if args[:1] == ("install",):
                return "Success\n"
            if args == ("shell", "getprop", "ro.build.version.sdk"):
                return "33\n"
            if args == ("shell", "pm", "list", "instrumentation"):
                return visual.RUNNER
            if args == ("shell", "pm", "clear", visual.PACKAGE):
                return "Success\n"
            if args == ("shell", "run-as", visual.PACKAGE, "pwd"):
                return "/data/user/0/dev.cinematv\n"
            return "native runtime data\n"
        def execute(command, **kwargs):
            if command[:4] == ["adb", "shell", "am", "instrument"]:
                calls.append("instrument")
                self.assertIn("/data/user/0/dev.cinematv/files/visual-review", command)
                kwargs["stdout"].write(junit_output([] if failure == "junit" else expected))
                if failure == "timeout":
                    raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            elif failure == "validation":
                raise subprocess.CalledProcessError(1, command)
            return SimpleNamespace(returncode=0)
        def export(directory, _):
            calls.append("export")
            directory.mkdir(exist_ok=True)
            from PIL import Image
            # Host-only pixels let the real PNG integrity validator run here.
            for name in ("01-home.png", "02-focused-rail.png"):
                Image.new("RGB", (16, 9), "black").save(directory / name)
            if failure == "truncated_non_golden":
                path = directory / "02-focused-rail.png"
                path.write_bytes(path.read_bytes()[:-20])
        with ExitStack() as stack:
            for name, value in (("ROOT", root), ("verify_build", verify), ("expected_tests", lambda: expected),
                                ("adb", adb), ("wait_launcher", Mock()), ("TestLogBoundary", Mock(return_value=log)),
                                ("export_evidence", export), ("golden_report", Mock(return_value={"status": "differs", "informationalOnly": True}))):
                stack.enter_context(patch.object(visual, name, value))
            stack.enter_context(patch.object(visual.subprocess, "check_output", side_effect=lambda cmd, **_: SHA if "rev-parse" in cmd else ""))
            stack.enter_context(patch.object(visual.subprocess, "run", side_effect=execute))
            stack.enter_context(patch("builtins.print"))
            status = visual.run(SimpleNamespace(viewport="tv1080", sha=SHA, apks=root / "dist", renderer="mock"))
        result = json.loads((root / "qa/glass-visual-review/tv1080/visual-review-result.json").read_text())
        return status, result, calls, log

    def test_runner_verifies_before_install_runs_once_and_marks_capture_only(self):
        with tempfile.TemporaryDirectory() as temp:
            status, result, calls, log = self.simulate(Path(temp))
            self.assertEqual(0, status)
            self.assertEqual("capture-passed", result["result"])
            self.assertFalse(result["releaseAcceptance"])
            self.assertFalse(result["officialMedia3Acceptance"])
            self.assertEqual("verify", calls[0])
            self.assertEqual(2, sum(isinstance(call, tuple) and call[0] == "install" for call in calls))
            self.assertEqual(1, calls.count("instrument"))
            self.assertEqual(1, calls.count("export"))
            log.start.assert_called_once()
            log.finish.assert_called_once()
            log.close.assert_called_once()

    def test_non_golden_truncated_png_fails_capture_before_hash_or_comparison(self):
        from PIL import Image
        name = "02-focused-rail.png"
        for viewport in visual.VIEWPORTS:
            manifest = json.loads((ROOT / "design/ui/goldens" / viewport / "manifest.json").read_text())
            self.assertNotIn(name, [screen["file"] for screen in manifest["screens"]])
        with tempfile.TemporaryDirectory() as temp:
            status, result, calls, _ = self.simulate(Path(temp), "truncated_non_golden")
            path = Path(temp) / "qa/glass-visual-review/tv1080/ui-evidence" / name
            # Header-only size inspection still passes for this truncated file.
            with Image.open(path) as image:
                self.assertEqual((16, 9), image.size)
            self.assertEqual(1, status)
            self.assertEqual("failed", result["result"])
            self.assertIn(name, result["error"])
            self.assertNotIn("captureSha256", result)
            self.assertNotIn("goldens", result)
            self.assertEqual(1, calls.count("export"))

    def test_png_format_and_full_decode_are_both_required(self):
        from PIL import Image
        import struct
        import zlib
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            path = directory / "14-player-panel.png"
            Image.new("RGB", (16, 9), "black").save(path, format="JPEG")
            with self.assertRaisesRegex(ValueError, "not a PNG"):
                visual.validate_pngs(directory)
            Image.new("RGB", (16, 9), "black").save(path, format="PNG")
            # Valid PNG container/CRC, invalid compressed pixels: verify() alone
            # accepts it, but the second open + load must reject it.
            data = bytearray(path.read_bytes())
            start = data.index(b"IDAT")
            length = struct.unpack(">I", data[start - 4:start])[0]
            data[start + 4] ^= 0xff
            end = start + 4 + length
            data[end:end + 4] = struct.pack(">I", zlib.crc32(data[start:end]))
            path.write_bytes(data)
            with Image.open(path) as image:
                image.verify()
            with self.assertRaisesRegex(ValueError, path.name):
                visual.validate_pngs(directory)

    def test_failures_cannot_become_capture_success_and_keep_partial_evidence(self):
        for failure in ("build", "junit", "timeout", "validation", "close"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                status, result, calls, _ = self.simulate(Path(temp), failure)
                self.assertEqual(1, status)
                self.assertEqual("failed", result["result"])
                self.assertFalse(result["releaseAcceptance"])
                if failure == "build":
                    self.assertNotIn("instrument", calls)
                    self.assertNotIn("export", calls)
                    self.assertFalse(any(isinstance(call, tuple) and call[0] == "install" for call in calls))
                else:
                    self.assertEqual(1, calls.count("instrument"))
                    self.assertEqual(1, calls.count("export"))


if __name__ == "__main__":
    unittest.main()
