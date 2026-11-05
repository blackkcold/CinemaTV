#!/usr/bin/env python3
"""Real adb D-pad input, one nondebuggable production-root APK, interleaved tiers.

Native receive times delimit 450 ms windows. Each key is followed by >=700 ms,
so startup, warmup, log collection, and static gaps are excluded from analysis.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
from evidence import APK_NAME, PACKAGE, sha256, verify_build
from protocol import CYCLE, ORDERS, QUALITY

ACTIVITY = PACKAGE + "/dev.cinematv.GlassBenchmarkActivity"
TAG = "ProductionGlassBenchmark"


def adb(*args, timeout=25, binary=False):
    return subprocess.check_output(["adb", *args], text=not binary, timeout=timeout)


def control(key):
    adb("shell", "am", "start", "-W", "-n", ACTIVITY, "-f", "0x20000000", "--ez", key, "true")


def logs():
    return adb("logcat", "-d", "-v", "brief", "-s", TAG + ":I")


def records(kind, run_id):
    result = []
    for line in logs().splitlines():
        match = re.search(r"\): " + re.escape(kind) + r" (\{.*\})$", line)
        if match:
            value = json.loads(match[1])
            identity = value.get("runId") or value.get("after", {}).get("runId")
            if identity == run_id:
                result.append(value)
    return result


def foreground():
    pid = adb("shell", "pidof", PACKAGE).strip()
    windows = adb("shell", "dumpsys", "window")
    assert pid and any("mCurrentFocus" in line and PACKAGE in line for line in windows.splitlines()), "Benchmark lost foreground"
    return pid


def wait_text(text, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if text in logs():
            return
        time.sleep(.2)
    raise AssertionError("Missing native marker: " + text)


def status(run_id):
    control("benchmark_status")
    values = records("status", run_id)
    assert values, "No native status receipt"
    return values[-1]


def wait_ready(run_id):
    deadline = time.monotonic() + 45
    last = None
    while time.monotonic() < deadline:
        last = status(run_id)
        if (last["ready"] and last["route"] == "HOME" and
                last["draws"].get(last["actualMode"].lower(), 0) > 0):
            return last
        time.sleep(.5)
    raise AssertionError("Fonts/catalog/decoded-scene/window not ready: " + json.dumps(last))


def validate_tier(value, tier):
    assert value["ready"] and value["foreground"] and value["fontsReady"], "Root not ready"
    assert value["actualMode"] == tier and not value["failed"], "Production policy/renderer fell back: " + json.dumps(value)
    assert value["animationsEnabled"], "Animations disabled by actual production policy"
    assert value["draws"][tier.lower()] > 0, "No actual draws in requested tier"
    if tier != "FULL":
        assert value["draws"]["full"] == 0, "Unexpected optical draw outside Full tier"
    if tier == "BASIC":
        assert value["draws"]["blur"] == 0, "Unexpected blur in Basic tier"


def key(run_id, index, code, expected_route=None, label="prepare"):
    host_start = time.time_ns()
    adb("shell", "input", "keyevent", str(code))
    time.sleep(.7)  # Collection begins after 450 ms native activity window.
    deadline = time.monotonic() + 15
    native = None
    while time.monotonic() < deadline:
        native = next((r for r in records("input", run_id) if r["index"] == index), None)
        if native is not None:
            break
        time.sleep(.2)
    assert native is not None, f"No native key receipt for {index}/{code}"
    assert native["key"] == code, "Input stream differs from plan"
    if expected_route:
        assert native["after"]["route"] == expected_route, f"{label}: {native['after']['route']} != {expected_route}"
    assert native["after"]["foreground"], "Lost foreground while input was processed"
    return {"index": index, "key": code, "label": label, "expectedRoute": expected_route,
            "hostStartNs": host_start, "native": native}


def launch(run_id, tier, build):
    adb("shell", "am", "force-stop", PACKAGE)
    assert "Success" in adb("shell", "pm", "clear", PACKAGE), "Cannot reset isolated benchmark data"
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", ACTIVITY,
        "--es", "benchmark_id", run_id, "--es", "quality", QUALITY[tier],
        "--es", "source_sha", build["sourceSha"], "--es", "apk_sha", build["apkSha256"])
    wait_ready(run_id)
    return foreground()


def warmup(run_id, pid):
    steps = []
    # Native Up/Down exits touch mode and returns to Home's real default control.
    for index, code in enumerate((19, 20)):
        steps.append(key(run_id, index, code, "HOME"))
    for offset, (code, route, label) in enumerate(CYCLE, start=2):
        steps.append(key(run_id, offset, code, route, "warmup-" + label))
    assert foreground() == pid, "App process changed during warmup"
    return steps


def run_one(out, run_id, tier, build):
    directory = out / run_id
    directory.mkdir(parents=True, exist_ok=True)
    result = {"runId": run_id, "tier": tier, "sourceSha": build["sourceSha"],
              "apkSha256": build["apkSha256"], "screenrecordConcurrent": False, "result": "blocked"}
    steps = []
    started = False
    stopped = False
    try:
        pid = launch(run_id, tier, build)
        gfx = adb("shell", "dumpsys", "gfxinfo", PACKAGE)
        (directory / "gfxinfo-before.txt").write_text(gfx)
        pipeline = [line.strip() for line in gfx.splitlines() if "Pipeline=" in line or "Pipeline:" in line]
        result["appHwuiPipeline"] = pipeline
        assert pipeline, "App HWUI pipeline could not be verified"
        # Keep the first ready receipt even if policy denies the desired tier.
        initial = status(run_id)
        (directory / "initial-status.json").write_text(json.dumps(initial, indent=2))
        validate_tier(initial, tier)
        steps = warmup(run_id, pid)
        start = status(run_id)
        validate_tier(start, tier)
        time.sleep(1)  # No startup/warmup animations in the measured phase.
        control("benchmark_start")
        wait_text("started " + run_id)
        started = True
        for index, (code, route, label) in enumerate(CYCLE * 2, start=len(steps)):
            if index % 5 == 0:
                assert foreground() == pid, "App process changed during measurement"
            steps.append(key(run_id, index, code, route, label))
        assert foreground() == pid
        time.sleep(.8)  # Last active window and FrameMetrics callbacks fully drain.
        control("benchmark_stop")
        wait_text("complete " + run_id)
        stopped = True
        device_file = f"/sdcard/Android/data/{PACKAGE}/files/glass-performance/{run_id}.json"
        adb("pull", device_file, str(directory / "frames.json"))
        data = json.loads((directory / "frames.json").read_text())
        assert not data["debuggable"] and data["apkSha256"] == build["apkSha256"]
        assert data["sourceSha"] == build["sourceSha"] and not data["screenrecordConcurrent"]
        validate_tier(data["end"], tier)
        assert not data["policyViolations"], "Production policy changed during input windows"
        assert len(data["windows"]) == len(CYCLE) * 2
        result["result"] = "collected"
    except Exception as error:
        result["error"] = f"{type(error).__name__}: {error}"
    finally:
        # Preserve partial raw samples when a real key/route assertion blocks the run.
        if started and not stopped:
            try:
                time.sleep(.8)
                control("benchmark_stop")
                wait_text("complete " + run_id, timeout=5)
                adb("pull", f"/sdcard/Android/data/{PACKAGE}/files/glass-performance/{run_id}.json",
                    str(directory / "frames-partial.json"))
            except Exception as error:
                result["partialExportError"] = str(error)
        (directory / "inputs.json").write_text(json.dumps(steps, indent=2))
        for name, args in (("logcat.txt", ("logcat", "-d", "-v", "threadtime")),
                           ("window.txt", ("shell", "dumpsys", "window")),
                           ("meminfo.txt", ("shell", "dumpsys", "meminfo", PACKAGE)),
                           ("gfxinfo-after.txt", ("shell", "dumpsys", "gfxinfo", PACKAGE)),
                           ("thermal.txt", ("shell", "dumpsys", "thermalservice"))):
            try:
                (directory / name).write_text(adb(*args))
            except Exception as error:
                result[name + "Error"] = str(error)
        (directory / "result.json").write_text(json.dumps(result, indent=2))
        adb("shell", "am", "force-stop", PACKAGE)
        time.sleep(2)
    print(json.dumps(result), flush=True)
    return result


def collect_environment(out, requested):
    surface = adb("shell", "dumpsys", "SurfaceFlinger")
    (out / "surfaceflinger.txt").write_text(surface)
    # Actual SurfaceFlinger GLES line is distinct from requested emulator flags.
    gles = [line.strip() for line in surface.splitlines() if re.search(r"\bGLES\s*:", line)]
    runtime = {"surfaceFlingerGles": gles, "requestedConfiguration": requested,
               "surfaceFlingerRendererVerified": bool(gles), "scope": "Cloud Android emulator only; not physical TV performance"}
    commands = {"fingerprint": ("getprop", "ro.build.fingerprint"), "hardware": ("getprop", "ro.hardware"),
                "sdk": ("getprop", "ro.build.version.sdk"), "abi": ("getprop", "ro.product.cpu.abi"),
                "wmSize": ("wm", "size"), "wmDensity": ("wm", "density"),
                "animatorScale": ("settings", "get", "global", "animator_duration_scale"),
                "heapGrowthLimit": ("getprop", "dalvik.vm.heapgrowthlimit"),
                "debugHwuiRendererProperty": ("getprop", "debug.hwui.renderer"),
                "roHwuiRendererProperty": ("getprop", "ro.hwui.renderer")}
    for name, command in commands.items():
        runtime[name] = adb("shell", *command).strip()
    (out / "runtime.json").write_text(json.dumps(runtime, indent=2))
    assert gles, "Actual GLES renderer could not be verified; requested GPU flags are not evidence"
    return runtime


def execute(args):
    build = verify_build(args.apks, args.sha)
    (args.out / "build-receipt.json").write_text(json.dumps(build, indent=2))
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb("shell", "settings", "put", "system", "user_rotation", "0")
    adb("shell", "wm", "size", "1920x1080")
    adb("shell", "wm", "density", "320")
    adb("shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed")
    adb("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "wm", "dismiss-keyguard")
    for setting in ("animator_duration_scale", "transition_animation_scale", "window_animation_scale"):
        adb("shell", "settings", "put", "global", setting, "1")
    collect_environment(args.out, args.requested_config)
    assert "Success" in adb("install", "-r", str(args.apks / APK_NAME), timeout=120)
    package_dump = adb("shell", "dumpsys", "package", PACKAGE)
    (args.out / "package.txt").write_text(package_dump)
    assert "DEBUGGABLE" not in package_dump, "Installed app is debuggable"
    installed_path = adb("shell", "pm", "path", PACKAGE).strip()
    assert installed_path.count("package:") == 1
    installed = args.out / "installed-apk-verification.apk"
    adb("pull", installed_path.removeprefix("package:"), str(installed))
    assert sha256(installed) == build["apkSha256"], "Installed APK is not build-once APK"
    installed.unlink()
    (args.out / "build-receipt.json").write_text(json.dumps(build, indent=2))
    (args.out / "setup.json").write_text(json.dumps({"sourceSha": args.sha, "status": "ready"}, indent=2))
    results = []
    for round_index, order in enumerate(ORDERS, start=1):
        for tier in order:
            run_id = f"r{round_index}-{tier.lower()}"
            results.append(run_one(args.out, run_id, tier, build))
            (args.out / "runs.json").write_text(json.dumps(results, indent=2))
    return all(r["result"] == "collected" for r in results)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apks", type=Path, default=Path("dist/glass-performance"))
    parser.add_argument("--out", type=Path, default=Path("qa/glass-performance"))
    parser.add_argument("--sha", required=True)
    parser.add_argument("--requested-config", required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "setup.json").write_text(json.dumps({"sourceSha": args.sha, "status": "starting"}, indent=2))
    (args.out / "runs.json").write_text("[]\n")
    try:
        return execute(args)
    except Exception as error:
        blocked = {"sourceSha": args.sha, "status": "blocked", "error": f"{type(error).__name__}: {error}"}
        (args.out / "setup.json").write_text(json.dumps(blocked, indent=2))
        print(json.dumps(blocked), flush=True)
        return False


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
