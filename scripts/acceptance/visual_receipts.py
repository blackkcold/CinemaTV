"""Standard-library checks binding actual-TV visual evidence to the accepted APK."""
from pathlib import Path
import struct
from manifest import ROOT, digest, read, require

ANDROID_STATES = ('home', 'explore', 'movies', 'series', 'search', 'live', 'library', 'settings',
                  'settings-scrolled', 'detail', 'rail-focus', 'home-basic')
BROWSER_STATES = ('home', 'explore', 'movies', 'series', 'search', 'live', 'library', 'settings',
                  'detail', 'detail-long', 'catalog-empty-load', 'settings-scrolled', 'home-basic')
VISUAL_CASE = 'dev.cinematv.HybridVisualAcceptanceTest#currentProductionRoutesAndMaterialHaveFullResolutionCaptures'


def png_dimensions(path):
    with Path(path).open('rb') as stream: header = stream.read(24)
    require(len(header) == 24 and header[:8] == b'\x89PNG\r\n\x1a\n' and header[12:16] == b'IHDR', 'Missing PNG raster header')
    return struct.unpack('>II', header[16:24])


def validate_android(folder, lane, identity, build, capture=False, baseline_root=None):
    require(lane in ('hybrid1080', 'hybrid4k'), 'Wrong actual-TV visual lane')
    receipt = read(folder / 'android-visual-receipt.json')
    require(receipt['sourceCommit'] == identity['commit'] and receipt['sourceTree'] == identity['tree'], 'Stale Android visual receipt')
    require(receipt['files'] == build['files'], 'Android visual suite used another APK')
    require(receipt['status'] == 'passed-android-visual-contracts' and receipt['completedTests'] == [VISUAL_CASE], 'Android visual contract did not execute')
    require(receipt['visualStates'] == list(ANDROID_STATES), 'Missing or reordered Android visual states')
    expected = 'candidate-only-not-approved' if capture else 'passed-reviewed-comparison'
    require(receipt['comparisonStatus'] == expected, 'Candidate capture cannot pass reviewed Android acceptance')
    comparison = read(folder / 'android-visual-comparison.json')
    require(comparison['sourceCommit'] == identity['commit'] and comparison['lane'] == lane, 'Wrong Android comparison source or lane')
    require(comparison['mode'] == ('candidate-only-not-approved' if capture else 'reviewed-comparison'), 'Wrong Android comparison mode')
    results = comparison['comparisons']
    if capture:
        require(results == [], 'Capture mode must not pretend to have compared a baseline')
    else:
        require([item['state'] for item in results] == list(ANDROID_STATES), 'Incomplete Android pixel comparisons')
        for item in results:
            require(item['passed'] is True and item['downsampled'] is False and item['masks'] == [], 'Android pixels were masked, rescaled or failed')
            require(item['meanLimit'] == 0.010 and item['changedLimit'] == 0.02, 'Unreviewed Android visual threshold change')
            require(0 <= item['meanError'] <= item['meanLimit'] and 0 <= item['changedRatio'] <= item['changedLimit'], 'Android comparison exceeds limits')
    directory = folder / 'visual-evidence'
    dimensions = (3840, 2160) if lane == 'hybrid4k' else (1920, 1080)
    baseline_root = baseline_root or ROOT / 'design/ui/goldens/hybrid-android'
    captures = {}
    for name in ANDROID_STATES:
        metadata = read(directory / f'{name}.capture.json')
        require(metadata['sourceCommit'] == identity['commit'] and metadata['sourceTree'] == identity['tree'], 'Stale Android visual state')
        require(metadata['apkSha256'] == build['files']['app-debug.apk']['sha256'], 'Visual state belongs to another installed APK')
        image = directory / f'{name}.png'
        require(digest(image) == metadata['sha256'] and png_dimensions(image) == dimensions, 'Android visual raster or bytes mismatch')
        env = metadata['environment']
        require(env['family'] == 'hybrid-android-production' and env['api'] == 36, 'Wrong visual presentation')
        require((env['width'], env['height']) == dimensions and env['densityDpi'] == (640 if lane == 'hybrid4k' else 320), 'Wrong visual density')
        require(env['provider'] and env['fingerprint'] and env['reducedMotion'] is True, 'Incomplete Android visual environment')
        require(abs(env['cssWidth'] * env['dpr'] - dimensions[0]) <= 1 and abs(env['cssHeight'] * env['dpr'] - dimensions[1]) <= 1, 'Upscaled screenshot is not native 4K')
        if not capture:
            baseline = baseline_root / lane
            approval = read(baseline / f'{name}.json')
            require(approval['status'] == 'reviewed-baseline' and approval['environment'] == env, 'Unreviewed or mismatched Android baseline')
            require(digest(baseline / f'{name}.png') == approval['sha256'], 'Reviewed Android baseline changed')
        captures[name] = metadata['sha256']
    return {'lane': lane, 'status': expected, 'source': identity,
            'apkSha256': build['files']['app-debug.apk']['sha256'], 'captures': captures,
            'fullRaster': list(dimensions), 'physicalDeviceCertified': False}
