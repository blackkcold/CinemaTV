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
READY_ATTEMPTS = 3
READY_ATTEMPT_SECONDS = 2
BOUNDARY_SECONDS = 10


class MarkerNotObserved(RuntimeError):
    """Only an unobserved READY may be retried, never a failed reader or boundary."""


class TestLogBoundary:
    """Stream continuously so a busy device cannot rotate the START out of evidence.

    API26 supports these buffers, threadtime, and -T MM-DD HH:MM:SS.mmm. The
    timestamp only bounds initial replay. Unique markers define the checked span;
    clock jumps, startup noise, missing markers and reader exits cannot pass it.
    A bounded READY handshake verifies the read/write path before the one-shot
    START. READY retries never move the beginning of an instrumented test span.
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
        self.receipt = {"strategy": "continuous explicit-buffer logcat with READY handshake and verified START/END markers",
                        "buffers": list(BUFFERS), "clearRequested": False,
                        "clearReason": "Buffer clearing is not a prerequisite; API26 clear failed before instrumentation in CI41956171693",
                        "startMarker": self.start_marker, "endMarker": self.end_marker,
                        "readyMaxAttempts": READY_ATTEMPTS, "readyAttemptSeconds": READY_ATTEMPT_SECONDS,
                        "markerCommands": [],
                        "deliveryNote": "A successful shell log command does not prove delivery; the reader must observe the exact marker",
                        "state": "created"}

    def _record(self, state, error=None):
        self.receipt["state"] = state
        if error is not None:
            self.receipt["error"] = f"{type(error).__name__}: {error}"
            if isinstance(error, subprocess.CalledProcessError):
                self.receipt["commandOutput"] = str(error.output or "")[-8000:]
        (self.directory / "log-boundary.json").write_text(json.dumps(self.receipt, indent=2) + "\n")

    def _raw(self):
        return (self.directory / "live-test.log").read_text(errors="replace")

    def _positions(self, marker, lines=None):
        # Match the actual log tag and complete message, never a quoted marker
        # embedded in another tag's log, shell command, or exception text.
        pattern = re.compile(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+I\s+"
                             + TAG + r"\s*:\s+" + re.escape(marker) + r"\s*$")
        if lines is None:
            lines = self._raw().splitlines()
        return [index for index, line in enumerate(lines) if pattern.search(line)]

    def _check_reader(self):
        code = self.process.poll()
        if code is not None:
            raise RuntimeError(f"logcat reader exited before verified boundary (status {code}); see live-test.log")

    def _emit(self, marker, timeout=None):
        self._check_reader()
        entry = {"marker": marker, "commandState": "pending", "observation": "pending"}
        self.receipt["markerCommands"].append(entry)
        self._record(self.receipt["state"])
        try:
            options = {} if timeout is None else {"timeout": timeout}
            self.adb("shell", "log", "-p", "i", "-t", TAG, marker, **options)
            entry["commandState"] = "succeeded"
        except Exception:
            entry["commandState"] = "failed"
            raise
        finally:
            self._record(self.receipt["state"])
        return entry

    def _wait(self, entry, timeout=BOUNDARY_SECONDS):
        deadline = time.monotonic() + timeout
        try:
            while True:
                self._check_reader()
                matches = self._positions(entry["marker"])
                self._check_reader()  # Exit cannot be hidden by a matching last line.
                if len(matches) == 1:
                    entry["observation"] = "observed"
                    return
                if len(matches) > 1:
                    raise RuntimeError("Duplicate log boundary marker")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise MarkerNotObserved(f"Log boundary marker not observed within {timeout:g} seconds; see live-test.log")
                time.sleep(min(.05, remaining))
        except Exception as error:
            entry["observation"] = "not-observed" if isinstance(error, MarkerNotObserved) else "failed"
            entry["error"] = f"{type(error).__name__}: {error}"
            raise
        finally:
            self._record(self.receipt["state"])

    def _ready(self):
        self._record("reader-probing")
        for attempt in range(1, READY_ATTEMPTS + 1):
            # Each probe is distinct, including across viewports. Delayed delivery
            # of an earlier probe cannot satisfy the current attempt. The budget
            # includes the shell command, not just observation time.
            deadline = time.monotonic() + READY_ATTEMPT_SECONDS
            entry = self._emit(f"READY:{self.token}:{attempt}", timeout=READY_ATTEMPT_SECONDS)
            try:
                self._wait(entry, timeout=max(0, deadline - time.monotonic()))
                self.receipt["readyMarker"] = entry["marker"]
                self._record("reader-ready")
                return
            except MarkerNotObserved:
                # Do not retry command failures, duplicates, reader exits, START,
                # or END. No instrumented test has begun during these probes.
                self._check_reader()
        raise RuntimeError(f"Log reader READY handshake not established after {READY_ATTEMPTS} probes; see live-test.log")

    def start(self):
        try:
            if self.receipt["state"] != "created":
                raise RuntimeError("Cannot restart a test log boundary")
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
            self._ready()
            self._wait(self._emit(self.start_marker))
            self.scoped(require_end=False)
            if self._positions(self.end_marker):
                raise RuntimeError("END observed before test log boundary was started")
            self.started = True
            self._record("start-verified")
        except Exception as error:
            self._record("failed", error)
            raise

    def finish(self):
        try:
            if not self.started:
                raise RuntimeError("Cannot finish an unverified test log boundary")
            if any(entry["marker"] == self.end_marker for entry in self.receipt["markerCommands"]):
                raise RuntimeError("Cannot repeat the END of a test log boundary")
            self._wait(self._emit(self.end_marker))
            text = self.scoped(require_end=True)
            self._check_reader()
            self.completed = True
            self._record("complete")
            (self.directory / "device.log").write_text(text)
            return text
        except Exception as error:
            self._record("failed", error)
            raise

    def scoped(self, require_end):
        # Validate and slice the same snapshot even while the reader appends.
        lines = self._raw().splitlines()
        starts, ends = self._positions(self.start_marker, lines), self._positions(self.end_marker, lines)
        if len(starts) != 1 or len(ends) > 1 or (require_end and len(ends) != 1):
            raise RuntimeError("Missing or duplicate test log boundary")
        if ends and ends[0] <= starts[0]:
            raise RuntimeError("Reversed test log boundaries")
        return "\n".join(lines[starts[0]:(ends[0] + 1 if ends else len(lines))]) + "\n"

    def close(self):
        reader_error = None
        if self.process is not None:
            if self.receipt["state"] != "failed":
                try:
                    self._check_reader()
                except RuntimeError as error:
                    reader_error = error
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
            self.process = None  # Closing our own reader is safe to repeat.
        if self.output is not None:
            self.output.close()
        try:
            if reader_error is not None:
                raise reader_error
            if self.completed:
                # Revalidate the stopped stream too: late duplicates or an
                # unexpected reader exit must not leave a successful receipt.
                self.scoped(require_end=True)
            elif self.started:
                # Partial evidence is diagnostic only. Never a passed crash/ANR gate.
                (self.directory / "device-partial.log").write_text(self.scoped(require_end=False))
                if self.receipt["state"] != "failed":
                    self._record("incomplete")
        except Exception as error:
            self._record("failed", error)
            raise
