#!/usr/bin/env python3
"""Capture canonical TV PNGs for human review, never release/media acceptance."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time

from evidence import APK_NAMES, digest, verify_build
from run_device import PACKAGE, RUNNER, adb, passed_instrumentation, reject_errors, wait_launcher
from test_log import TestLogBoundary

ROOT = Path(__file__).resolve().parents[2]
CLASSES = ("TvUiRegressionTest", "GlassActionLayoutTest", "FocusMediaCardMotionTest", "ContentRailVisibilityTest")
VIEWPORTS = {"tv1080": ("1920x1080", 320, 700), "tv4k": ("3840x2160", 640, 1420)}
DEVICE_EVIDENCE = "files/visual-review/ui-evidence"
PURPOSE = "Visual review capture only; not release acceptance, official Media3 acceptance, or physical-device performance evidence"


def kotlin_code(source):
    """Mask comments/literals before examining annotations; preserve offsets/braces."""
    chars = list(source)
    index = 0
    while index < len(source):
        start = index
        if source.startswith("//", index):
            end = source.find("\n", index)
            index = len(source) if end < 0 else end
        elif source.startswith("/*", index):
            depth, index = 1, index + 2
            while depth and index < len(source):
                if source.startswith("/*", index):
                    depth, index = depth + 1, index + 2
                elif source.startswith("*/", index):
                    depth, index = depth - 1, index + 2
                else:
                    index += 1
            assert depth == 0, "Unterminated Kotlin comment"
        elif source.startswith('"""', index):
            end = source.find('"""', index + 3)
            assert end >= 0, "Unterminated Kotlin raw string"
            index = end + 3
        elif source[index] in "\"'":
            quote, index = source[index], index + 1
            while index < len(source) and source[index] != quote:
                index += 2 if source[index] == "\\" else 1
            assert index < len(source), "Unterminated Kotlin literal"
            index += 1
        else:
            index += 1
            continue
        chars[start:index] = ["\n" if char == "\n" else " " for char in source[start:index]]
    return "".join(chars)


def test_methods(source, class_name):
    """Fail closed on unsupported signatures, nested tests, duplicates, or @Ignore."""
    code = kotlin_code(source)
    assert re.search(r"^package\s+dev\.cinematv\s*$", code, re.M), "Unexpected test package"
    assert not re.search(r"@(?:org\.junit\.)?Ignore\b", code), "Ignored visual-review test"
    declaration = re.search(r"\bclass\s+" + re.escape(class_name) + r"\s*\{", code)
    assert declaration, f"Expected a plain, non-inheriting test class: {class_name}"
    depth, class_end = 1, declaration.end()
    while depth and class_end < len(code):
        depth += (code[class_end] == "{") - (code[class_end] == "}")
        class_end += 1
    assert depth == 0, "Unclosed Kotlin test class"
    annotations = list(re.finditer(r"@(?:org\.junit\.)?Test\b", code))
    matches = list(re.finditer(r"@Test\s+(?:public\s+)?fun\s+([A-Za-z_]\w*)\s*\(\s*\)\s*(?::\s*Unit\s*)?\{", code))
    assert annotations and len(matches) == len(annotations), f"Missing/unsupported @Test method: {class_name}"
    for match in matches:
        preceding = code[declaration.end():match.start()]
        assert declaration.end() < match.start() < class_end and preceding.count("{") == preceding.count("}"), "Test outside the expected class body"
    methods = [match[1] for match in matches]
    assert len(methods) == len(set(methods)), f"Duplicate @Test methods: {class_name}"
    return methods


def expected_tests(root=ROOT):
    expected = []
    for cls in CLASSES:
        source = (root / f"app/src/androidTest/java/dev/cinematv/{cls}.kt").read_text()
        methods = test_methods(source, cls)
        if cls == "TvUiRegressionTest":
            assert methods == ["remoteRoutesRestoreOffscreenItemsAndPlayerPanels"], "Canonical TV capture scenario changed; review the harness"
        expected.extend(("dev.cinematv." + cls, method) for method in methods)
    return expected


def export_evidence(directory, call_adb):
    directory.mkdir(exist_ok=True)
    names = call_adb("shell", "run-as", PACKAGE, "ls", DEVICE_EVIDENCE).split()
    assert names and len(names) <= 32 and len(names) == len(set(names)), "Empty, duplicate, or excessive evidence files"
    for name in names:
        assert re.fullmatch(r"[A-Za-z0-9_-]+\.(?:png|json|txt)", name), "Unexpected evidence filename"
        (directory / name).write_bytes(call_adb("exec-out", "run-as", PACKAGE, "cat", f"{DEVICE_EVIDENCE}/{name}", binary=True))


