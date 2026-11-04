#!/usr/bin/env python3
"""Execute the real Groovy task in an SDK-free temporary Gradle project.

Requires an installed Gradle and network for pinned official downloads. No Android
plugins, SDK, project mutation or remote writes. Failures retain their Gradle log.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def verify(gradle, report):
    lock_path = ROOT / 'scripts/fonts/noto-fonts.lock.json'
    lock = json.loads(lock_path.read_text())
    report.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='cinema-noto-gradle-') as tmp:
        project = Path(tmp)
        (project / 'scripts/fonts').mkdir(parents=True)
        shutil.copy2(lock_path, project / 'scripts/fonts/noto-fonts.lock.json')
        shutil.copy2(ROOT / 'app/noto-fonts.gradle', project / 'noto-fonts.gradle')
        (project / 'settings.gradle').write_text("rootProject.name = 'noto-task-regression'\n")
        (project / 'build.gradle').write_text("apply from: 'noto-fonts.gradle'\n")
        # Java does not inherit HTTPS_PROXY automatically. Honor a credential-free
        # environment proxy without embedding infrastructure details in the repository.
        proxy = urllib.parse.urlparse(urllib.request.getproxies().get('https', ''))
        if proxy.hostname:
            assert not (proxy.username or proxy.password), 'Authenticated proxy needs separate secure setup'
            (project / 'gradle.properties').write_text(
                f'systemProp.https.proxyHost={proxy.hostname}\n'
                f'systemProp.https.proxyPort={proxy.port or 80}\n')
        assets = project / 'build/generated/notoAssets/fonts'

        def run(name, offline=False, expected_error=None):
            args = [str(gradle), '--no-daemon', '--console=plain', '--rerun-tasks',
                    '--max-workers=2', '--gradle-user-home', str(project / '.gradle-user-home'),
                    '-p', str(project), 'prepareNotoFonts']
            if offline:
                args.append('--offline')
            completed = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, timeout=300)
            (report / f'{name}.log').write_text(completed.stdout)
            if expected_error:
                assert completed.returncode != 0, f'{name} unexpectedly succeeded'
                assert expected_error in completed.stdout, completed.stdout
            else:
                assert completed.returncode == 0, completed.stdout
            assert not list(assets.glob('*.part')), f'{name} left a partial download'
            print(f'PASS {name}', flush=True)

        def check_bytes():
            for entry in lock['fonts']:
                data = (assets / entry['name']).read_bytes()
                assert len(data) == entry['size'], entry['name']
                assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['name']

        # Exercises the actual download branch, including temporary-file interpolation.
        run('01-cold-download')
        check_bytes()
        run('02-verified-offline-cache', offline=True)
        check_bytes()
        target = assets / lock['fonts'][0]['name']
        corrupted = bytearray(target.read_bytes())
        corrupted[100] ^= 1
        target.write_bytes(corrupted)
        run('03-corrupt-offline-rejected', offline=True, expected_error='Missing or invalid pinned Noto asset')
        assert target.read_bytes() == corrupted, 'Offline rejection must not modify local bytes'
        run('04-corrupt-online-repaired')
        check_bytes()
        target.unlink()
        run('05-missing-offline-rejected', offline=True, expected_error='Missing or invalid pinned Noto asset')
        assert not target.exists(), 'Offline mode must not download a missing font'
    print(f'Five real Gradle task scenarios passed. Logs: {report}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    verify(args.gradle.resolve(), args.report.resolve())
