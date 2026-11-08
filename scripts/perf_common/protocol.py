"""Host-side validity and summary rules for the native TV performance protocol.

A run is evidence only when its identity, scene, tier and input/response chain are
complete. These rules never turn an emulator measurement into a product-FPS claim.
"""
from dataclasses import dataclass, field

PROTOCOL_VERSION = 3


@dataclass
class Summary:
    valid: bool
    errors: list = field(default_factory=list)
    frames: int = 0
    duplicate_frames: int = 0
    jank_ratio: float = 0.0
    p95_ns: float = 0.0
    p99_ns: float = 0.0
    first_draw_frames: int = 0
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
    if run.get("schema", 0) < PROTOCOL_VERSION:
        errors.append("protocol-version")
    for key in ("runId", "sourceSha", "apkSha256", "buildType", "device", "renderer"):
        if not run.get(key):
            errors.append("missing:" + key)
    if not run.get("windows"):
        errors.append("no-input-windows")
    if run.get("sceneReady") is False:
        errors.append("scene-not-ready")
    if run.get("metricCallbackDrops", 0) > 0:
        errors.append("callback-drops")
    if run.get("truncated") is True:
        errors.append("capacity-truncated")
    requested = run.get("requestedTier")
    actual = run.get("actualTier")
    if requested and actual and requested != actual:
        errors.append("tier-mismatch")
    for item in run.get("inputs", []):
        if item.get("receivedNs") and not item.get("responseDrawNs"):
            errors.append("response-timeout")
            break
    frames, _ = deduplicate_frames(run.get("frames", []))
    if not frames:
        errors.append("no-frames")
    return errors


def summarize(run, refresh_hz=None):
    frames, duplicates = deduplicate_frames(run.get("frames", []))
    hz = refresh_hz or run.get("refreshHz") or 60.0
    budget_ns = 1e9 / float(hz)
    durations = []
    jank = 0
    first_draw = 0
    for frame in frames:
        total = frame.get("totalNs")
        if total is None:
            continue
        durations.append(float(total))
        deadline = frame.get("deadlineNs") or budget_ns
        # Keep overrun signed; never clamp it to zero before reporting.
        if float(total) > float(deadline):
            jank += 1
        if frame.get("firstDraw") is True:
            first_draw += 1
    windows = run.get("windows", [])
    empty_windows = sum(1 for window in windows
                        if window.get("startNs") is None or window.get("endNs") is None
                        or window.get("endNs") <= window.get("startNs"))
    summary = Summary(
        valid=not validate_run(run),
        errors=validate_run(run),
        frames=len(durations),
        duplicate_frames=duplicates,
        jank_ratio=(jank / len(durations)) if durations else 0.0,
        p95_ns=_percentile(durations, .95),
        p99_ns=_percentile(durations, .99),
        first_draw_frames=first_draw,
        callback_drops=int(run.get("metricCallbackDrops", 0)),
        truncated=bool(run.get("truncated", False)),
        empty_windows=empty_windows,
    )
    return summary
