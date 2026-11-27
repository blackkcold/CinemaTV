#!/usr/bin/env python3
"""Guard the packaged font set against a replaced-but-still-shipped asset.

A font replacement is not one change: the lock gains a file, the loader switches to it, and the
old file stops being named. Gradle deletes stale task outputs only when history is absent, so an
incremental build can keep packaging the retired face — 19 MB of dead weight that no test would
notice, because the app never opens it.

Run against a built APK. Exits non-zero when the archive contains a font the lock does not name.
"""
import argparse
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', required=True)
    args = parser.parse_args()

    lock = json.loads((ROOT / 'scripts/fonts/noto-fonts.lock.json').read_text())
    locked = {font['name'] for font in lock['fonts']}

    apk = Path(args.apk)
    if not apk.is_file():
        print(f'FAIL missing APK: {apk}')
        return 1

    with zipfile.ZipFile(apk) as archive:
        packaged = {
            Path(name).name: info
            for name, info in ((i.filename, i) for i in archive.infolist())
            if name.startswith('assets/fonts/') and name.lower().endswith(('.ttf', '.ttc'))
        }

    problems = []
    for name in sorted(packaged):
        if name not in locked:
            problems.append(f'unlisted font packaged: {name} ({packaged[name].file_size:,} bytes)')
        elif packaged[name].compress_type != zipfile.ZIP_STORED:
            problems.append(f'font not STORED (mmap ineligible): {name}')

    for name in sorted(locked):
        if name not in packaged:
            problems.append(f'locked font missing from APK: {name}')

    total = sum(packaged[n].file_size for n in packaged if n in locked)
    for problem in problems:
        print(f'FAIL {problem}')
    if problems:
        return 1

    print(f'PASS {len(packaged)} font(s) packaged, all STORED and named by the lock; '
          f'{total:,} bytes total')
    return 0


if __name__ == '__main__':
    sys.exit(main())
