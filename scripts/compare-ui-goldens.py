#!/usr/bin/env python3
"""Compare original fixture UI at a common dp-equivalent raster size."""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

SIZE = (960, 540)


def normalized(path):
    with Image.open(path) as image:
        return image.convert("RGB").resize(SIZE, Image.Resampling.LANCZOS)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mean-limit", type=float, default=0.020)
    parser.add_argument("--changed-limit", type=float, default=0.08)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    baselines = sorted(args.baseline.glob("*.png"))
    if not baselines:
        raise SystemExit("No reviewed golden images found")
    report = []
    for baseline in baselines:
        candidate = args.candidate / baseline.name
        if not candidate.is_file():
            report.append({"screen": baseline.name, "passed": False, "reason": "Missing screenshot"})
            continue
        before, after = normalized(baseline), normalized(candidate)
        difference = ImageChops.difference(before, after)
        mean = sum(ImageStat.Stat(difference).mean) / (3 * 255)
        changed = sum(1 for pixel in difference.getdata() if max(pixel) > 32) / (SIZE[0] * SIZE[1])
        passed = mean <= args.mean_limit and changed <= args.changed_limit
        report.append({"screen": baseline.name, "meanError": round(mean, 6), "changedRatio": round(changed, 6), "passed": passed})
        if not passed:
            difference.save(args.output / (baseline.stem + "-difference.png"))
            Image.blend(before, after, .5).save(args.output / (baseline.stem + "-overlay.png"))
    (args.output / "comparison.json").write_text(json.dumps({"normalizedSize": SIZE, "screens": report}, indent=2))
    for row in report:
        print(json.dumps(row))
    if not all(row["passed"] for row in report):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
