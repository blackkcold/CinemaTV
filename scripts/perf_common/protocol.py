"""Host-side validity and summary rules for the native TV performance protocol.

A run is evidence only when its identity, scene, purpose, input receipt, applied state and
responding frame are all complete. These rules never turn an emulator measurement into a
product-FPS claim.
"""
from dataclasses import dataclass, field

PROTOCOL_VERSION = 4
DEFAULT_ACTIVE_WINDOW_NS = 450_000_000
PURPOSES = ("input-causality", "tier-direct")
MIN_PAIRED_INPUTS = 1


@dataclass
class Summary:
    valid: bool
    errors: list = field(default_factory=list)
    frames: int = 0
    raw_frames: int = 0
    duplicate_frames: int = 0
    jank_ratio: float = 0.0
    p50_ns: float = 0.0
    p95_ns: float = 0.0
    p99_ns: float = 0.0
    first_draw_frames: int = 0
    paired_inputs: int = 0
    unpaired_inputs: int = 0
    latency_p50_ns: float = 0.0
    latency_p95_ns: float = 0.0
    latency_p99_ns: float = 0.0
    callback_drops: int = 0
    truncated: bool = False
    empty_windows: int = 0

    def as_dict(self):
        return dict(self.__dict__)


def _frame_identity(frame):
    return (frame.get("vsyncNs"), frame.get("intendedVsyncNs"))


def deduplicate_frames(frames):
    seen = set()
    unique = []
    duplicates = 0
    for frame in frames:
        identity = _frame_identity(frame)
        if identity in seen:
            duplicates += 1
            continue
        seen.add(identity)
        unique.append(frame)
    return unique, duplicates


def _percentile(values, fraction):
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(0, min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1)))))
    return float(ordered[rank])


def validate_run(run):
    """Return validity errors. Empty means the run may enter a comparison."""
    errors = []
    if not isinstance(run, dict):
        return ["not-an-object"]
    if run.get("schema") != PROTOCOL_VERSION:
        errors.append("protocol-version")
    for key in ("runId", "sourceSha", "apkSha256", "buildType", "device", "renderer"):
        if not run.get(key):
            errors.append("missing:" + key)
    purpose = run.get("purpose")
    if purpose not in PURPOSES:
        errors.append("missing:purpose")
    if run.get("sourceReady") is not True:
        errors.append("source-not-ready")
    if run.get("sceneReady") is False:
        errors.append("scene-not-ready")
    if run.get("metricCallbackDrops", 0) > 0:
        errors.append("callback-drops")
    if run.get("truncated") is True:
        errors.append("capacity-truncated")

    if purpose == "tier-direct":
        for key in ("requestedTier", "actualTier"):
            if not run.get(key):
                errors.append("missing:" + key)
        requested, actual = run.get("requestedTier"), run.get("actualTier")
        if requested and actual and requested != actual:
            errors.append("tier-mismatch")

    windows = run.get("windows") or []
    if not windows:
        errors.append("no-input-windows")
    spans = sorted(
        (window.get("startNs"), window.get("endNs"))
        for window in windows
        if window.get("startNs") is not None and window.get("endNs") is not None
    )
    for (_, first_end), (second_start, _) in zip(spans, spans[1:]):
        if second_start < first_end:
            errors.append("window-overlap")
            break

    frames, _ = deduplicate_frames(run.get("frames", []))
    if not frames:
        errors.append("no-frames")
    vsyncs = {frame.get("vsyncNs") for frame in frames}
    active_ns = run.get("activeWindowNs") or DEFAULT_ACTIVE_WINDOW_NS

    claimed = set()
    paired = 0
    for item in run.get("inputs", []):
        received = item.get("receivedNs")
        applied = item.get("stateAppliedNs")
        response = item.get("responseDrawNs")
        if received is None:
            errors.append("missing:receivedNs")
            continue
        # A press the UI never consumed is not evidence of a frame, only of the harness.
        if item.get("handled") is False:
            continue
        if applied is None:
            errors.append("response-no-state")
            continue
        if response is None:
            # Handled but nothing was drawn in this window: a no-op press or a lost frame.
            # It is counted as unpaired, never back-filled, and the lane requires a minimum.
            continue
        if response not in vsyncs:
            errors.append("response-not-a-frame")
            continue
        if response < applied:
            errors.append("response-before-state")
        if not (received <= response <= received + active_ns):
            errors.append("response-outside-window")
        if response in claimed:
            errors.append("response-shared-frame")
        claimed.add(response)
        paired += 1
    if paired < MIN_PAIRED_INPUTS:
        errors.append("not-enough-paired-inputs")
    return errors


def summarize(run, refresh_hz=None):
    all_frames, duplicates = deduplicate_frames(run.get("frames", []))
    frames = [frame for frame in all_frames if frame.get("firstDraw") is not True] or all_frames
    hz = refresh_hz or run.get("refreshHz") or 60.0
    budget_ns = 1e9 / float(hz)
    durations = []
    jank = 0
    for frame in frames:
        total = frame.get("totalNs")
        if total is None:
            continue
        durations.append(float(total))
        deadline = frame.get("deadlineNs") or budget_ns
        # Keep overrun signed; never clamp it to zero before reporting.
        if float(total) > float(deadline):
            jank += 1
    inputs = run.get("inputs", [])
    latencies = [float(item["latencyNs"]) for item in inputs if item.get("latencyNs") is not None]
    paired = sum(1 for item in inputs if item.get("responseOutcome") == "paired")
    windows = run.get("windows", [])
    empty_windows = sum(1 for window in windows
                        if window.get("startNs") is None or window.get("endNs") is None
                        or window.get("endNs") <= window.get("startNs"))
    summary = Summary(
        valid=not validate_run(run),
        errors=validate_run(run),
        frames=len(durations),
        raw_frames=len(all_frames),
        duplicate_frames=duplicates,
        jank_ratio=(jank / len(durations)) if durations else 0.0,
        p50_ns=_percentile(durations, .50),
        p95_ns=_percentile(durations, .95),
        p99_ns=_percentile(durations, .99),
        first_draw_frames=sum(1 for frame in all_frames if frame.get("firstDraw") is True),
        paired_inputs=paired,
        unpaired_inputs=len(inputs) - paired,
        latency_p50_ns=_percentile(latencies, .50),
        latency_p95_ns=_percentile(latencies, .95),
        latency_p99_ns=_percentile(latencies, .99),
        callback_drops=int(run.get("metricCallbackDrops", 0)),
        truncated=bool(run.get("truncated", False)),
        empty_windows=empty_windows,
    )
    return summary
