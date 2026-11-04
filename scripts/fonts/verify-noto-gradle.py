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
        (project / 'build.gradle').write_text("plugins { id 'base' }\napply from: 'noto-fonts.gradle'\n")
        # Java does not inherit HTTPS_PROXY automatically. Honor a credential-free
        # environment proxy without embedding infrastructure details in the repository.
        proxy = urllib.parse.urlparse(urllib.request.getproxies().get('https', ''))
        if proxy.hostname:
            assert not (proxy.username or proxy.password), 'Authenticated proxy needs separate secure setup'
            (project / 'gradle.properties').write_text(
                f'systemProp.https.proxyHost={proxy.hostname}\n'
                f'systemProp.https.proxyPort={proxy.port or 80}\n')
        assets = project / 'build/generated/notoAssets/fonts'
        cache = project / '.cache/cinematv-noto-fonts'

        def run(name, offline=False, expected_error=None, clean=False, extra_tasks=None):
            args = [str(gradle), '--no-daemon', '--console=plain', '--rerun-tasks', '--info',
                    '--max-workers=2', '--gradle-user-home', str(project / '.gradle-user-home'),
                    '-p', str(project)]
            if clean:
                args.append('clean')
            args.extend(extra_tasks or ['prepareNotoFonts'])
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
            assert not list(assets.glob('*.part')), f'{name} left a partial generated file'
            assert not list(cache.glob('*.part')), f'{name} left a partial cached file'
            print(f'PASS {name}', flush=True)
            return completed.stdout

        def check_bytes():
            for directory in (assets, cache):
                assert sorted(x.name for x in directory.iterdir()) == sorted(x['name'] for x in lock['fonts'])
                for entry in lock['fonts']:
                    data = (directory / entry['name']).read_bytes()
                    assert len(data) == entry['size'], entry['name']
                    assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['name']

        # Exercise the real script with BasePlugin output cleanup, matching AGP lifecycle.
        run('01-cold-download')
        check_bytes()
        run('02-verified-offline-cache', offline=True)
        check_bytes()
        run('03-clean-offline-restores-from-download-cache', offline=True, clean=True)
        check_bytes()
        # Simulate Actions restoring files into a checkout with no task execution history.
        # The old output-cache implementation reproducibly deletes these valid output files.
        shutil.rmtree(project / '.gradle')
        output = run('04-fresh-history-offline-restores-cache', offline=True)
        assert 'Deleting stale output file:' in output, output
        assert 'Reused verified Noto download cache:' in output, output
        check_bytes()
        # Removing both generated outputs and Gradle project metadata must be safe offline.
        shutil.rmtree(project / 'build')
        shutil.rmtree(project / '.gradle')
        run('05-clean-checkout-with-only-download-cache', offline=True)
        check_bytes()
        target = assets / lock['fonts'][0]['name']
        cached = cache / target.name
        corrupted = bytearray(target.read_bytes())
        corrupted[100] ^= 1
        target.write_bytes(corrupted)
        run('06-corrupt-output-repaired-offline', offline=True)
        check_bytes()
        target.write_bytes(corrupted)
        cached.write_bytes(corrupted)
        run('07-corrupt-both-offline-rejected', offline=True, expected_error='Missing or invalid pinned Noto asset')
        assert cached.read_bytes() == corrupted, 'Offline rejection must not mutate invalid cached bytes'
        run('08-corrupt-cache-online-repaired')
        check_bytes()
        target.unlink()
        cached.unlink()
        run('09-missing-both-offline-rejected', offline=True, expected_error='Missing or invalid pinned Noto asset')
        assert not target.exists() and not cached.exists(), 'Offline mode must not download missing fonts'
        # One verified generated file is sufficient to seed/migrate the durable cache.
        # Reuse a different, intact font and remove its cached copy only.
        other = assets / lock['fonts'][1]['name']
        other_cache = cache / other.name
        other_cache.unlink()
        # Restrict the fixture lock to that genuine locked entry; no synthetic font bytes.
        (project / 'scripts/fonts/noto-fonts.lock.json').write_text(json.dumps({'schema': 1, 'fonts': [lock['fonts'][1]]}))
        run('10-verified-generated-file-seeds-cache-offline', offline=True)
        assert hashlib.sha256(other_cache.read_bytes()).hexdigest() == lock['fonts'][1]['sha256']
    print(f'Ten real Gradle task scenarios passed. Logs: {report}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    verify(args.gradle.resolve(), args.report.resolve())
