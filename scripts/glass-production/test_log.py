#!/usr/bin/env python3
"""Explicit instrumented-test log window, without requiring privileged buffer clearing."""
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

BUFFERS = ("main", "system", "crash")
TAG = "CinemaTVGlassBoundary"


class TestLogBoundary:
    """Stream continuously so a busy device cannot rotate the START out of evidence.

    API26 supports these buffers, threadtime, and -T MM-DD HH:MM:SS.mmm. The
    timestamp only bounds initial replay. Unique markers define the checked span;
    clock jumps, startup noise, missing markers and reader exits cannot pass it.
    """
    def __init__(self, directory, adb):
        self.directory = Path(directory)
        self.adb = adb
        self.token = uuid.uuid4().hex
        self.start_marker = "START:" + self.token
        self.end_marker = "END:" + self.token
        self.process = None
        self.output = None
        self.started = False
        self.completed = False
        self.receipt = {"strategy": "continuous explicit-buffer logcat with verified START/END markers",
                        "buffers": list(BUFFERS), "clearRequested": False,
                        "clearReason": "Buffer clearing is not a prerequisite; API26 clear failed before instrumentation in CI41956171693",
                        "startMarker": self.start_marker, "endMarker": self.end_marker, "state": "created"}

    def _record(self, state, error=None):
        self.receipt["state"] = state
        if error is not None:
            self.receipt["error"] = f"{type(error).__name__}: {error}"
            if isinstance(error, subprocess.CalledProcessError):
                self.receipt["commandOutput"] = str(error.output or "")[-8000:]
        (self.directory / "log-boundary.json").write_text(json.dumps(self.receipt, indent=2) + "\n")

    def _raw(self):
        return (self.directory / "live-test.log").read_text(errors="replace")

    def _positions(self, marker):
        # Match the actual log tag and complete message, never a quoted marker
        # embedded in another tag's log, shell command, or exception text.
        pattern = re.compile(r"\sI\s+" + TAG + r"\s*:\s+" + re.escape(marker) + r"\s*$")
        return [index for index, line in enumerate(self._raw().splitlines()) if pattern.search(line)]

    def _wait(self, marker):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            code = self.process.poll()
            if code is not None:
                raise RuntimeError(f"logcat reader exited before verified boundary (status {code}); see live-test.log")
            matches = self._positions(marker)
            if len(matches) == 1:
                return
            if len(matches) > 1:
                raise RuntimeError("Duplicate log boundary marker")
            time.sleep(.05)
        raise RuntimeError("Log boundary marker not observed within 10 seconds; see live-test.log")

    def start(self):
        try:
            # Device local time matches threadtime and does not assume host timezone
            # or require nanosecond date extensions absent on some older images.
            since = self.adb("shell", "date", "+%m-%d_%H:%M:%S").strip().replace("_", " ")
            if re.fullmatch(r"\d{2}-\d{2} \d{2}:\d{2}:\d{2}", since) is None:
                raise RuntimeError(f"Unsupported device log start time: {since!r}")
            self.receipt["requestedSince"] = since + ".000"
            self.output = (self.directory / "live-test.log").open("w")
            command = ["adb", "logcat"]
            for buffer in BUFFERS:
                command += ["-b", buffer]
            command += ["-v", "threadtime", "-T", since + ".000", "*:V"]
            self.process = subprocess.Popen(command, stdout=self.output, stderr=subprocess.STDOUT)
            self._record("reader-started")
            self.adb("shell", "log", "-p", "i", "-t", TAG, self.start_marker)
            self._wait(self.start_marker)
            self.started = True
            self._record("start-verified")
        except Exception as error:
            self._record("failed", error)
            raise

    def finish(self):
        try:
            if not self.started:
                raise RuntimeError("Cannot finish an unverified test log boundary")
            self.adb("shell", "log", "-p", "i", "-t", TAG, self.end_marker)
            self._wait(self.end_marker)
            text = self.scoped(require_end=True)
            self.completed = True
            self._record("complete")
            (self.directory / "device.log").write_text(text)
            return text
        except Exception as error:
            self._record("failed", error)
            raise

    def scoped(self, require_end):
        starts, ends = self._positions(self.start_marker), self._positions(self.end_marker)
        if len(starts) != 1 or (require_end and len(ends) != 1):
            raise RuntimeError("Missing or duplicate test log boundary")
        if ends and ends[0] <= starts[0]:
            raise RuntimeError("Reversed test log boundaries")
        lines = self._raw().splitlines()
        return "\n".join(lines[starts[0]:(ends[0] + 1 if ends else len(lines))]) + "\n"

    def close(self):
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
        if self.output is not None:
            self.output.close()
        if not self.completed and self.started:
            # Partial evidence is diagnostic only. Never a passed crash/ANR gate.
            (self.directory / "device-partial.log").write_text(self.scoped(require_end=False))
            if self.receipt["state"] != "failed":
                self._record("incomplete")
