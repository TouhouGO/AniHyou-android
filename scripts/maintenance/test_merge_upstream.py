import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('merge-upstream.sh').resolve()
EMAIL = '93237754+TouhouGO@users.noreply.github.com'
ENV = {k: v for k, v in os.environ.items() if not k.startswith(('GIT_AUTHOR_', 'GIT_COMMITTER_'))}


def git(repo, *args):
    return subprocess.run(['git', *args], cwd=repo, env=ENV, check=True,
                          capture_output=True, text=True).stdout.strip()


class MergeUpstreamTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.upstream = root / 'upstream'
        self.fork = root / 'fork'
        self.report = root / 'report'
        self.output = root / 'output'
        self.summary = root / 'summary'
        self.upstream.mkdir()
        git(self.upstream, 'init', '-q', '-b', 'master')
        git(self.upstream, 'config', 'user.name', 'TouhouGO')
        git(self.upstream, 'config', 'user.email', EMAIL)
        (self.upstream / 'source.txt').write_text('original\n')
        git(self.upstream, 'add', '.')
        git(self.upstream, 'commit', '-qm', 'official baseline')
        git(root, 'clone', '-q', str(self.upstream), str(self.fork))
        git(self.fork, 'config', 'user.name', 'TouhouGO')
        git(self.fork, 'config', 'user.email', EMAIL)
        (self.fork / 'localization.txt').write_text('local translation\n')
        git(self.fork, 'add', '.')
        git(self.fork, 'commit', '-qm', 'localization')
        # A local bare origin acts as fork master; no network or remote writes occur.
        self.origin = root / 'origin.git'
        git(root, 'clone', '-q', '--bare', str(self.fork), str(self.origin))
        git(self.fork, 'remote', 'set-url', 'origin', str(self.origin))
        self.base = git(self.fork, 'rev-parse', 'HEAD')

    def run_sync(self, **overrides):
        env = dict(ENV, UPSTREAM_URL=str(self.upstream), UPSTREAM_REPORT_DIR=str(self.report),
                   GITHUB_OUTPUT=str(self.output), GITHUB_STEP_SUMMARY=str(self.summary))
        env.update(overrides)
        return subprocess.run(['bash', str(SCRIPT)], cwd=self.fork, env=env,
                              capture_output=True, text=True)

    def advance_upstream(self):
        (self.upstream / 'source.txt').write_text('official update\n')
        git(self.upstream, 'commit', '-qam', 'official update')
        return git(self.upstream, 'rev-parse', 'HEAD')

    def test_unchanged(self):
        result = self.run_sync()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('changed=false', self.output.read_text())
        self.assertEqual(git(self.fork, 'rev-parse', 'HEAD'), self.base)

    def test_merge_preserves_translation_and_ancestry_and_identity(self):
        upstream = self.advance_upstream()
        result = self.run_sync(GIT_AUTHOR_NAME='Other', GIT_AUTHOR_EMAIL='author@example.invalid',
                               GIT_COMMITTER_NAME='Other', GIT_COMMITTER_EMAIL='committer@example.invalid')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('changed=true', self.output.read_text())
        self.assertEqual((self.fork / 'localization.txt').read_text(), 'local translation\n')
        self.assertEqual((self.fork / 'source.txt').read_text(), 'official update\n')
        git(self.fork, 'merge-base', '--is-ancestor', upstream, 'HEAD')
        self.assertEqual(git(self.fork, 'rev-list', '--parents', '-n', '1', 'HEAD').split()[1:], [self.base, upstream])
        self.assertEqual(git(self.fork, 'show', '-s', '--format=%an|%ae|%cn|%ce', 'HEAD'),
                         f'TouhouGO|{EMAIL}|TouhouGO|{EMAIL}')

    def test_conflict_stops_without_success(self):
        (self.fork / 'source.txt').write_text('local change\n')
        git(self.fork, 'commit', '-qam', 'local change')
        git(self.origin, 'fetch', '-q', str(self.fork), 'master:master')
        self.advance_upstream()
        result = self.run_sync()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('changed=true', self.output.read_text())
        self.assertIn('source.txt', (self.report / 'report.md').read_text())
        self.assertIn('source.txt', git(self.fork, 'diff', '--name-only', '--diff-filter=U'))

    def test_foreign_fork_identity_is_rejected_without_leaking_it(self):
        (self.fork / 'private.txt').write_text('local\n')
        git(self.fork, 'add', 'private.txt')
        git(self.fork, '-c', 'user.name=Other', '-c', 'user.email=other@example.invalid',
            'commit', '-qm', 'foreign identity')
        git(self.origin, 'fetch', '-q', str(self.fork), 'master:master')
        self.advance_upstream()
        result = self.run_sync()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('1 commits', result.stderr)
        self.assertIn('author mismatches', result.stderr)
        self.assertNotIn('other@example.invalid', result.stderr + result.stdout)
        self.assertEqual(git(self.fork, 'branch', '--show-current'), 'master')
        self.assertEqual(git(self.fork, 'branch', '--list', 'claude/upstream-sync-*'), '')

    def test_rejects_nonofficial_network_and_other_branch(self):
        self.assertNotEqual(self.run_sync(UPSTREAM_URL='https://example.invalid/repo.git').returncode, 0)
        self.assertNotEqual(self.run_sync(UPSTREAM_BRANCH='other').returncode, 0)


if __name__ == '__main__':
    unittest.main()
