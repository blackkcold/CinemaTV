#!/usr/bin/env python3
"""Run current native contracts on one immutable APK bundle, without Gradle."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

from manifest import ROOT, digest, require, write
sys.path.insert(0, str(ROOT / 'scripts/hybrid'))
from artifacts import verify_device_bundle
from phone import verify_results

ACCOUNT = ','.join('dev.cinematv.' + name for name in (
    'DoubanAccountUiTest', 'DoubanDisabledLoginActivityTest', 'TvDoubanAccountUiTest',
    'data.DoubanDeviceSecurityTest', 'data.DoubanDeveloperOptionsTest'))
OFFICIAL = 'dev.cinematv.OfficialLibreTvPlaybackAcceptanceTest'


def profiles():
    spec = importlib.util.spec_from_file_location('native_matrix', ROOT / 'scripts/qa-device-matrix.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    original = {item['name']: item for item in module.DEVICES}
    lanes = {}
    for lane, old in (('native-phone', 'phone'), ('native-compat26', 'compat26'),
                      ('native1080', 'tv1080'), ('native4k', 'tv4k')):
        lanes[lane] = {**original[old], 'kind': 'native', 'arch': 'x86_64', 'configuration': 'preview-debug'}
    for lane, api in (('account26', 26), ('account33', 33)):
        lanes[lane] = {'api': api, 'target': 'google_apis' if api == 26 else 'default',
                       'size': '1920x1080' if api == 26 else '1080x2400',
                       'density': 320 if api == 26 else 420, 'classes': ACCOUNT,
                       'kind': 'native', 'arch': 'x86_64', 'configuration': 'preview-debug'}
    for lane, api, size, density, target, test in (
        ('recommendation33', 33, '1080x2400', 420, 'default', 'MobileRecommendationDashboardTest'),
        ('updates-phone', 30, '1080x1920', 420, 'google_apis', 'MobileUpdateSettingsTest'),
        ('updates-tv', 33, '1920x1080', 320, 'default', 'TvUpdateSettingsTest')):
        lanes[lane] = {'api': api, 'target': target, 'size': size, 'density': density,
                       'classes': 'dev.cinematv.' + test, 'kind': 'native', 'arch': 'x86_64', 'configuration': 'preview-debug'}
    for lane, api, arch, mode, size, density in (
        ('tv26', 26, 'x86', 'native-fallback', '1920x1080', 320),
        ('tv29', 29, 'x86', 'provider-policy', '1920x1080', 320),
        ('hybrid1080', 36, 'x86_64', 'hybrid', '1920x1080', 320),
        ('hybrid4k', 36, 'x86_64', 'hybrid', '3840x2160', 640)):
        lanes[lane] = {'api': api, 'arch': arch, 'target': 'android-tv', 'mode': mode,
                       'size': size, 'density': density, 'kind': 'tv', 'configuration': 'dev-debug'}
    lanes['phone35'] = {'api': 35, 'arch': 'x86_64', 'target': 'google_apis', 'size': '1080x1920',
                         'density': 420, 'kind': 'phone', 'configuration': 'dev-debug'}
    for lane, profile in lanes.items():
        profile['official'] = lane in ('native-phone', 'native1080')
    return lanes


def mask_literals(text):
    # Preserve offsets/newlines while excluding comments and strings from declarations.
    pattern = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*'
    return re.sub(pattern, lambda match: ''.join('\n' if c == '\n' else ' ' for c in match[0]), text)


def class_methods(text, full_name):
    masked = mask_literals(text)
    package = re.search(r'^package\s+([\w.]+)', masked, re.M)
    package_name, _, short = full_name.rpartition('.')
    if not package or package[1] != package_name:
        return None
    matches = list(re.finditer(r'\bclass\s+' + re.escape(short) + r'\b', masked))
    if not matches:
        return None
    require(len(matches) == 1, 'Ambiguous test class declaration')
    start = masked.find('{', matches[0].end())
    require(start >= 0, 'Test class body missing')
    depth, end = 1, start + 1
    while end < len(masked) and depth:
        depth += (masked[end] == '{') - (masked[end] == '}'); end += 1
    require(depth == 0, 'Unbalanced test class')
    methods = re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)', masked[start:end])
    require(methods and len(methods) == len(set(methods)), 'Missing or duplicate declared test methods')
    return methods


def declared_cases(selectors, root=ROOT):
    sources = [(path, path.read_text()) for path in (root / 'app/src/androidTest').rglob('*.kt')]
    result = set()
    for selector in selectors.split(','):
        name, _, method = selector.partition('#')
        matches = [methods for _, text in sources if (methods := class_methods(text, name)) is not None]
        require(len(matches) == 1, f'Test class not uniquely declared: {name}')
        methods = matches[0]
        if method:
            require(method in methods, f'Unknown method: {selector}'); methods = [method]
        result.update(name + '#' + item for item in methods)
    require(result, 'No native tests selected')
    return result


def adb(*args, timeout=30):
    executable = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
    serial = os.getenv('ANDROID_SERIAL', 'emulator-5554')
    return subprocess.check_output([executable, '-s', serial, *args], timeout=timeout, text=True)


def ready_window(text):
    return all(value in text for value in ('mAwake=true', 'mScreenOnFully=true', 'mWindowManagerDrawComplete=true')) and 'mDisplayFrozen=true' not in text and re.search(r'mCurrentFocus=Window\{[^}]+ (?:com.google.android.apps.nexuslauncher|com.android.launcher3)/', text) is not None


def configure(profile, evidence):
    subprocess.run(['bash', 'scripts/hybrid/wait-device.sh', str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'),
                    os.getenv('ANDROID_SERIAL', 'emulator-5554'), str(evidence / 'startup.txt')], check=True, timeout=150)
    adb('shell', 'svc', 'power', 'stayon', 'true'); adb('shell', 'wm', 'dismiss-keyguard')
    for changed in (False, True):
        if changed:
            adb('shell', 'wm', 'size', profile['size']); adb('shell', 'wm', 'density', str(profile['density']))
            adb('shell', 'settings', 'put', 'secure', 'immersive_mode_confirmations', 'confirmed')
        deadline, stable = time.monotonic() + 60, 0
        while time.monotonic() < deadline:
            text = adb('shell', 'dumpsys', 'window')
            (evidence / 'setup-window.txt').write_text(text)
            require(not any(marker in text for marker in ('Application Not Responding:', 'Application Error:', 'Application at fault:')), 'Fresh device has a recorded ANR/crash')
            stable = stable + 1 if ready_window(text) else 0
            if stable >= 2: break
            time.sleep(1)
        require(stable >= 2, 'Native launcher window did not become ready')
    adb('shell', 'settings', 'put', 'system', 'screen_off_timeout', '2147483647')
    (evidence / 'device.txt').write_text('\n'.join((
        'api=' + adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
        'fingerprint=' + adb('shell', 'getprop', 'ro.build.fingerprint').strip(),
        adb('shell', 'wm', 'size'), adb('shell', 'wm', 'density'), adb('shell', 'dumpsys', 'webviewupdate'))))


def installed_digest(package, evidence):
    paths = adb('shell', 'pm', 'path', package).strip().splitlines()
    require(len(paths) == 1 and paths[0].startswith('package:/data/app/'), 'Unexpected installed package paths')
    remote = paths[0].removeprefix('package:')
    require(re.fullmatch(r'/[A-Za-z0-9_./+=~-]+', remote), 'Unexpected installed package path')
    local = evidence / (package + '-installed.apk')
    adb('pull', remote, str(local), timeout=90)
    value = digest(local); local.unlink()
    return value


def execute_instrumentation(selectors, output, device_output, budget):
    executable = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
    command = [executable, '-s', os.getenv('ANDROID_SERIAL', 'emulator-5554'), 'shell', 'am', 'instrument', '-w', '-r',
               '-e', 'class', selectors, '-e', 'additionalTestOutputDir', device_output,
               'dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner']
    started, next_report = time.monotonic(), time.monotonic() + 30
    with output.open('w') as log:
        proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        try:
            while proc.poll() is None:
                require(time.monotonic() - started < budget, 'Native instrumentation exceeded its bounded budget')
                if time.monotonic() >= next_report:
                    print(f'Native contracts executing: {int(time.monotonic() - started)}s', flush=True)
                    next_report += 30
                time.sleep(1)
            require(proc.returncode == 0, 'ADB instrumentation command failed')
        finally:
            if proc.poll() is None:
                proc.terminate()
                try: proc.wait(timeout=5)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait(timeout=5)


def run(lane, bundle, evidence):
    profile = profiles()[lane]
    require(profile['kind'] == 'native', 'Use the production host harness for hybrid lanes')
    require(not evidence.exists() or not any(evidence.iterdir()), 'Evidence directory must start empty')
    evidence.mkdir(parents=True, exist_ok=True)
    receipt = verify_device_bundle(ROOT, bundle)
    configure(profile, evidence)
    for filename in ('app-debug.apk', 'app-debug-androidTest.apk'):
        require('Success' in adb('install', '-r', '-t', str(bundle / filename), timeout=90), 'Installation failed')
    installed = {package: installed_digest(package, evidence) for package in ('dev.cinematv', 'dev.cinematv.test')}
    require(installed['dev.cinematv'] == receipt['files']['app-debug.apk']['sha256'] and
            installed['dev.cinematv.test'] == receipt['files']['app-debug-androidTest.apk']['sha256'], 'Installed APK does not match bundle')
    write(evidence / 'installed-apks.json', installed)
    output = '/sdcard/Android/media/dev.cinematv/acceptance-output'
    adb('shell', 'rm', '-rf', output); adb('shell', 'mkdir', '-p', output)
    selectors = ','.join(x for x in profile['classes'].split(',') if x != OFFICIAL)
    expected = declared_cases(selectors)
    execute_instrumentation(selectors, evidence / 'instrumentation.txt', output, 1440 if lane == 'native4k' else 720)
    completed = verify_results((evidence / 'instrumentation.txt').read_text(), expected)
    # Screenshots are optional for non-visual safety suites, mandatory for TV visual lanes below.
    try: adb('pull', output + '/ui-evidence', str(evidence), timeout=90)
    except subprocess.CalledProcessError: pass
    if profile['official']:
        require(os.getenv('CINEMA_OFFICIAL_CREDENTIAL_FILE'), 'Official adapter acceptance wrapper was not started')
        for filename, dest in (('app-debug.apk', 'app/build/outputs/apk/debug'),
                               ('app-debug-androidTest.apk', 'app/build/outputs/apk/androidTest/debug')):
            Path(dest).mkdir(parents=True, exist_ok=True); shutil.copyfile(bundle / filename, Path(dest) / filename)
        subprocess.run([sys.executable, 'scripts/libretv-contracts/run_android_test.py'], check=True, timeout=420)
        official_log = Path('qa/official-android-instrumentation.txt')
        shutil.copyfile(official_log, evidence / 'official-instrumentation.txt')
        completed += verify_results(official_log.read_text(), declared_cases(OFFICIAL))
    if lane in ('native1080', 'native4k'):
        subprocess.run([sys.executable, 'scripts/validate-ui-evidence.py', '--directory', str(evidence / 'ui-evidence'),
                        '--size', profile['size'], '--density', str(profile['density'])], check=True, timeout=120)
        baseline = 'tv4k' if lane == 'native4k' else 'tv1080'
        subprocess.run([sys.executable, 'scripts/compare-ui-goldens.py', '--baseline', 'design/ui/goldens/' + baseline,
                        '--candidate', str(evidence / 'ui-evidence'), '--output', str(evidence / 'native-golden')], check=True, timeout=120)
    require(len(completed) == len(set(completed)), 'Duplicate native execution')
    receipt.update(status='passed-native-device-tests', completedTests=completed, lane=lane,
                   installedApks=installed, physicalTvAcceptance=False, signedArtifactAcceptance=False)
    write(evidence / 'native-receipt.json', receipt)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('describe', 'run'))
    parser.add_argument('--lane', required=True, choices=sorted(profiles()))
    parser.add_argument('--bundle', type=Path); parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    if args.command == 'describe':
        profile = profiles()[args.lane]
        print(json.dumps(profile, indent=2))
        if os.getenv('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
                for key, value in profile.items(): stream.write(f'{key}={str(value).lower() if isinstance(value, bool) else value}\n')
    else:
        require(args.bundle and args.evidence, 'Bundle and evidence are required')
        run(args.lane, args.bundle, args.evidence)


if __name__ == '__main__':
    try: main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        sys.exit(f'Native contract gate: {error}')
