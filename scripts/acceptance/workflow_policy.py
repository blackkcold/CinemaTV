#!/usr/bin/env python3
"""Check or prepare a minimal, comment-preserving emulator mutex migration."""
import argparse
import difflib
import json
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]
LOCK = {'group': 'cinematv-emulator-exclusive', 'cancel-in-progress': False, 'queue': 'max'}


def mapping(node):
    return {key.value: value for key, value in node.value}


def device_job(job):
    return any('reactivecircus/android-emulator-runner@' in str(step.get('uses', '')) or
               'scripts/hybrid/run-tv-emulator.sh' in str(step.get('run', ''))
               for step in job.get('steps', []))


def affected_workflows(documents):
    affected = {name for name, doc in documents.items() if any(device_job(job) for job in doc.get('jobs', {}).values())}
    changed = True
    while changed:
        changed = False
        for name, doc in documents.items():
            references = {job.get('uses', '').removeprefix('./.github/workflows/') for job in doc.get('jobs', {}).values()}
            if name not in affected and references & affected:
                affected.add(name); changed = True
    return affected


def migrate(text, manages_devices):
    document = yaml.safe_load(text)
    node = yaml.compose(text)
    root = mapping(node)
    lines = text.splitlines(keepends=True)
    edits = []
    if manages_devices and 'concurrency' in root:
        policy = document['concurrency']
        if isinstance(policy, str):
            raise ValueError('Expand scalar workflow concurrency explicitly before migration')
        cn = mapping(root['concurrency'])
        if policy.get('group') == LOCK['group']:
            raise ValueError('Outer workflow may not hold its device job mutex')
        if policy.get('cancel-in-progress') is not False:
            if 'cancel-in-progress' in cn:
                line = cn['cancel-in-progress'].start_mark.line
                edits.append((line, line + 1, '  cancel-in-progress: false\n'))
            else:
                edits.append((root['concurrency'].start_mark.line, root['concurrency'].start_mark.line, '  cancel-in-progress: false\n'))
        if policy.get('queue') != 'max':
            if 'queue' in cn:
                line = cn['queue'].start_mark.line; edits.append((line, line + 1, '  queue: max\n'))
            else:
                line = root['concurrency'].start_mark.line; edits.append((line, line, '  queue: max\n'))
    job_nodes = mapping(root['jobs'])
    for name, job in document.get('jobs', {}).items():
        jnode = job_nodes[name]
        keys = mapping(jnode)
        if not device_job(job):
            if isinstance(job.get('concurrency'), dict) and job['concurrency'].get('group') == LOCK['group']:
                raise ValueError(f'Non-device caller {name} would hold the device lock')
            continue
        if job.get('concurrency') != LOCK:
            replacement = '    concurrency:\n      group: cinematv-emulator-exclusive\n      cancel-in-progress: false\n      queue: max\n'
            if 'concurrency' in keys:
                keynode = next(key for key, value in jnode.value if key.value == 'concurrency')
                edits.append((keynode.start_mark.line, keys['concurrency'].end_mark.line, replacement))
            else:
                line = jnode.start_mark.line; edits.append((line, line, replacement))
        if 'matrix' in job.get('strategy', {}) and job['strategy'].get('max-parallel') != 1:
            sn = mapping(keys['strategy'])
            if 'max-parallel' in sn:
                line = sn['max-parallel'].start_mark.line; edits.append((line, line + 1, '      max-parallel: 1\n'))
            else:
                line = keys['strategy'].start_mark.line; edits.append((line, line, '      max-parallel: 1\n'))
    for start, end, replacement in sorted(edits, key=lambda item: (item[0], item[1]), reverse=True):
        lines[start:end] = [replacement]
    result = ''.join(lines)
    yaml.safe_load(result)
    return result


def prepare(root, output=None):
    paths = sorted((root / '.github/workflows').glob('*.yml'))
    texts = {path.name: path.read_text() for path in paths}
    affected = affected_workflows({name: yaml.safe_load(text) for name, text in texts.items()})
    changed = []
    for path in paths:
        old = texts[path.name]
        new = migrate(old, path.name in affected)
        if new == old: continue
        relative = path.relative_to(root)
        changed.append(str(relative))
        if output:
            destination = output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(new)
            (output / (path.name + '.diff')).write_text(''.join(difflib.unified_diff(old.splitlines(True), new.splitlines(True), fromfile=str(relative), tofile=str(relative))))
    return changed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare', type=Path)
    args = parser.parse_args()
    changed = prepare(ROOT, args.prepare)
    print(json.dumps({'workflowsRequiringMigration': changed, 'mode': 'prepare-only' if args.prepare else 'check'}, indent=2))
    if changed and not args.prepare:
        raise SystemExit('Emulator policies are not fully migrated')


if __name__ == '__main__': main()
