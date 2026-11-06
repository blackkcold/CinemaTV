#!/usr/bin/env python3
"""Stage reviewed policy-generated blobs only; never commit, move refs or publish."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
from workflow_policy import ROOT, prepare

REPOSITORY = 'blackkcold/CinemaTV'
BRANCH = 'fix/hybrid-acceptance-serial-qa'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY or os.environ.get('GITHUB_REF') != 'refs/heads/' + BRANCH:
        raise SystemExit('Blob staging is restricted to the isolated acceptance branch')
    if args.output.exists(): raise SystemExit('Preserve existing staging evidence')
    args.output.mkdir(parents=True)
    changed = prepare(ROOT, args.output)
    elements = []
    for relative in changed:
        path = Path(relative)
        if path.parts[:2] != ('.github', 'workflows') or path.suffix != '.yml':
            raise SystemExit('Unexpected staged path')
        data = (args.output / path).read_bytes()
        request = urllib.request.Request(f'https://api.github.com/repos/{REPOSITORY}/git/blobs',
            data=json.dumps({'encoding': 'base64', 'content': base64.b64encode(data).decode('ascii')}).encode(),
            headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json',
                     'X-GitHub-Api-Version': '2022-11-28'}, method='POST')
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.load(response)
        expected = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if result.get('sha') != expected: raise SystemExit('Staged blob identity mismatch')
        elements.append({'path': relative, 'mode': '100644', 'type': 'blob', 'sha': expected})
    receipt = {'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
               'treeElements': elements, 'refMoved': False, 'publicationAllowed': False}
    (args.output / 'staged-tree.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__': main()
