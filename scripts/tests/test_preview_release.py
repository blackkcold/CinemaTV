import copy
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('promotion', ROOT / 'scripts/preview-release.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class PromotionTests(unittest.TestCase):
    def setUp(self):
        self.classes = {d: ['required.Test'] for d in ('phone', 'tv1080', 'tv4k', 'tvNative', 'tv29', 'tv31', 'tv36', 'compat26')}
        self.run = dict(repository={'full_name': p.REPO}, head_repository={'full_name': p.REPO},
                        path='.github/workflows/android.yml', name='Android TV APK', status='completed',
                        updated_at=p.datetime.now(p.timezone.utc).isoformat(), conclusion='success', event='pull_request', run_attempt=1, id=42, head_sha='a' * 40)
        self.jobs = [dict(name=n, status='completed', conclusion='success') for n in
                     ['apk'] + [f'ui-qa / UI / {d}' for d in self.classes]]
        self.receipts = {d: dict(schema=1, repository=p.REPO, source_repository=p.REPO,
                                run_id='42', attempt='1', source_head='a' * 40,
                                checkout_sha='b' * 40, checkout_tree='c' * 40,
                                version='0.6.0-preview', device=d, classes=self.classes.get(d, []),
                                apk_sha256='d' * 64, apk_payload_sha256='e' * 64)
                         for d in ['apk', *self.classes]}

    def validate(self, **overrides):
        args = dict(run=self.run, jobs=self.jobs, tree='c' * 40, requested='0.6.0-preview',
                    receipts=self.receipts, classes=self.classes, payload='e' * 64, sha='d' * 64)
        args.update(overrides)
        p.validate(**args)

    def test_full_same_tree_passes(self):
        self.validate()

    def test_run_fail_closed(self):
        for field, bad in [('conclusion', 'failure'), ('status', 'in_progress'), ('run_attempt', 2),
                           ('updated_at', '2020-01-01T00:00:00Z'), ('updated_at', '2099-01-01T00:00:00Z'), ('event', 'pull_request_target'), ('path', 'other.yml'), ('name', 'Other'),
                           ('repository', {'full_name': 'evil/repo'}), ('head_repository', {'full_name': 'evil/repo'})]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(run={**self.run, field: bad})

    def test_missing_failed_skipped_duplicate_device_jobs(self):
        for idx in range(len(self.jobs)):
            for status in ('failure', 'cancelled', 'skipped', None):
                jobs = copy.deepcopy(self.jobs)
                jobs[idx]['conclusion'] = status
                with self.subTest(index=idx, status=status), self.assertRaises(ValueError):
                    self.validate(jobs=jobs)
            with self.assertRaises(ValueError):
                self.validate(jobs=self.jobs[:idx] + self.jobs[idx+1:])
        with self.assertRaises(ValueError):
            self.validate(jobs=self.jobs + [self.jobs[0]])

    def test_receipt_fields_fail_closed(self):
        bad_fields = dict(schema=2, repository='evil/repo', source_repository='evil/repo',
                          run_id='43', attempt='2', source_head='f'*40, checkout_tree='f'*40,
                          version='0.6.1-preview', device='other', classes=[],
                          apk_sha256='bogus', apk_payload_sha256='f'*64, checkout_sha='f'*40)
        for device in self.classes:
            for field, bad in bad_fields.items():
                receipts = copy.deepcopy(self.receipts)
                receipts[device][field] = bad
                with self.subTest(device=device, field=field), self.assertRaises(ValueError):
                    self.validate(receipts=receipts)
            with self.assertRaises(ValueError):
                self.validate(receipts={k:v for k,v in self.receipts.items() if k != device})

    def test_stable_changed_tree_partial_matrix_and_changed_apk_rejected(self):
        for args in [dict(requested='0.6.0'), dict(tree='f'*40), dict(sha='f'*64), dict(classes={'phone': ['required.Test']})]:
            with self.assertRaises(ValueError):
                self.validate(**args)

    def test_apk_payload_ignores_only_signature_material(self):
        with tempfile.TemporaryDirectory() as temp:
            paths = [Path(temp)/str(i) for i in range(3)]
            for i, path in enumerate(paths):
                with zipfile.ZipFile(path, 'w') as z:
                    z.writestr('classes.dex', b'same' if i < 2 else b'different')
                    z.writestr('META-INF/CERT.RSA', str(i))
            hashes = [p.hashes(path) for path in paths]
            self.assertNotEqual(hashes[0][0], hashes[1][0])
            self.assertEqual(hashes[0][1], hashes[1][1])
            self.assertNotEqual(hashes[0][1], hashes[2][1])

    def test_zip_slip_and_symlinks_rejected(self):
        for name in ('../escape', '/tmp/escape', 'qa//x', 'qa/a/../x', './x', 'link'):
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as z:
                info = zipfile.ZipInfo(name)
                if name == 'link':
                    info.external_attr = 0o120777 << 16
                z.writestr(info, 'bad')
            data.seek(0)
            with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(data) as z, self.assertRaises(ValueError):
                p.extract(z, Path(temp))

    def test_required_junit_classes_and_method(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports = root/'app/build/outputs/androidTest-results'
            reports.mkdir(parents=True)
            report = reports/'TEST.xml'
            report.write_text('<testsuite><testcase classname="required.Test" name="one"/></testsuite>')
            p.validate_device_evidence(root, ['required.Test', 'required.Test#one'])
            for selectors in (['absent.Test'], ['required.Test#absent']):
                with self.assertRaises(ValueError):
                    p.validate_device_evidence(root, selectors)
            for child in ('skipped', 'failure', 'error'):
                report.write_text(f'<testsuite><testcase classname="required.Test" name="one"><{child}/></testcase></testsuite>')
                with self.assertRaises(ValueError):
                    p.validate_device_evidence(root, ['required.Test'])

    def test_workflow_keeps_compile_gate_and_separates_publish(self):
        workflow = (ROOT/'.github/workflows/android.yml').read_text()
        promotion = workflow.split('  promote-preview:')[1]
        self.assertIn('needs: apk', promotion)
        self.assertIn('refs/heads/main', promotion)
        self.assertIn('--publish', promotion)
        self.assertIn("(!contains(github.event.head_commit.message, '[promote-preview'))", workflow)
        self.assertIn('name: preview-build-receipt', workflow)
        self.assertIn(':app:lintDebug', workflow)

    def test_checkout_is_bound_to_source_run_head(self):
        commit = dict(sha='b'*40, tree={'sha':'c'*40}, parents=[{'sha':'base'}, {'sha':'a'*40}])
        p.validate_checkout(self.run, 'b'*40, commit, 'c'*40)
        for changed in ({**commit, 'parents': []}, {**commit, 'parents': [{'sha':'base'}, {'sha':'other'}]},
                        {**commit, 'sha':'other'}, {**commit, 'tree': {'sha':'other'}}):
            with self.assertRaises(ValueError):
                p.validate_checkout(self.run, 'b'*40, changed, 'c'*40)
        for event in ('push', 'workflow_dispatch'):
            with self.assertRaises(ValueError):
                p.validate_checkout({**self.run, 'event':event}, 'b'*40, commit, 'c'*40)
            p.validate_checkout({**self.run, 'event':event, 'head_sha':'b'*40}, 'b'*40, commit, 'c'*40)

    def test_download_requires_github_digest_and_exact_size(self):
        from unittest.mock import patch
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('receipt.json', '{}')
        raw = data.getvalue()
        artifact = dict(id=1, expired=False, size_in_bytes=len(raw), digest='sha256:'+p.hashlib.sha256(raw).hexdigest())
        def downloaded(args, stdout, check):
            stdout.write(raw)
        with tempfile.TemporaryDirectory() as temp, patch.object(p.subprocess, 'run', side_effect=downloaded):
            p.download(artifact, Path(temp)/'good')
            for changed in ({**artifact, 'digest':None}, {**artifact, 'digest':'sha256:wrong'},
                            {**artifact, 'size_in_bytes':1}, {**artifact, 'expired':True}):
                with self.assertRaises(ValueError):
                    p.download(changed, Path(temp)/'bad')

    def test_no_execution_for_foreign_or_non_main_context(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        for env in ({}, {'GITHUB_REPOSITORY':'evil/repo', 'GITHUB_EVENT_NAME':'push', 'GITHUB_REF':'refs/heads/main'},
                    {'GITHUB_REPOSITORY':p.REPO, 'GITHUB_EVENT_NAME':'pull_request', 'GITHUB_REF':'refs/heads/main'},
                    {'GITHUB_REPOSITORY':p.REPO, 'GITHUB_EVENT_NAME':'push', 'GITHUB_REF':'refs/tags/v0.6.0-preview'}):
            with patch.dict(p.os.environ, env, clear=True), patch.object(p, 'api') as api, self.assertRaises(ValueError):
                p.promote(SimpleNamespace(output='unused', publish=True))
            api.assert_not_called()

    def test_official_result_required_even_with_other_success(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            reports = root/'app/build/outputs/androidTest-results'
            reports.mkdir(parents=True)
            (reports/'TEST.xml').write_text('<testsuite><testcase classname="required.Test" name="one"/></testsuite>')
            qa = root/'qa'
            qa.mkdir()
            (qa/'official-android-result.json').write_text(p.json.dumps({'result':'failed','class':'dev.cinematv.OfficialLibreTvPlaybackAcceptanceTest'}))
            (qa/'official-lifecycle.json').write_text(p.json.dumps({'result':'passed','commandExitStatus':0,'archiveSha256':'a'}))
            (qa/'official-artifact-verification.json').write_text(p.json.dumps({'result':'passed','libreTvArchiveSha256':'a'}))
            with self.assertRaises(ValueError):
                p.validate_device_evidence(root, ['dev.cinematv.OfficialLibreTvPlaybackAcceptanceTest'])

    def test_promotion_orchestration_and_tag_collision(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        run = {**self.run, 'html_url':'https://github.com/blackkcold/CinemaTV/actions/runs/42'}
        marker = '[promote-preview run=42 version=0.6.0-preview]'
        for collision in ('none', 'tag', 'release', 'moved-main', 'raced-tag'):
            with self.subTest(collision=collision), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                notes = root/'docs/releases/v0.6.0-preview.md'
                notes.parent.mkdir(parents=True)
                notes.write_text('Reviewed preview notes')
                event = root/'event.json'
                event.write_text(p.json.dumps({'after':'f'*40,'head_commit':{'message':marker}}))
                env = dict(GITHUB_REPOSITORY=p.REPO, GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/main',
                           GITHUB_SHA='f'*40, GITHUB_EVENT_PATH=str(event))
                names = ['CinemaTV-debug-apk','preview-build-receipt']+[f'ui-evidence-{d}' for d in self.classes]
                def api(path):
                    if path == 'git/ref/heads/main':
                        return {'object':{'sha':('0' if collision == 'moved-main' else 'f')*40}}
                    if path == 'actions/runs/42':
                        return run
                    if '/jobs?' in path:
                        return {'total_count':len(self.jobs), 'jobs':self.jobs}
                    if '/artifacts?' in path:
                        return {'total_count':len(names), 'artifacts':[{'name':n} for n in names]}
                    if path.startswith('git/commits/'):
                        return {'sha':'b'*40, 'tree':{'sha':'c'*40}, 'parents':[{'sha':'base'},{'sha':'a'*40}]}
                    if path.startswith('git/matching-refs/'):
                        return [{}] if collision == 'tag' else []
                    if path.startswith('git/ref/tags/'):
                        return {'object': {'type':'commit','sha':'f'*40}}
                    raise AssertionError(path)
                def command(*args):
                    if args == ('git','rev-parse','HEAD'):
                        return 'f'*40
                    if args == ('git','rev-parse','HEAD^{tree}'):
                        return 'c'*40
                    if '--slurp' in args:
                        return p.json.dumps([[{'tag_name':'v0.6.0-preview'}]] if collision == 'release' else [[]])
                    if '--method' in args:
                        if collision == 'raced-tag':
                            raise p.subprocess.CalledProcessError(1, args)
                        return '{}'
                    raise AssertionError(args)
                def download(artifact, destination):
                    destination.mkdir(parents=True)
                    name = artifact['name']
                    if name == 'CinemaTV-debug-apk':
                        (destination/'app-debug.apk').write_bytes(b'tested apk')
                    elif name == 'preview-build-receipt':
                        (destination/'promotion-receipt.json').write_text(p.json.dumps(self.receipts['apk']))
                    else:
                        device = name.removeprefix('ui-evidence-')
                        (destination/'qa').mkdir()
                        (destination/'qa/promotion-receipt.json').write_text(p.json.dumps(self.receipts[device]))
                previous = Path.cwd()
                try:
                    p.os.chdir(root)
                    with patch.dict(p.os.environ, env, clear=True), patch.object(p,'api',side_effect=api), \
                         patch.object(p,'command',side_effect=command) as commands, patch.object(p,'version',return_value='0.6.0-preview'), \
                         patch.object(p,'matrix',return_value=self.classes), patch.object(p,'download',side_effect=download), \
                         patch.object(p,'hashes',return_value=('d'*64,'e'*64)), patch.object(p,'validate_device_evidence'), \
                         patch.object(p.subprocess,'run') as publish:
                        args = SimpleNamespace(output=str(root/'dist'), publish=True)
                        if collision == 'none':
                            p.promote(args)
                            self.assertEqual(publish.call_count, 1)
                            self.assertIn('--verify-tag', publish.call_args.args[0])
                            self.assertEqual((root/'dist/CinemaTV-v0.6.0-preview.apk').read_bytes(), b'tested apk')
                            self.assertTrue(any('--method' in c.args for c in commands.call_args_list))
                        else:
                            with self.assertRaises((ValueError, p.subprocess.CalledProcessError)):
                                p.promote(args)
                            publish.assert_not_called()
                            self.assertEqual(any('--method' in c.args for c in commands.call_args_list), collision == 'raced-tag')
                finally:
                    p.os.chdir(previous)
