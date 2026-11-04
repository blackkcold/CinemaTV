#!/usr/bin/env python3
"""Verify downloaded official artifacts before extracting or executing Node."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
from positive import NODE_SHA256, base

NODE_ARCHIVE = 'node-v22.23.4-linux-x64.tar.xz'
NODE_DIRECTORY = 'node-v22.23.4-linux-x64'

def verify_node_manifest(directory):
    entries = [line.split() for line in (directory / 'SHASUMS256.txt').read_text().splitlines() if line.strip()]
    matches = [row for row in entries if len(row) == 2 and row[1] == NODE_ARCHIVE]
    if matches != [[NODE_SHA256, NODE_ARCHIVE]]:
        raise ValueError('Official Node manifest must match exact pinned digest and filename once')
    if hashlib.sha256((directory / NODE_ARCHIVE).read_bytes()).hexdigest() != NODE_SHA256:
        raise ValueError('Official Node archive bytes do not match pinned digest')

def prepare(directory):
    base.verify_archive(directory / base.ARCHIVE, directory / 'SHA256SUMS')
    verify_node_manifest(directory)
    # Both official archives are authenticated against local pins before extraction.
    with tarfile.open(directory / NODE_ARCHIVE) as archive:
        archive.extractall(directory, filter='data')
    node = directory / NODE_DIRECTORY / 'bin/node'
    if not node.is_file():
        raise ValueError('Verified Node archive has no expected executable')
    return node

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    node = prepare(args.directory)
    args.report.write_text(json.dumps({'result': 'passed', 'libreTvArchiveSha256': base.SHA256, 'nodeArchiveSha256': NODE_SHA256, 'scope': 'Pinned artifact/manifest verification before runtime extraction; not protocol acceptance'}, indent=2) + '\n')
    print('Verified official LibreTV and Node artifacts; runtime ready')

if __name__ == '__main__':
    main()
