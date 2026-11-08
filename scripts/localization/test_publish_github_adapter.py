"""Exercise publisher with the real GitHub adapter and a simulated HTTP boundary."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
from urllib.parse import parse_qs, unquote, urlsplit

from package_catalog import package
from publish_catalog import API, GitHub, publish
from refresh_sources import CATALOG


class Transport:
    def __init__(self):
        self.releases = []
        self.bytes = {}
        self.calls = []
        self.next_id = 10000

    def request(self, url, method='GET', data=None, content_type='application/json', public=False, missing=False, binary=False):
        self.calls.append((method, url))
        parsed = urlsplit(url)
        path = parsed.path
        prefix = '/repos/TouhouGO/AniHyou-android'
        def encode(value):
            return json.dumps(value).encode()
        if public:
            tag, name = path.split('/releases/download/', 1)[1].split('/', 1)
            rel = next(r for r in self.releases if r['tag_name'] == unquote(tag) and not r['draft'])
            asset = next(a for a in rel['assets'] if a['name'] == unquote(name))
            return self.bytes[asset['id']]
        if path == prefix + '/releases/tags/' + unquote(path.rsplit('/', 1)[-1]):
            tag = unquote(path.rsplit('/', 1)[-1])
            published = [r for r in self.releases if r['tag_name'] == tag and not r['draft']]
            return encode(published[0]) if published else None
        if path == prefix + '/releases' and method == 'GET':
            page = int(parse_qs(parsed.query)['page'][0])
            return encode(self.releases[(page - 1) * 100:page * 100])
        if path == prefix + '/releases' and method == 'POST':
            payload = json.loads(data)
            self.next_id += 1
            rel = {'id': self.next_id, 'tag_name': payload['tag_name'], 'draft': payload['draft'], 'assets': []}
            self.releases.append(rel)
            return encode(rel)
        if '/releases/assets/' in path:
            return self.bytes[int(path.rsplit('/', 1)[-1])]
        if '/releases/' in path and path.endswith('/assets'):
            release_id = int(path.split('/releases/')[1].split('/')[0])
            rel = next(r for r in self.releases if r['id'] == release_id)
            name = parse_qs(parsed.query)['name'][0]
            self.next_id += 1
            rel['assets'].append({'id': self.next_id, 'name': name})
            self.bytes[self.next_id] = data
            return b'{}'
        if '/releases/' in path:
            release_id = int(path.rsplit('/', 1)[-1])
            rel = next(r for r in self.releases if r['id'] == release_id)
            if method == 'PATCH':
                rel.update(json.loads(data))
            return encode(rel)
        raise AssertionError((method, url))

    def add_draft(self, tag, assets=()):
        self.next_id += 1
        rel = {'id': self.next_id, 'tag_name': tag, 'draft': True, 'assets': []}
        self.releases.append(rel)
        for name, raw in assets:
            self.next_id += 1
            rel['assets'].append({'id': self.next_id, 'name': name})
            self.bytes[self.next_id] = raw
        return rel


class GitHubAdapterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.manifest = package(Path(__file__).resolve().parents[2] / CATALOG, self.directory)
        self.tag = 'catalog-v' + self.manifest['version']
        self.transport = Transport()
        self.github = GitHub('test-token')
        patcher = patch.object(self.github, 'request', side_effect=self.transport.request)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_resumes_existing_draft_across_pages_and_refreshes_by_id(self):
        for number in range(100):
            self.transport.add_draft('unrelated-' + str(number))
        catalog = (self.directory / 'catalog.json').read_bytes()
        draft = self.transport.add_draft(self.tag, [('catalog.json', catalog)])
        draft_id = draft['id']
        publish(self.github, self.directory, {'NOTICE.txt': b'notice'})
        self.assertFalse(draft['draft'])
        self.assertEqual(catalog, self.transport.bytes[draft['assets'][0]['id']])
        self.assertEqual(1, sum(r['tag_name'] == self.tag for r in self.transport.releases))
        self.assertIn(('GET', API + f'/releases/{draft_id}'), self.transport.calls)
        self.assertIn(('GET', API + '/releases?per_page=100&page=2'), self.transport.calls)
        created = [u for method, u in self.transport.calls if method == 'POST' and u == API + '/releases']
        self.assertEqual(1, len(created))  # Only the new catalog-latest channel.
        publish(self.github, self.directory, {'NOTICE.txt': b'notice'})
        self.assertEqual(1, len([r for r in self.transport.releases if r['tag_name'] == self.tag]))

    def test_duplicate_draft_across_pages_fails_closed(self):
        self.transport.add_draft(self.tag)
        for number in range(99):
            self.transport.add_draft('other-' + str(number))
        self.transport.add_draft(self.tag)
        with self.assertRaisesRegex(ValueError, 'Multiple releases'):
            self.github.release(self.tag)
        self.assertFalse(any(method != 'GET' for method, url in self.transport.calls))

    def test_listing_limit_fails_closed_even_when_draft_found(self):
        self.transport.add_draft(self.tag)
        for number in range(1999):
            self.transport.add_draft('other-' + str(number))
        with self.assertRaisesRegex(ValueError, 'exceeded 20 pages'):
            self.github.release(self.tag)
        self.assertFalse(any(method != 'GET' for method, url in self.transport.calls))

    def test_refresh_rejects_changed_identity(self):
        rel = self.transport.add_draft(self.tag)
        stale = copy.deepcopy(rel)
        rel['tag_name'] = 'other'
        with self.assertRaisesRegex(ValueError, 'identity changed'):
            self.github.refresh(stale)

    def test_only_http_404_is_treated_as_missing(self):
        github = GitHub('test-token')
        with patch('urllib.request.build_opener') as opener:
            opener.return_value.open.side_effect = urllib.error.HTTPError('https://api.github.com/test', 404, 'missing', {}, None)
            self.assertIsNone(github.request('https://api.github.com/test', missing=True))
            opener.return_value.open.side_effect = urllib.error.HTTPError('https://api.github.com/test', 403, 'forbidden', {}, None)
            with self.assertRaises(urllib.error.HTTPError):
                github.request('https://api.github.com/test', missing=True)

    def test_listing_errors_do_not_create_release(self):
        def failed(url, *args, **kwargs):
            if '/releases/tags/' in url:
                return None
            raise OSError('listing failed')
        with patch.object(self.github, 'request', side_effect=failed):
            with self.assertRaisesRegex(OSError, 'listing failed'):
                publish(self.github, self.directory, {'NOTICE.txt': b'notice'})


if __name__ == '__main__':
    unittest.main()
