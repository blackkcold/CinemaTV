#!/usr/bin/env python3
"""Compare original fixture UI at a common dp-equivalent raster size."""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageStat

SIZE = (960, 540)


def normalized(path, masks):
    with Image.open(path) as image:
        result = image.convert("RGB").resize(SIZE, Image.Resampling.LANCZOS)
    for rectangle in masks:
        ImageDraw.Draw(result).rectangle(rectangle, fill="black")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mean-limit", type=float, default=0.020)
    parser.add_argument("--changed-limit", type=float, default=0.08)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((args.baseline / "manifest.json").read_text())
    screens = manifest["screens"]
    if not screens:
        raise SystemExit("No reviewed golden images found")
    report = []
    for screen in screens:
        baseline = args.baseline / screen["file"]
        candidate = args.candidate / baseline.name
        if not baseline.is_file() or not candidate.is_file():
            report.append({"screen": baseline.name, "passed": False, "reason": "Missing baseline or screenshot"})
            continue
        masks = screen.get("masks", [])
        before, after = normalized(baseline, masks), normalized(candidate, masks)
        difference = ImageChops.difference(before, after)
        mean = sum(ImageStat.Stat(difference).mean) / (3 * 255)
        changed = sum(1 for pixel in difference.getdata() if max(pixel) > 32) / (SIZE[0] * SIZE[1])
        passed = mean <= args.mean_limit and changed <= args.changed_limit
        report.append({"screen": baseline.name, "meanError": round(mean, 6), "changedRatio": round(changed, 6), "passed": passed})
        if not passed:
            difference.save(args.output / (baseline.stem + "-difference.png"))
            Image.blend(before, after, .5).save(args.output / (baseline.stem + "-overlay.png"))
    (args.output / "comparison.json").write_text(json.dumps({
        "normalizedSize": SIZE, "sourceCommit": manifest["sourceCommit"],
        "meanLimit": args.mean_limit, "changedLimit": args.changed_limit, "screens": report,
    }, indent=2))
    for row in report:
        print(json.dumps(row))
    if not all(row["passed"] for row in report):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
