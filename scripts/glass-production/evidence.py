#!/usr/bin/env python3
"""Exact-commit build and native screenshot receipts; never turns fallback into coverage."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

APK_NAMES = ("app-debug.apk", "app-debug-androidTest.apk")
MODES = ("ENHANCED", "BACKDROP", "BASIC")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build_receipt(directory, sha):
    assert re.fullmatch(r"[0-9a-f]{40}", sha), "Expected immutable source SHA"
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == sha
    assert not subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], text=True).strip(), "Dirty build source"
    receipt = {"schema": 1, "sourceSha": sha, "apks": {name: digest(directory / name) for name in APK_NAMES},
               "backdropLockSha256": digest(Path("backdrop/upstream-lock.json")),
               "purpose": "Offline production glass debug QA; not a release or performance certificate"}
    (directory / "build-provenance.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def verify_build(directory, sha):
    receipt = json.loads((directory / "build-provenance.json").read_text())
    assert receipt["sourceSha"] == sha, "APK source SHA differs from checked-out source"
    assert receipt["backdropLockSha256"] == digest(Path("backdrop/upstream-lock.json"))
    assert set(receipt["apks"]) == set(APK_NAMES)
    for name in APK_NAMES:
        assert receipt["apks"][name] == digest(directory / name), f"APK bytes changed: {name}"
    return receipt


def verify_captures(directory, sha, sdk, layout):
    from PIL import Image, ImageStat
    manifest = json.loads((directory / "captures.json").read_text())
    assert manifest["completed"] is True, "Instrumentation did not finish production routes"
    assert manifest["sourceSha"] == sha and manifest["sdk"] == sdk and manifest["layout"] == layout
    assert manifest["host"] == "dev.cinematv.ProductionGlassTestActivity"
    assert manifest["root"] == ("TvRootApp" if layout == "tv" else "MobileRootApp")
    captures = manifest["captures"]
    expected_names = {f"{mode.lower()}-home.png" for mode in MODES}
    for mode in (MODES if sdk >= 33 else ("BASIC",)):
        expected_names.update(f"{mode.lower()}-{route}.png" for route in ("navigation", "detail"))
    assert {c["file"] for c in captures} == expected_names, "Missing/extra route or tier evidence"
    assert len(captures) == len(expected_names), "Duplicate captures"
    assert {p.name for p in directory.glob("*.png")} == expected_names
    size, density = ((1920, 1080), 320) if layout == "tv" else ((1080, 2400), 420)
    coverage = {}
    for capture in captures:
        path = directory / capture["file"]
        assert path.parent == directory and path.name == capture["file"]
        assert digest(path) == capture["sha256"], "PNG content hash mismatch"
        assert capture["sourceSha"] == sha and capture["sdk"] == sdk
        assert (capture["widthPx"], capture["heightPx"]) == size
        assert capture["densityDpi"] == density
        assert capture["foregroundVerified"] is True and capture["nativeWindowFrameFence"] is True
        assert capture["failed"] is False and capture["route"] != "PLAYER"
        env = capture["environment"]
        assert env["api"] == sdk and env["userReducedMotion"] is True
        assert env["hardwareAccelerated"] is True
        with Image.open(path) as image:
            assert image.format == "PNG" and image.size == size
            image.load()
            assert max(ImageStat.Stat(image.convert("RGB")).stddev) > 8, "Blank/flat screenshot"
        if capture["renderer"] in ("FULL", "BLUR"):
            assert env["windowReady"] and not env["lowRam"] and env["memoryClassMb"] > 128 and not env["powerSave"]
            assert capture["draws"][capture["renderer"].lower()] > 0
        if capture["renderer"] == "BASIC":
            assert capture["draws"]["basic"] > 0
        if capture["file"].endswith("-home.png"):
            mode = capture["requested"]
            expected = "BASIC" if mode == "BASIC" or sdk < 31 else "FULL" if mode == "ENHANCED" and sdk >= 33 else "BLUR"
            coverage[mode] = {"expected": expected, "observed": capture["renderer"], "reason": capture["reason"]}
            # Keep fallback evidence, but do not claim Full/Blur coverage when production
            # policy correctly chooses Basic on an underpowered or constrained emulator.
            assert capture["renderer"] == expected, f"Coverage blocked, production policy was NOT bypassed: {coverage}"
            assert capture["renderer"] == capture["policyMode"]
            assert capture["decodedScene"].startswith("android.resource://dev.cinematv/")
        if layout == "phone" and capture["file"].endswith("-detail.png"):
            assert capture["renderer"] == "BASIC", "Phone detail must not reuse Home's local recording"
            assert capture["draws"]["full"] == capture["draws"]["blur"] == 0
    homes = {c["requested"]: c for c in captures if c["file"].endswith("-home.png")}
    if sdk >= 33:
        assert len({c["sha256"] for c in homes.values()}) == 3, "Stale/identical native Home pixels across Full, Blur and Basic"
    elif sdk >= 31:
        assert homes["BACKDROP"]["sha256"] != homes["BASIC"]["sha256"], "Blur and Basic native pixels are identical"
    if layout == "tv":
        trace = (directory / "focus-trace.txt").read_text()
        assert "route=MOVIES" in trace and "route=DETAIL" in trace and "Back" in trace
    return {"result": "passed", "sourceSha": sha, "sdk": sdk, "layout": layout, "captures": len(captures), "coverage": coverage,
            "scope": "Native production UI and input correctness on emulator; no physical GPU performance claim"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("build", "verify-build", "captures"))
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--sdk", type=int)
    parser.add_argument("--layout", choices=("tv", "phone"))
    args = parser.parse_args()
    if args.command == "build":
        result = build_receipt(args.directory, args.sha)
    elif args.command == "verify-build":
        result = verify_build(args.directory, args.sha)
    else:
        result = verify_captures(args.directory, args.sha, args.sdk, args.layout)
        (args.directory / "validated.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
