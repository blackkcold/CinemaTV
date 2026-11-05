#!/usr/bin/env python3
"""Reuse built APKs. One bounded native production test pass per viewport, no media suite."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from evidence import APK_NAMES, verify_build, verify_captures
from test_log import TestLogBoundary

PACKAGE = "dev.cinematv"
RUNNER = "dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner"
PRODUCTION = ("dev.cinematv.ProductionGlassUiTest", "actualProductionRootsRenderRequestedTiersAndKeepNativeInput")
BUTTON = ("dev.cinematv.MobileGlassButtonTest", "primaryAndSecondaryKeepNativeTouchTargetsRolesAndDisabledState")
BUTTON_EDGE = ("dev.cinematv.MobileGlassButtonTest", "pressedEdgeKeepsStableTouchTargetAndSingleClick")
MATERIAL = ("dev.cinematv.GlassMaterialSmokeTest", "materialSurvivesFocusPressReducedMotionMissingArtAndFailure")
MOTION = [("dev.cinematv.FocusMediaCardMotionTest", method) for method in (
    "focusPressAndReleaseKeepDistinctScalesAndSingleActivation",
    "touchPressWithoutFocusReturnsToIdentityOnCancel",
    "reducedMotionDisablesGeometryEvenMidSpringAndWhilePressed",
    "rootHintTransfersToNewFocusReversesAndSettlesWithoutLoop",
    "oneTestClockOwnsBothExactFreshnessAndScheduledExpiry",
    "backAndRouteResetCancelHintWithoutConsumingNavigation",
    "animationFramesDoNotRecomposeTheWholeCardAndGlassTierDoesNotChangeMotion",
    "identityCaptionsSelectionProgressAndLoadingSurviveImageFailure",
    "productionReducedMotionProviderKeepsApi26SafeAndVisibleFocus",
)]

FOCUS_RESTORE = [("dev.cinematv.InterruptedFocusRestorationTest", method) for method in (
    "nativeUpDuringEntryFramesKeepsNavigationAndRightCenterActivatesMovies",
    "nativeUpDuringLazyPrepareIsObservedBeforeNavigationRecomposition",
    "interruptedMissingLazyItemMustNotFallBackToHero",
    "nativeUpBetweenLazyAttachmentFramesKeepsNavigation",
    "nativeRightWhileLazyPrepareSuspendsPreservesNewContentTarget",
    "routeChangeDuringPrepareRejectsOldRequestBeforeRecomposition",
    "uninterruptedLazyRestorationKeepsExactSavedTarget",
    "missingBookmarkStillUsesNormalEntryFallback",
)]

ACTION_LAYOUT = [("dev.cinematv.GlassActionLayoutTest", method) for method in (
    "unconstrainedLabelsKeepDefaultPaddingAndFullSemantics",
    "fixedSizesPreserveIconsAndEllipsizeOnlyTheVisibleLabel",
    "nativeFocusAndPressKeepHitTargetAndTextLayoutStable",
)]


def expected_for(layout):
    return [PRODUCTION, BUTTON, BUTTON_EDGE] + ([MATERIAL, *MOTION, *FOCUS_RESTORE, *ACTION_LAYOUT] if layout == "tv" else [])


ERROR_MARKERS = ("ANR in dev.cinematv", "ANR in com.android", "FATAL EXCEPTION", "Application Not Responding:",
                 "Application Error:", "Application at fault:")


def passed_instrumentation(output, expected):
    """am instrument may exit zero for failed, skipped, crashed, or unexecuted tests."""
    if any(marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "Process crashed.")):
        return False
    starts, ends, status = [], [], {}
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if sep:
                status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.rsplit(": ", 1)[1])
            pair = (status.get("class"), status.get("test"))
            if code == 1:
                starts.append(pair)
            elif code == 0:
                ends.append(pair)
            else:
                return False
            status = {}
    return (sorted(starts) == sorted(expected) == sorted(ends)
            and re.search(r"^INSTRUMENTATION_CODE: -1\s*$", output, re.M) is not None
            and re.search(rf"^OK \({len(expected)} tests?\)\s*$", output, re.M) is not None)


def adb(*args, timeout=20, binary=False):
    return subprocess.check_output(["adb", *args], timeout=timeout, stderr=subprocess.STDOUT, text=not binary)


def reject_errors(text):
    assert not any(marker in text for marker in ERROR_MARKERS), "Emulator/app ANR or crash found; no dialogs were dismissed"


def wait_launcher(directory):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        windows = adb("shell", "dumpsys", "window", timeout=min(10, max(1, int(deadline - time.monotonic()))))
        (directory / "setup-windows.txt").write_text(windows)
        reject_errors(windows)
        if re.search(r"mCurrentFocus=Window\{[^}]+ (?:com\.google\.android\.apps\.nexuslauncher|com\.android\.launcher3)/", windows):
            return
        time.sleep(1)
    raise RuntimeError("No focused launcher after bounded device setup")


def run_layout(args, layout, sdk):
    directory = Path("qa/production-glass") / f"api{sdk}-{layout}"
    directory.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    result = {"result": "failed", "sdk": sdk, "layout": layout, "sourceSha": args.sha}
    test_log = None
    try:
        size, density = ("1920x1080", "320") if layout == "tv" else ("1080x2400", "420")
        assert adb("shell", "pm", "clear", PACKAGE).strip() == "Success"
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", "0")
        adb("shell", "wm", "size", size)
        adb("shell", "wm", "density", density)
        adb("shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed")
        adb("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
        adb("shell", "svc", "power", "stayon", "true")
        adb("shell", "wm", "dismiss-keyguard")
        wait_launcher(directory)
        runtime = {"sourceSha": args.sha, "sdk": sdk, "layout": layout, "size": size, "density": int(density),
                   "fingerprint": adb("shell", "getprop", "ro.build.fingerprint").strip(),
                   "abi": adb("shell", "getprop", "ro.product.cpu.abi").strip(),
                   "emulatorGpu": args.renderer,
                   "rendererScope": "Android hardware Canvas backed by emulator software GPU; not physical performance evidence"}
        (directory / "runtime.json").write_text(json.dumps(runtime, indent=2) + "\n")
        for label, command in (("display", ("shell", "wm", "size")), ("surfaceflinger", ("shell", "dumpsys", "SurfaceFlinger")),
                               ("power", ("shell", "dumpsys", "power")), ("memory", ("shell", "getprop", "dalvik.vm.heapgrowthlimit"))):
            (directory / f"{label}.txt").write_text(adb(*command))
        test_log = TestLogBoundary(directory, adb)
        test_log.start()  # Must read our marker back before any test can start.
        expected = expected_for(layout)
        classes = ",".join(dict.fromkeys(pair[0] for pair in expected))
        # Budget covers setup + instrumentation; no Gradle or APK rebuild in this job.
        remaining = max(1, min(520, int(580 - (time.monotonic() - start))))
        proc = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", classes,
                               "-e", "glassLayout", layout, "-e", "glassSourceSha", args.sha, RUNNER],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=remaining)
        (directory / "instrumentation.txt").write_text(proc.stdout)
        device_log = test_log.finish()  # Verified END also fences streamed log delivery.
        reject_errors(device_log)
        assert proc.returncode == 0 and passed_instrumentation(proc.stdout, expected), "Expected exact executed passing tests, no skips"
        evidence = directory / "native"
        evidence.mkdir(exist_ok=True)
        # run-as exports app-owned fixture files after tests. No screen-capture fallback.
        names = adb("shell", "run-as", PACKAGE, "ls", "files/production-glass").split()
        for name in names:
            assert re.fullmatch(r"[a-zA-Z0-9_.-]+", name), "Unexpected evidence filename"
            (evidence / name).write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", f"files/production-glass/{name}", binary=True))
        windows = adb("shell", "dumpsys", "window")
        (directory / "windows-after.txt").write_text(windows)
        reject_errors(windows)
        result.update(verify_captures(evidence, args.sha, sdk, layout))
        result["executedTests"] = ["#".join(pair) for pair in expected]
    except Exception as error:
        result["error"] = f"{type(error).__name__}: {error}"
        # Keep partial native evidence even when JUnit or an actual capture failed.
        try:
            evidence = directory / "native"
            evidence.mkdir(exist_ok=True)
            for name in adb("shell", "run-as", PACKAGE, "ls", "files/production-glass", timeout=5).split():
                if re.fullmatch(r"[a-zA-Z0-9_.-]+", name):
                    (evidence / name).write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", f"files/production-glass/{name}", timeout=5, binary=True))
        except Exception:
            pass
    finally:
        if test_log is not None:
            try:
                test_log.close()
            except Exception as error:
                result["result"] = "failed"
                result["logCleanupError"] = f"{type(error).__name__}: {error}"
        for name, command in (("activity-after.txt", ("shell", "dumpsys", "activity", "top")),
                               ("windows-after.txt", ("shell", "dumpsys", "window"))):
            try:
                (directory / name).write_text(adb(*command, timeout=5))
            except Exception:
                pass
        result["elapsedSeconds"] = round(time.monotonic() - start, 2)
        (directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2), flush=True)
    return result["result"] == "passed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apks", type=Path, default=Path("dist"))
    parser.add_argument("--sha", required=True)
    parser.add_argument("--sdk", type=int, required=True)
    parser.add_argument("--layouts", nargs="+", choices=("tv", "phone"), required=True)
    parser.add_argument("--renderer", default="lavapipe; emulator build 15004761; Vulkan disabled")
    args = parser.parse_args()
    verify_build(args.apks, args.sha)
    sdk = int(adb("shell", "getprop", "ro.build.version.sdk").strip())
    assert sdk == args.sdk, "Wrong runtime SDK"
    for name in APK_NAMES:
        assert "Success" in adb("install", "-r", "-t", str(args.apks / name), timeout=90)
    assert RUNNER in adb("shell", "pm", "list", "instrumentation")
    results = []
    for layout in args.layouts:
        results.append(run_layout(args, layout, sdk))
        # A failed/ANR emulator is not recycled into a false-success next target.
        if not results[-1]:
            break
    return 0 if len(results) == len(args.layouts) and all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
