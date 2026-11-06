#!/usr/bin/env python3
"""Full-raster, environment-scoped actual Android TV visual comparisons."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
from PIL import Image, ImageChops, ImageStat
from manifest import ROOT, digest, read, require, write
sys.path.insert(0, str(ROOT / 'scripts/hybrid'))
from artifacts import verify_device_bundle
from phone import verify_results

STATES = ('home', 'explore', 'movies', 'series', 'search', 'live', 'library', 'settings',
          'settings-scrolled', 'detail', 'rail-focus', 'home-basic')
CLASS = 'dev.cinematv.HybridVisualAcceptanceTest'
METHOD = 'currentProductionRoutesAndMaterialHaveFullResolutionCaptures'


def visual_environment(metadata, size, density):
    require(metadata.get('family') == 'hybrid-android-production' and metadata.get('activity') == 'dev.cinematv.MainActivity', 'Wrong visual presentation family')
    require(metadata.get('api') == 36 and metadata.get('syntheticContent') is True, 'Unexpected visual device or content')
    require((metadata['width'], metadata['height']) == size and metadata['densityDpi'] == density, 'Not the declared physical capture size/density')
    dom = metadata['dom']
    require(dom['reducedMotion'] == 'true' and isinstance(dom['focus'], str) and dom['focus'], 'Motion or focus state is invalid')
    require(abs(dom['width'] * dom['dpr'] - size[0]) <= 1 and abs(dom['height'] * dom['dpr'] - size[1]) <= 1, 'DOM is not the actual raster viewport')
    require(dom['overflow'] <= 1, 'Unexpected horizontal overflow')
    return {'family': metadata['family'], 'api': metadata['api'], 'provider': metadata['provider'],
            'fingerprint': metadata['fingerprint'], 'width': size[0], 'height': size[1], 'densityDpi': density,
            'cssWidth': dom['width'], 'cssHeight': dom['height'], 'dpr': dom['dpr'],
            'quality': dom['quality'], 'reducedMotion': True,
            'fontLockSha256': digest(ROOT / 'scripts/fonts/noto-fonts.lock.json')}


def image_difference(baseline, candidate, output):
    with Image.open(baseline) as left, Image.open(candidate) as right:
        require(left.size == right.size, 'Comparison images have different raster sizes')
        # Never downsample or mask the actual hybrid 4K image.
        delta = ImageChops.difference(left.convert('RGB'), right.convert('RGB'))
        means = ImageStat.Stat(delta).mean
        mean = sum(means) / (3 * 255)
        channels = delta.split()
        maximum = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2])
        histogram = maximum.histogram()
        changed = sum(histogram[33:]) / (left.width * left.height)
        delta.save(output)
    return {'meanError': mean, 'changedRatio': changed, 'meanLimit': 0.010, 'changedLimit': 0.02,
            'passed': mean <= 0.010 and changed <= 0.02, 'downsampled': False, 'masks': []}


def run(bundle, evidence, lane, capture=False):
    receipt = verify_device_bundle(ROOT, bundle)
    executed = verify_results((evidence / 'visual-instrumentation.txt').read_text(), {CLASS + '#' + METHOD})
    size, density = ((3840, 2160), 640) if lane == 'hybrid4k' else ((1920, 1080), 320)
    directory = evidence / 'visual-evidence'
    require({path.stem for path in directory.glob('*.png')} == set(STATES), 'Required actual-TV visual state is missing or unexpected')
    comparisons = []
    for name in STATES:
        metadata = read(directory / f'{name}.json')
        require(metadata['state'] == name, 'Wrong screenshot state')
        environment = visual_environment(metadata, size, density)
        image = directory / f'{name}.png'
        with Image.open(image) as decoded:
            decoded.load(); require(decoded.size == size, 'PNG is not full resolution')
        value = {'schema': 1, 'sourceCommit': receipt['sourceCommit'], 'sourceTree': receipt['sourceTree'],
                 'apkSha256': receipt['files']['app-debug.apk']['sha256'], 'state': name,
                 'environment': environment, 'sha256': digest(image),
                 'status': 'candidate-only-not-approved' if capture else 'comparison-pending'}
        write(directory / f'{name}.capture.json', value)
        if not capture:
            baseline = ROOT / 'design/ui/goldens/hybrid-android' / lane
            approval = read(baseline / f'{name}.json')
            require(approval['status'] == 'reviewed-baseline', 'Visual baseline is not reviewed')
            require(approval['environment'] == environment, 'Visual environment mismatch; not a pixel regression')
            require(digest(baseline / f'{name}.png') == approval['sha256'], 'Reviewed image hash mismatch')
            comparison = image_difference(baseline / f'{name}.png', image, directory / f'{name}.difference.png')
            comparison['state'] = name
            comparisons.append(comparison)
    write(evidence / 'android-visual-comparison.json', {'mode': 'candidate-only-not-approved' if capture else 'reviewed-comparison',
          'lane': lane, 'sourceCommit': receipt['sourceCommit'], 'comparisons': comparisons})
    require(capture or all(item['passed'] for item in comparisons), 'Actual TV pixel comparison failed; preserve differences for review')
    receipt.update(status='passed-android-visual-contracts', completedTests=executed,
                   comparisonStatus='candidate-only-not-approved' if capture else 'passed-reviewed-comparison',
                   visualStates=list(STATES), physicalTvAcceptance=False, signedArtifactAcceptance=False)
    write(evidence / 'android-visual-receipt.json', receipt)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--lane', choices=('hybrid1080', 'hybrid4k'), required=True)
    args = parser.parse_args()
    try: run(args.bundle, args.evidence, args.lane, os.getenv('CINEMA_VISUAL_CAPTURE') == '1')
    except (ValueError, OSError, KeyError) as error: sys.exit(f'Actual-TV visual gate: {error}')


if __name__ == '__main__': main()
