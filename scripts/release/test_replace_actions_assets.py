import copy
import io
import zipfile
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import replace_actions_assets as r


class FakeGitHub:
    def __init__(self, files):
        self.entries = {}
        self.next_id = 1
        self.writes = []
        self.fail_name = None
        self.head = 'a' * 40
        self.body = 'Existing notes'
        for name, data in files.items():
            self.add(name, b'old-' + data)

    def add(self, name, data):
        ident = self.next_id
        self.next_id += 1
        self.entries[ident] = {'id': ident, 'name': name, 'data': data}
        return ident

    def release(self, tag):
        return {'id': 1, 'draft': False, 'tag_name': tag, 'body': self.body,
                'assets': [dict(id=a['id'], name=a['name']) for a in self.entries.values()]}

    def tag(self, tag):
        return self.head

    def download(self, ident):
        return self.entries[ident]['data']

    def upload(self, rid, name, data):
        self.writes.append(('upload', name))
        return {'id': self.add(name, data)}

    def rename(self, ident, name):
        self.writes.append(('rename', name))
        if name == self.fail_name:
            self.fail_name = None
            raise RuntimeError('injected')
        if name in {a['name'] for a in self.entries.values()}:
            raise ValueError('collision')
        self.entries[ident]['name'] = name

    def delete(self, ident):
        self.writes.append(('delete', ident))
        del self.entries[ident]

    def notes(self, rid, body):
        self.writes.append(('notes', body))
        self.body = body


class ReplaceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.files = {f'{n}.apk': str(n).encode() for n in range(5)}
        self.files.update({'release-manifest.json': b'manifest', 'SHA256SUMS': b'sums'})
        for name, data in self.files.items():
            (self.path / name).write_bytes(data)
        self.api = FakeGitHub(self.files)
        self.old = copy.deepcopy(self.api.entries)
        self.patches = [mock.patch.object(r, 'validate'),
                        mock.patch.object(r, 'verify_artifacts', return_value={'sourceCommit': self.api.head}),
                        mock.patch.object(r, 'verify_run')]
        for patch in self.patches:
            patch.start()
            self.addCleanup(patch.stop)

    def run_replace(self, prior=None):
        r.replace(self.api, self.path, '1.8.0', self.api.head, 'b' * 40, '123',
                  r.digest(self.files['release-manifest.json']),
                  prior or r.digest(b'old-' + self.files['release-manifest.json']), self.path)

    def test_success_stages_verifies_and_preserves_notes(self):
        self.run_replace()
        self.assertEqual({a['name']: a['data'] for a in self.api.entries.values()}, self.files)
        kinds = [w[0] for w in self.api.writes]
        self.assertEqual(kinds[:7], ['upload'] * 7)
        self.assertGreater(kinds.index('delete'), kinds.index('notes'))
        self.assertIn('Existing notes', next(x[1] for x in self.api.writes if x[0] == 'notes'))

    def test_prior_guard_stops_writes(self):
        with self.assertRaises(ValueError):
            self.run_replace('0' * 64)
        self.assertEqual(self.api.writes, [])

    def test_failure_restores_old_names_without_deleting(self):
        self.api.fail_name = '2.apk'
        with self.assertRaises(RuntimeError):
            self.run_replace()
        self.assertTrue(all(self.api.entries[i]['name'] == asset['name'] for i, asset in self.old.items()))
        self.assertFalse(any(w[0] == 'delete' for w in self.api.writes))

    def test_partial_upload_retry_reuses_existing_stage(self):
        original = self.api.upload
        calls = [0]
        def partial(rid, name, data):
            calls[0] += 1
            result = original(rid, name, data)
            if calls[0] == 3:
                raise RuntimeError('response lost after upload')
            return result
        self.api.upload = partial
        with self.assertRaises(RuntimeError):
            self.run_replace()
        self.api.upload = original
        self.run_replace()
        uploaded = [name for kind, name in self.api.writes if kind == 'upload']
        self.assertEqual(len(uploaded), 7)
        self.assertEqual(len(set(uploaded)), 7)

    def test_notes_response_lost_restores_original_body_and_assets(self):
        original = self.api.notes
        calls = [0]
        def uncertain(rid, body):
            original(rid, body)
            calls[0] += 1
            if calls[0] == 1:
                raise RuntimeError('response lost after notes update')
        self.api.notes = uncertain
        with self.assertRaises(RuntimeError):
            self.run_replace()
        self.assertEqual(self.api.body, 'Existing notes')
        self.assertTrue(all(self.api.entries[i]['name'] == a['name'] for i, a in self.old.items()))
        self.assertFalse(any(w[0] == 'delete' for w in self.api.writes))

    def test_cleanup_failure_retains_verified_new_assets(self):
        def fail_delete(ident):
            raise RuntimeError('injected cleanup failure')
        self.api.delete = fail_delete
        with self.assertRaisesRegex(RuntimeError, 'New assets were verified'):
            self.run_replace()
        current = {a['name']: a['data'] for a in self.api.entries.values()}
        self.assertTrue(all(current[n] == data for n, data in self.files.items()))
        self.assertTrue(any(n.startswith('backup-123-') for n in current))

    def test_unexpected_existing_asset_blocks_writes(self):
        self.api.add('actions-123-0.apk', b'collision')
        with self.assertRaises(ValueError):
            self.run_replace()
        self.assertEqual(self.api.writes, [])

    def test_corrupt_staged_asset_leaves_original_names(self):
        original = self.api.upload
        def corrupt(rid, name, data):
            return original(rid, name, b'bad')
        self.api.upload = corrupt
        with self.assertRaises(ValueError):
            self.run_replace()
        self.assertTrue(all(self.api.entries[i]['name'] == a['name'] for i, a in self.old.items()))
        self.assertFalse(any(w[0] in ('rename', 'delete') for w in self.api.writes))


