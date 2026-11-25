#!/usr/bin/env python3
"""Analyze one native TV run. Validity is a gate; emulator timing is not a product FPS."""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from perf_common.protocol import summarize  # noqa: E402


def analyze(path, refresh_hz=None):
    run = json.loads(Path(path).read_text())
    return summarize(run, refresh_hz=refresh_hz)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run")
    parser.add_argument("--refresh-hz", type=float, default=None)
    parser.add_argument("--output", default=None)
    args = parser.parse_args()
    summary = analyze(args.run, args.refresh_hz)
    text = json.dumps(summary.as_dict(), indent=2, sort_keys=True) + "\n"
    if args.output:
        Path(args.output).write_text(text)
    else:
        sys.stdout.write(text)
    return 0 if summary.valid else 1


if __name__ == "__main__":
    sys.exit(main())
