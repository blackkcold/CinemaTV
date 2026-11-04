#!/usr/bin/env python3
"""Require complete evidence from a successful TV instrumented scenario."""
import argparse
import json
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("--directory", type=Path, required=True)
parser.add_argument("--size", required=True)
parser.add_argument("--density", type=int, required=True)
args = parser.parse_args()
size = tuple(map(int, args.size.split("x")))
screens = [
    "01-home", "02-focused-rail", "03-detail", "04-restored-rail", "05-movies",
    "06-series", "07-discovery", "08-search", "09-live", "10-library",
    "11-settings", "12-effects", "13-player", "14-player-panel",
]
for screen in screens:
    with Image.open(args.directory / (screen + ".png")) as image:
        if image.size != size:
            raise SystemExit(f"{screen}: expected {size}, received {image.size}")
viewport = json.loads((args.directory / "viewport.json").read_text())
if (viewport["widthPx"], viewport["heightPx"]) != size or viewport["densityDpi"] != args.density:
    raise SystemExit("Viewport metadata does not match requested emulator configuration")
frames = json.loads((args.directory / "frames.json").read_text())
if frames["frames"] <= 0 or frames["p95Ms"] <= 0 or frames["p99Ms"] < frames["p95Ms"]:
    raise SystemExit("Rendered frame evidence is empty or invalid")
if not (args.directory / "focus-trace.txt").read_text().strip():
    raise SystemExit("Remote focus trace is empty")
print(f"Validated {len(screens)} screenshots at {args.size}, density {args.density}; {frames['frames']} measured emulator frames")