class RunTests(unittest.TestCase):
    def setUp(self):
        self.files = {'app.apk': b'payload', 'release-manifest.json': b'manifest'}
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            for name, data in self.files.items():
                archive.writestr(name, data)
        self.archive = output.getvalue()
        self.api = mock.Mock()
        self.run = {'id': 123, 'event': 'workflow_dispatch', 'status': 'completed',
                    'conclusion': 'success', 'repository': {'full_name': r.REPO},
                    'head_repository': {'full_name': r.REPO}, 'workflow_id': 55,
                    'head_sha': 'b' * 40, 'head_branch': 'master'}
        def query(endpoint):
            if endpoint.endswith('/runs/123'):
                return self.run
            if endpoint.endswith('/workflows/55'):
                return {'path': '.github/workflows/release-foss.yml'}
            return {'total_count': 1, 'artifacts': [{'id': 9, 'expired': False, 'name': 'foss-release-1.8.0-123', 'size_in_bytes': len(self.archive)}]}
        self.api.json.side_effect = query
        self.api.api.return_value = self.archive

    def test_matching_zip_is_accepted(self):
        r.verify_run(self.api, '123', 'b' * 40, '1.8.0', self.files)

    def test_wrong_bytes_are_rejected(self):
        with self.assertRaises(ValueError):
            r.verify_run(self.api, '123', 'b' * 40, '1.8.0', {'app.apk': b'wrong', 'release-manifest.json': b'manifest'})

    def test_wrong_workflow_head_is_rejected_before_download(self):
        with self.assertRaises(ValueError):
            r.verify_run(self.api, '123', 'c' * 40, '1.8.0', self.files)
        self.api.api.assert_not_called()

    def test_untrusted_run_is_rejected_before_download(self):
        self.run['head_repository']['full_name'] = 'attacker/repo'
        with self.assertRaises(ValueError):
            r.verify_run(self.api, '123', 'b' * 40, '1.8.0', self.files)
        self.api.api.assert_not_called()


if __name__ == '__main__':
    unittest.main()
