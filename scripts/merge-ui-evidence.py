#!/usr/bin/env python3
"""Merge all UTP class evidence, without silently replacing any differing file."""
import argparse
import hashlib
from pathlib import Path
import shutil


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.digest()


def merge(source, destination):
    source, destination = Path(source), Path(destination)
    files = {}
    roots = sorted(source.rglob('ui-evidence')) if source.exists() else []
    for root in roots:
        if root.is_symlink():
            raise ValueError(f'Symlink evidence root: {root}')
        if not root.is_dir() or any(parent in roots for parent in root.parents):
            continue
        for path in sorted(root.rglob('*')):
            if path.is_symlink():
                raise ValueError(f'Symlink evidence: {path}')
            if path.is_dir():
                continue
            if not path.is_file():
                raise ValueError(f'Not a regular evidence file: {path}')
            relative = path.relative_to(root)
            fingerprint = digest(path)
            previous = files.get(relative)
            if previous and previous[1] != fingerprint:
                raise ValueError(f'Conflicting evidence: {relative} ({previous[0]} vs {path})')
            files.setdefault(relative, (path, fingerprint))
    # Validate the complete plan before writing anything. Existing identical
    # evidence is allowed; conflicts, symlinks and file/directory clashes fail.
    for relative, (_, fingerprint) in files.items():
        target = destination / relative
        for parent in target.parents:
            if parent.is_symlink() or (parent.exists() and not parent.is_dir()):
                raise ValueError(f'Invalid evidence parent: {parent}')
        if any(parent in files for parent in relative.parents):
            raise ValueError(f'Evidence file/directory conflict: {relative}')
        if target.is_symlink() or (target.exists() and (not target.is_file() or digest(target) != fingerprint)):
            raise ValueError(f'Conflicting destination evidence: {target}')
    for relative, (path, _) in sorted(files.items()):
        target = destination / relative
        if target.exists():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        with path.open('rb') as original, target.open('xb') as output:
            shutil.copyfileobj(original, output, length=1024 * 1024)
    return len(files)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--destination', required=True, type=Path)
    args = parser.parse_args()
    try:
        print(f'Merged {merge(args.source, args.destination)} unique UI evidence files')
    except (OSError, ValueError) as error:
        parser.exit(1, f'Evidence export failed: {error}\n')