def validate_pngs(directory):
    """Verify and fully decode every exported PNG, including non-golden routes."""
    from PIL import Image, ImageFile

    assert not ImageFile.LOAD_TRUNCATED_IMAGES, "Permissive truncated-image decoding is forbidden"
    paths = sorted(directory.glob("*.png"))
    assert paths, "No exported PNG captures"
    for path in paths:
        try:
            with Image.open(path) as image:
                assert image.format == "PNG", "Capture is not a PNG"
                image.verify()
            # verify() checks container structure without decoding pixel data.
            # Reopen before load(): every image must also survive full decoding.
            with Image.open(path) as image:
                assert image.format == "PNG", "Capture is not a PNG"
                image.load()
        except Exception as error:
            raise ValueError(f"Invalid native PNG {path.name}: {error}") from error


def golden_report(viewport, evidence, directory, timeout):
    """Keep unchanged thresholds, but label old-golden differences as review input."""
    baseline = ROOT / "design/ui/goldens" / viewport
    output = directory / "golden-difference"
    proc = subprocess.run([sys.executable, str(ROOT / "scripts/compare-ui-goldens.py"),
                           "--baseline", str(baseline), "--candidate", str(evidence), "--output", str(output)],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    (directory / "golden-comparison.txt").write_text(proc.stdout)
    # Exit 1 means a reviewable difference only when a complete numerical report
    # exists. A missing PNG/baseline, bad manifest, or script crash is not a diff.
    report = json.loads((output / "comparison.json").read_text())
    manifest = json.loads((baseline / "manifest.json").read_text())
    rows = report["screens"]
    names = [row["screen"] for row in rows]
    assert names and len(names) == len(set(names)) and sorted(names) == sorted(row["file"] for row in manifest["screens"])
    assert report["meanLimit"] == .020 and report["changedLimit"] == .08, "Golden thresholds changed"
    assert all("meanError" in row and "changedRatio" in row and type(row["passed"]) is bool and "reason" not in row for row in rows), "Incomplete golden comparison"
    matches = all(row["passed"] for row in rows)
    assert proc.returncode == (0 if matches else 1), "Golden comparison failed unexpectedly"
    return {"status": "matches" if matches else "differs", "informationalOnly": True,
            "baselineSourceCommit": report["sourceCommit"], "report": "golden-difference/comparison.json",
            "releaseGatePassed": False}


def run(args):
    size, density, budget = VIEWPORTS[args.viewport]
    directory = ROOT / "qa/glass-visual-review" / args.viewport
    # Stale captures must never satisfy a new invocation, including local reruns.
    directory.mkdir(parents=True, exist_ok=True)
    assert not any(directory.iterdir()), f"Evidence destination is not empty: {directory}"
    start = time.monotonic()
    deadline = start + budget
    result = {"schema": 1, "result": "failed", "purpose": PURPOSE, "sourceSha": args.sha,
              "viewport": args.viewport, "releaseAcceptance": False, "officialMedia3Acceptance": False}
    test_log = None
    exported = False
    instrument_started = False

    def remaining(limit=20):
        value = min(limit, deadline - time.monotonic())
        if value <= 0:
            raise TimeoutError("Visual-review viewport budget exhausted")
        return value

    def call_adb(*command, timeout=20, binary=False):
        return adb(*command, timeout=remaining(timeout), binary=binary)

    try:
        assert re.fullmatch(r"[0-9a-f]{40}", args.sha), "Expected immutable source SHA"
        assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, timeout=remaining()).strip() == args.sha
        assert not subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT, text=True, timeout=remaining()).strip(), "Dirty capture source"
        build = verify_build(args.apks, args.sha)  # Before either install.
        (directory / "build-provenance.json").write_text(json.dumps(build, indent=2) + "\n")
        expected = expected_tests()
        result["expectedTests"] = ["#".join(pair) for pair in expected]
        sdk = int(call_adb("shell", "getprop", "ro.build.version.sdk").strip())
        assert sdk == 33, "Visual review requires the canonical API33 runtime"
        for name in APK_NAMES:
            assert re.search(r"^Success\s*$", call_adb("install", "-r", "-t", str(args.apks / name), timeout=90), re.M), f"APK install failed: {name}"
        assert RUNNER in call_adb("shell", "pm", "list", "instrumentation")
        assert call_adb("shell", "pm", "clear", PACKAGE).strip() == "Success"
        for command in (("settings", "put", "system", "accelerometer_rotation", "0"),
                        ("settings", "put", "system", "user_rotation", "0"),
                        ("wm", "size", size), ("wm", "density", str(density)),
                        ("settings", "put", "secure", "immersive_mode_confirmations", "confirmed"),
                        ("settings", "put", "system", "screen_off_timeout", "2147483647"),
                        ("svc", "power", "stayon", "true"), ("wm", "dismiss-keyguard")):
            call_adb("shell", *command)
        wait_launcher(directory)
        data_path = call_adb("shell", "run-as", PACKAGE, "pwd").strip()
        assert re.fullmatch(r"/data/(?:user/0|data)/dev\.cinematv", data_path), "Unexpected app-private directory"
        runtime = {"sourceSha": args.sha, "sdk": sdk, "viewport": args.viewport, "size": size, "density": density,
                   "fingerprint": call_adb("shell", "getprop", "ro.build.fingerprint").strip(),
                   "abi": call_adb("shell", "getprop", "ro.product.cpu.abi").strip(),
                   "requestedEmulatorGpu": args.renderer, "scope": PURPOSE}
        (directory / "runtime.json").write_text(json.dumps(runtime, indent=2) + "\n")
        for label, command in (("display", ("wm", "size")), ("density", ("wm", "density")),
                               ("surfaceflinger", ("dumpsys", "SurfaceFlinger")), ("power", ("dumpsys", "power"))):
            (directory / f"{label}.txt").write_text(call_adb("shell", *command))
        test_log = TestLogBoundary(directory, call_adb)
        test_log.start()
        classes = ",".join("dev.cinematv." + cls for cls in CLASSES)
        # One invocation only. Reserve 90 seconds for original PNG export and
        # validation; workflow's hard bound also covers installation and cleanup.
        instrument_timeout = remaining(budget) - 90
        assert instrument_timeout > 0, "No instrumentation budget remains"
        instrument_started = True
        with (directory / "instrumentation.txt").open("w") as output:
            proc = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", classes,
                                   "-e", "additionalTestOutputDir", data_path + "/files/visual-review", RUNNER],
                                  stdout=output, stderr=subprocess.STDOUT, text=True, timeout=instrument_timeout)
        reject_errors(test_log.finish())
        assert proc.returncode == 0 and passed_instrumentation((directory / "instrumentation.txt").read_text(), expected), "Expected exact executed passing tests, no skips or duplicates"
        result["executedTests"] = ["#".join(pair) for pair in expected]
        evidence = directory / "ui-evidence"
        export_evidence(evidence, call_adb)
        exported = True
        with (directory / "evidence-validation.txt").open("w") as output:
            subprocess.run([sys.executable, str(ROOT / "scripts/validate-ui-evidence.py"), "--directory", str(evidence),
                            "--size", size, "--density", str(density)], stdout=output, stderr=subprocess.STDOUT,
                           timeout=remaining(30), check=True)
        validate_pngs(evidence)
        result["captureSha256"] = {path.name: digest(path) for path in sorted(evidence.iterdir())}
        result["goldens"] = golden_report(args.viewport, evidence, directory, remaining(45))
        windows = call_adb("shell", "dumpsys", "window")
        (directory / "windows-after.txt").write_text(windows)
        reject_errors(windows)
        result["result"] = "capture-passed"
    except Exception as error:
        result["error"] = f"{type(error).__name__}: {error}"
        # Keep app-generated partial PNGs even if instrumentation fails. They
        # remain failed evidence and are never replaced by host-made screenshots.
        if instrument_started and not exported:
            try:
                export_evidence(directory / "ui-evidence", call_adb)
            except Exception as export_error:
                result["partialExportError"] = f"{type(export_error).__name__}: {export_error}"
    finally:
        if test_log is not None:
            try:
                test_log.close()
            except Exception as error:
                result.update(result="failed", logCleanupError=f"{type(error).__name__}: {error}")
        for name, command in (("activity-after.txt", ("dumpsys", "activity", "top")),
                               ("windows-final.txt", ("dumpsys", "window"))):
            try:
                text = call_adb("shell", *command, timeout=5)
                (directory / name).write_text(text)
                reject_errors(text)
            except Exception as error:
                result.update(result="failed", finalDiagnosticsError=f"{type(error).__name__}: {error}")
        result["elapsedSeconds"] = round(time.monotonic() - start, 2)
        (directory / "visual-review-result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2), flush=True)
    return 0 if result["result"] == "capture-passed" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apks", type=Path, default=ROOT / "dist")
    parser.add_argument("--sha", required=True)
    parser.add_argument("--viewport", choices=VIEWPORTS, required=True)
    parser.add_argument("--renderer", default="lavapipe; emulator build 15004761; Vulkan disabled")
    return run(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())
