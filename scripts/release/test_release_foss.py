import contextlib
import io
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock

import release_foss as r


class RepositoryTests(unittest.TestCase):
    def test_checkout_origin_with_or_without_git_suffix(self):
        module = types.SimpleNamespace(REPO='maintainer/AniHyou-android')
        with mock.patch.dict('sys.modules', {'publish_catalog': module}):
            for suffix in ('', '.git'):
                with mock.patch.object(r, 'run', return_value='https://github.com/maintainer/AniHyou-android' + suffix):
                    self.assertEqual(r.repository(), module.REPO)
            with mock.patch.object(r, 'run', return_value='https://github.com/other/AniHyou-android.git'), self.assertRaises(ValueError):
                r.repository()


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.files = {'one.apk': b'one', 'two.apk': b'two'}
        for name, data in self.files.items():
            (self.root / name).write_bytes(data)
        self.head = 'a' * 40
        self.assets = {name: {'name': name, 'size': len(data), 'id': index}
                       for index, (name, data) in enumerate(self.files.items(), 1)}
        self.remote = {index: data for index, data in enumerate(self.files.values(), 1)}
        self.writes = []
        self.release = {'draft': True, 'prerelease': False, 'target_commitish': self.head,
                        'assets': []}

    def call(self, tag=None):
        def run(args, **kwargs):
            self.writes.append(args)
            if args[2] == 'upload':
                name = Path(args[4]).name
                self.release['assets'].append(self.assets[name])
            elif args[2] == 'edit':
                self.release['draft'] = False
            return ''
        def download(args, **kwargs):
            asset_id = int(args[-1].split('/')[-1])
            return mock.Mock(returncode=0, stdout=self.remote[asset_id])
        with mock.patch.object(r, 'validate'), mock.patch.object(r, 'verify_artifacts', return_value={'sourceCommit': self.head}), mock.patch.object(r, 'repository', return_value='owner/AniHyou-android'), mock.patch.object(r, 'tag_commit', return_value=self.head if tag is None else tag), mock.patch.object(r, 'find_release', side_effect=lambda *args: self.release), mock.patch.object(r, 'run', side_effect=run), mock.patch.object(r.subprocess, 'run', side_effect=download), contextlib.redirect_stdout(io.StringIO()):
            r.publish(self.root, self.head, '1.8.0', False, self.root)

    def test_tag_collision_has_no_writes(self):
        with self.assertRaises(ValueError):
            self.call(tag='b'*40)
        self.assertEqual(self.writes, [])

    def test_public_release_same_bytes_is_idempotent(self):
        self.release['draft'] = False
        self.release['assets'] = list(self.assets.values())
        self.call()
        self.assertEqual(self.writes, [])

    def test_draft_can_resume_missing_asset(self):
        self.release['assets'] = [self.assets['one.apk']]
        self.call()
        self.assertEqual([args[2] for args in self.writes], ['upload', 'edit'])
        self.assertEqual(Path(self.writes[0][4]).name, 'two.apk')

    def test_draft_mismatched_existing_asset_does_not_upload(self):
        self.release['assets'] = [self.assets['one.apk']]
        self.remote[1] = b'bad'
        with self.assertRaises(ValueError):
            self.call()
        self.assertEqual(self.writes, [])

    def test_published_mismatch_is_never_deleted(self):
        self.release['draft'] = False
        self.release['assets'] = list(self.assets.values())
        self.remote[1] = b'bad'
        with self.assertRaises(ValueError):
            self.call()
        self.assertEqual(self.writes, [])


class BuildTests(unittest.TestCase):
    def test_controlled_build_checks_graph_and_excludes_credentials(self):
        calls = []
        def subprocess_run(args, **kwargs):
            calls.append((args, kwargs))
            return mock.Mock(returncode=0, stdout=':app:assembleFossRelease SKIPPED\nBUILD SUCCESSFUL\n')
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(os.environ, {'KEY_PASSWORD': 'secret', 'GH_TOKEN': 'secret'}), mock.patch.object(r, 'validate') as validate, mock.patch.object(r, 'repository'), mock.patch.object(r, 'run', return_value='tree'), mock.patch.object(r.subprocess, 'run', side_effect=subprocess_run), mock.patch.object(r, 'record_build') as record, contextlib.redirect_stdout(io.StringIO()):
            root = Path(directory)
            r.build('1.8.0', 'a'*40, root)
            self.assertEqual(validate.call_count, 3)
            self.assertEqual(len(calls), 2)
            self.assertIn('--dry-run', calls[0][0])
            self.assertNotIn('--dry-run', calls[1][0])
            for _, kwargs in calls:
                self.assertNotIn('KEY_PASSWORD', kwargs['env'])
                self.assertNotIn('GH_TOKEN', kwargs['env'])
            record.assert_called_once_with(root / 'app/build/outputs/apk/foss/release', root)

    def test_unsafe_graph_stops_before_build_or_receipt(self):
        graph = ':app:installFossRelease SKIPPED\n:app:assembleFossRelease SKIPPED\nBUILD SUCCESSFUL\n'
        with mock.patch.object(r, 'validate'), mock.patch.object(r, 'repository'), mock.patch.object(r, 'run', return_value='tree'), mock.patch.object(r.subprocess, 'run', return_value=mock.Mock(returncode=0, stdout=graph)) as execute, mock.patch.object(r, 'record_build') as record, self.assertRaises(ValueError):
            r.build('1.8.0', 'a'*40)
        self.assertEqual(execute.call_count, 1)
        record.assert_not_called()


class GraphTests(unittest.TestCase):
    def test_host_profile_compile_allowed(self):
        graph = ':app:compileFossReleaseArtProfile SKIPPED\n:app:assembleFossRelease SKIPPED\nBUILD SUCCESSFUL\n'
        self.assertEqual(r.validate_graph(graph), 2)

    def test_device_and_profile_generation_rejected(self):
        for task in ('connectedFossReleaseAndroidTest', 'installFossRelease', 'pixelManagedDeviceCheck', 'generateFossReleaseBaselineProfile'):
            with self.subTest(task=task), self.assertRaises(ValueError):
                r.validate_graph(f':app:{task} SKIPPED\n:app:assembleFossRelease SKIPPED\nBUILD SUCCESSFUL\n')


if __name__ == '__main__':
    unittest.main()
