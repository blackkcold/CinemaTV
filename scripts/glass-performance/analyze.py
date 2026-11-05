#!/usr/bin/env python3
"""Compare renderer cost in native input windows, never infer physical-display FPS."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import statistics
from protocol import DEFAULT_PROFILE, PROFILES, EXPECTED_RUNS, QUALITY, cycle_for, validate_profile, poster_catalog, poster_origin, poster_step

MODES = ("FULL", "BLUR", "BASIC")


def percentile(values, quantile):
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * quantile
    lower, upper = math.floor(position), math.ceil(position)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def describe(values):
    return {"n": len(values), "median": percentile(values, .5), "p90": percentile(values, .9),
            "p95": percentile(values, .95), "p99": percentile(values, .99), "max": max(values) if values else None}


def frame_summary(data, profile=DEFAULT_PROFILE):
    cycle = cycle_for(profile)
    validate_profile(data, profile)
    warmup_count, measured_count = 2 + len(cycle), 2 * len(cycle)
    assert data["schema"] == 2 and data["buildType"] == "glassBenchmark" and data["debuggable"] is False
    assert data["package"] == "dev.cinematv.glassbenchmark"
    assert data["screenrecordConcurrent"] is False, "Video-encoded runs cannot enter baseline"
    assert data["root"] == "TvRootApp" and data["viewModel"] == "CinemaViewModel" and data["provider"] == "ProductionGlassProvider"
    assert data["width"] == 1920 and data["height"] == 1080 and data["densityDpi"] == 320, "Require production 1080p/320dpi layout"
    windows = sorted(data["windows"], key=lambda w: w["startNs"])
    assert len(windows) == measured_count, "Exactly the selected profile native measured inputs required"
    assert all(w["measured"] and w["endNs"] - w["startNs"] == 450_000_000 for w in windows)
    assert all(a["endNs"] <= b["startNs"] for a, b in zip(windows, windows[1:])), "Input activity windows overlap"
    assert [w["index"] for w in windows] == list(range(warmup_count, warmup_count + measured_count))
    measured = [v for v in data["inputs"] if v["measured"]]
    assert len(measured) == measured_count, "Duplicate or missing measured input receipts"
    inputs = {v["index"]: v for v in measured}
    for state in [data["start"], data["end"], *(v["after"] for v in data["inputs"])]:
        validate_profile(state, profile)
    catalog = None
    if profile == "poster-motion":
        catalog = poster_origin(data["start"])
        for state in [data["end"], *(v["after"] for v in data["inputs"])]:
            poster_catalog(state, catalog)
        poster_origin(data["end"], catalog)
        warmup = [v for v in data["inputs"] if not v["measured"]]
        assert [v["index"] for v in warmup] == list(range(warmup_count)), "Exactly one native warmup cycle plus Up/Down required"
        plan = ((19, "HOME", "prepare-up"), (20, "HOME", "prepare-down")) + cycle
        before_warmup = "HOME"
        for offset, (value, (code, route, label)) in enumerate(zip(warmup, plan)):
            assert value["key"] == code and value["routeBefore"] == before_warmup and value["after"]["route"] == route, f"Wrong warmup input/route: {label}"
            assert value["after"]["runId"] == data["runId"], "Warmup belongs to another run"
            if offset == 1:
                poster_origin(value["after"], catalog)
            elif offset >= 2:
                poster_step(value["after"], offset - 2, catalog)
            before_warmup = route
        assert all(b["startNs"] - a["startNs"] >= 700_000_000 for a, b in zip(windows, windows[1:])), "Poster native inputs must remain at least 700 ms apart"
    assert set(inputs) == {w["index"] for w in windows}, "Native input receipt is incomplete"
    before = "HOME"
    for window, (code, route, label) in zip(windows, cycle * 2):
        native = inputs[window["index"]]
        assert window["startNs"] == native["receivedNs"], "Window is not keyed to native receive time"
        assert window["key"] == native["key"] == code, f"Wrong native key for {label}"
        assert window["routeBefore"] == native["routeBefore"] == before, f"Wrong pre-input route for {label}"
        assert native["after"]["route"] == route, f"Wrong post-input route for {label}"
        if catalog is not None:
            poster_step(native["after"], window["index"] - warmup_count, catalog)
        before = route
    expected_mode = data["expectedMode"]
    assert data["requested"] == QUALITY[expected_mode], "Requested preference does not match expected render tier"
    issues = list(data["policyViolations"])
    for state in [data["start"], data["end"], *(value["after"] for value in inputs.values())]:
        assert state["runId"] == data["runId"], "Snapshot belongs to a different run"
        if not state["ready"] or not state["fontsReady"] or state["fontFailures"] or state["busy"] or not str(state["decodedScene"]).startswith("android.resource://dev.cinematv.glassbenchmark/"):
            issues.append("A native input snapshot has an unready font, decoded scene, catalog or route")
        if state["actualMode"] != expected_mode or state["failed"] or not state["foreground"] or not state["animationsEnabled"]:
            issues.append("A native input snapshot did not preserve the admitted tier, animations and foreground")
    if data["start"]["draws"][expected_mode.lower()] <= 0:
        issues.append("No initial successful recording traversal of requested material")
    draws = data["end"]["draws"]
    if draws[expected_mode.lower()] <= 0:
        issues.append("No successful requested-material recording traversal during the complete run")
    if (expected_mode != "FULL" and draws["full"]) or (expected_mode == "BASIC" and draws["blur"]):
        issues.append("Unexpected higher-cost material draws")
    if data["metricCallbackDrops"]:
        issues.append("FrameMetrics callbacks were lost; timing distribution may be biased (not dropped display frames)")
    if data["truncated"]:
        issues.append("FrameMetrics capture hit its capacity")
    frames, intervals, per_window, boundary = [], [], [], []
    seen = set()
    for window in windows:
        values = sorted([frame for frame in data["frames"] if not frame["firstDraw"] and
                         window["startNs"] <= frame["vsyncNs"] < window["endNs"]], key=lambda frame: frame["vsyncNs"])
        for frame in values:
            assert frame["vsyncNs"] not in seen, "Duplicate frame timestamp or overlapping windows"
            seen.add(frame["vsyncNs"])
        frames.extend(values)
        # A key may be handled during a frame whose VSYNC precedes its receive time.
        # Keep that adjacent candidate as separate sensitivity evidence, never silently
        # reclassify it as an input-caused frame or fold static gaps into cadence.
        candidates = [f for f in data["frames"] if not f["firstDraw"] and
                      window["startNs"] - 1e9 / data["refreshHz"] <= f["vsyncNs"] < window["startNs"]]
        if candidates:
            boundary.append(max(candidates, key=lambda f: f["vsyncNs"]))
        intervals.extend((b["vsyncNs"] - a["vsyncNs"]) / 1e6 for a, b in zip(values, values[1:]))
        native = inputs[window["index"]]
        deltas = {tier: native["after"]["draws"][tier] - native["drawsBefore"][tier] for tier in ("full", "blur", "basic")}
        # This observer is outside drawBackdrop's graphicsLayer. Inner RenderNode
        # focus/press changes need not re-enter it; zero is valid, not a missed frame.
        if any(value < 0 for value in deltas.values()):
            issues.append("Material draw counters decreased within an input phase")
        per_window.append({"index": window["index"], "key": window["key"], "routeBefore": window["routeBefore"],
                           "materialRecordingTraversalDeltaTo520msSnapshot": deltas, "renderedSamples": len(values), "totalMs": describe([f["totalNs"] / 1e6 for f in values])})
        if not values:
            issues.append(f"Input {window['index']} has no rendered-frame samples")
    detail_recordings = {tier: sum(value["materialRecordingTraversalDeltaTo520msSnapshot"][tier]
                                  for value in per_window if inputs[value["index"]]["after"]["route"] == "DETAIL")
                         for tier in ("full", "blur", "basic")}
    total = [frame["totalNs"] / 1e6 for frame in frames if frame["totalNs"] >= 0]
    gpu = [frame["gpuNs"] / 1e6 for frame in frames if frame.get("gpuNs", -1) >= 0]
    deadlines = [frame for frame in frames if frame.get("deadlineNs", -1) > 0]
    if len(total) != len(frames):
        issues.append("One or more rendered-frame duration samples are invalid")
    # Reciprocal of within-window VSYNC spacing describes APP render cadence only.
    # This is not TOTAL_DURATION inversion or measured display presentation FPS.
    cadence_hz = [1000 / interval for interval in intervals if interval > 0]
    result = {"profile": profile, "runId": data["runId"], "tier": expected_mode, "sourceSha": data["sourceSha"], "apkSha256": data["apkSha256"],
              "validForComparison": not issues, "issues": sorted(set(issues)), "nativeInputWindows": len(windows),
              "activeWindowSeconds": sum(w["endNs"] - w["startNs"] for w in windows) / 1e9,
              "renderedFrameSamples": len(frames), "excludedStartupIdleOrFirstDrawSamples": len(data["frames"]) - len(frames),
              "metricCallbackDrops": data["metricCallbackDrops"], "materialRecordingTraversals": draws,
              "detailEntryRecordingTraversalDiagnostic": detail_recordings,
              "viewport": {"widthPx": data["width"], "heightPx": data["height"], "densityDpi": data["densityDpi"]},
              "windowRenderTotalMs": describe(total), "gpuDurationMs": describe(gpu),
              "precedingBoundarySamples": len(boundary),
              "precedingBoundaryTotalMs": describe([f["totalNs"] / 1e6 for f in boundary if f["totalNs"] >= 0]),
              "withPrecedingBoundaryTotalMs": describe(total + [f["totalNs"] / 1e6 for f in boundary if f["totalNs"] >= 0]),
              "samplesPerActiveSecond": len(frames) / (len(windows) * .45),
              "appRenderIntervalMs": describe(intervals), "appRenderCadenceHz": describe(cadence_hz), "perWindow": per_window,
              "renderBudget16_67msExceededCount": sum(ms > 1000 / 60 for ms in total),
              "platformDeadlineSamples": len(deadlines),
              "platformDeadlineExceededCount": sum(f["totalNs"] > f["deadlineNs"] for f in deadlines)}
    return result


def analyze(directory, profile=DEFAULT_PROFILE):
    cycle_for(profile)
    directory.mkdir(parents=True, exist_ok=True)
    setup_issues = []
    def read(name, default):
        try:
            return json.loads((directory / name).read_text())
        except (OSError, ValueError) as error:
            setup_issues.append(f"{name}: {type(error).__name__}: {error}")
            return default
    build = read("build-receipt.json", {"sourceSha": "unverified", "apkSha256": "unverified"})
    runtime = read("runtime.json", {"surfaceFlingerGles": [], "surfaceFlingerRendererVerified": False,
                                    "requestedConfiguration": "unverified"})
    runs = read("runs.json", [])
    setup = read("setup.json", {"status": "missing"})
    if setup.get("status") != "ready":
        setup_issues.append("Setup was not completed: " + json.dumps(setup))
    if setup.get("profile") != profile:
        setup_issues.append("Setup profile is missing or differs from selected profile")
    output = []
    matrix_catalog = None
    for run in runs:
        result = dict(run)
        if run["result"] == "collected":
            try:
                data = json.loads((directory / run["runId"] / "frames.json").read_text())
                validate_profile(run, profile)
                result = frame_summary(data, profile)
                if profile == "poster-motion":
                    # The fixture gets a fresh loopback port on each launch. Its
                    # resolved entry is stable within a run, not across launches.
                    catalog = poster_origin(data["start"])[:2]
                    assert matrix_catalog is None or catalog == matrix_catalog, "Catalog identity/order changed across poster trials"
                    matrix_catalog = catalog
                assert result["runId"] == run["runId"] and result["tier"] == run["tier"], "Run identity/tier differs from planned trial"
                host = json.loads((directory / run["runId"] / "inputs.json").read_text())
                assert [step["native"] for step in host if step["native"]["measured"]] == [step for step in data["inputs"] if step["measured"]], "Host and native input receipts differ"
                if profile == "poster-motion":
                    assert [step["native"] for step in host] == data["inputs"], "Host and native poster warmup/input receipts differ"
                result["appHwuiPipeline"] = run.get("appHwuiPipeline", [])
                if not result["appHwuiPipeline"]:
                    result["validForComparison"] = False
                    result["issues"].append("App HWUI pipeline missing")
                assert result["sourceSha"] == build["sourceSha"] and result["apkSha256"] == build["apkSha256"]
            except Exception as error:
                result.update(validForComparison=False, issues=[str(error)])
        else:
            result.update(validForComparison=False, issues=[run.get("error", "Run not collected")])
        output.append(result)
    if [(run.get("runId"), run.get("tier")) for run in runs] != EXPECTED_RUNS:
        setup_issues.append("Nine unique runs in the required interleaved order were not collected")
    counts = Counter(r["tier"] for r in output if r["validForComparison"])
    complete = len(output) == 9 and all(counts[tier] == 3 for tier in MODES) and runtime["surfaceFlingerRendererVerified"] and not setup_issues
    tier_summary = {}
    for tier in MODES:
        values = [run for run in output if run["tier"] == tier and run["validForComparison"]]
        tier_summary[tier] = {"validRuns": len(values)}
        cadence = [run["appRenderCadenceHz"]["median"] for run in values if run["appRenderCadenceHz"]["median"] is not None]
        tier_summary[tier]["appRenderCadenceHzMedianAcrossRuns"] = statistics.median(cadence) if cadence else None
        if values:
            for metric in ("windowRenderTotalMs", "gpuDurationMs", "appRenderIntervalMs", "appRenderCadenceHz"):
                series = [run[metric]["p95"] for run in values if run[metric]["p95"] is not None]
                tier_summary[tier][metric + "P95AcrossRuns"] = {
                    "median": statistics.median(series) if series else None,
                    "min": min(series) if series else None, "max": max(series) if series else None}
    differences = []
    if complete:
        for metric in ("windowRenderTotalMs", "gpuDurationMs"):
            baseline = tier_summary["BASIC"][metric + "P95AcrossRuns"]["median"]
            if baseline is not None:
                for tier in ("FULL", "BLUR"):
                    value = tier_summary[tier][metric + "P95AcrossRuns"]["median"]
                    if value is not None:
                        differences.append({"tier": tier, "baseline": "BASIC", "metric": metric + "P95AcrossRunsMedian",
                                            "differenceMs": value - baseline,
                                            "ratio": value / baseline if baseline > 0 else None})
    summary = {"schema": 1, "profile": profile, "completeComparison": complete, "sourceSha": build["sourceSha"],
               "apkSha256": build["apkSha256"], "runtime": runtime, "runs": output,
               "tiers": tier_summary, "differences": differences, "setupIssues": setup_issues,
               "limitations": ["Cloud software-GPU emulator results only; no physical-device performance claim.",
                               "VSYNC_TIMESTAMP is app render cadence, not SurfaceFlinger actual display presentation.",
                               "TOTAL_DURATION reciprocal is not FPS; metricCallbackDrops is callback loss, not frame drops.",
                               "Fixed 450 ms native-input windows measure interactive cost and may truncate longer animation tails.",
                               "An input may arrive after its response frame VSYNC. The immediately preceding refresh-period candidate is reported separately as boundary sensitivity, not silently counted as idle or input-caused.",
                               "SurfaceFlinger GLES identifies the compositor; app HWUI pipeline is recorded separately from gfxinfo. Requested GPU flags are not proof of either renderer.",
                               "Three interleaved runs per tier describe this environment; not statistical device-population certification.",
                               "Fixture is original generated local imagery and metadata. No video surfaces or cross-window dialogs.",
                               "Material counters observe outer recording traversals, not GPU passes, displayed frames or FPS. Inner RenderNode animation can produce zero per-input counter delta. Initial and whole-run recording evidence is required; per-input and Detail-entry deltas are diagnostics only."]}
    (directory / "summary.json").write_text(json.dumps(summary, indent=2))
    lines = ["# Production glass performance", "", "Complete comparable matrix: " + str(complete),
             "Profile: " + profile, "Source: " + build["sourceSha"], "APK SHA-256: " + build["apkSha256"],
             "SurfaceFlinger GLES: " + "; ".join(runtime["surfaceFlingerGles"]),
             "Requested emulator configuration: " + runtime["requestedConfiguration"], ""]
    for tier, value in tier_summary.items():
        cost = value.get("windowRenderTotalMsP95AcrossRuns", {})
        lines.append(f"- {tier} app render cadence (not display FPS), median across runs (Hz): {value['appRenderCadenceHzMedianAcrossRuns']}")
        lines.append(f"- {tier}: {value['validRuns']}/3 valid runs; render total p95 across-run median/min/max (ms): {cost}")
    if not complete:
        lines += ["", "No overall tier performance conclusion: one or more runs are blocked or incomplete."]
    lines += ["- Setup: " + issue for issue in setup_issues]
    for run in output:
        for issue in run.get("issues", []):
            lines.append(f"- {run['runId']}: {issue}")
    lines += ["", "Limitations", *["- " + value for value in summary["limitations"]], ""]
    (directory / "summary.md").write_text("\n".join(lines))
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=PROFILES, default=DEFAULT_PROFILE)
    parser.add_argument("--directory", type=Path, default=Path("qa/glass-performance"))
    args = parser.parse_args()
    result = analyze(args.directory, args.profile)
    print(json.dumps({"completeComparison": result["completeComparison"], "tiers": result["tiers"]}, indent=2))
    raise SystemExit(0 if result["completeComparison"] else 1)
