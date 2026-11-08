import copy
import datetime
import gzip
import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import urlparse, parse_qs

from package_catalog import package
from publish_catalog import publish, valid_manifest
from refresh_sources import CATALOG, MANIFEST, REPORT, check_shrink, refresh, loads
from rebuild_catalog import rebuild
from test_rebuild_catalog import RebuildCatalogTest


class FakeGitHub:
    def __init__(self):
        self.releases = {}
        self.fail = None
        self.calls = []
    def release(self, tag):
        rel = self.releases.get(tag)
        return copy.deepcopy(rel) if rel else None
    def refresh(self, release):
        return self.release(release['id'])
    def create(self, tag, draft):
        self.calls.append(('create', tag))
        rel = {'id': tag, 'draft': draft, 'assets': []}
        self.releases[tag] = rel
        return copy.deepcopy(rel)
    def update(self, release, **kw):
        self.calls.append(('update', release['id']))
        self.releases[release['id']].update(kw)
        return self.release(release['id'])
    def upload(self, release, name, raw):
        self.calls.append(('upload', release['id'], name))
        if self.fail == (release['id'], name):
            self.fail = None
            raise RuntimeError('upload failed')
        self.releases[release['id']]['assets'].append({'id': (release['id'], name), 'name': name, 'raw': raw})
    def delete(self, asset):
        tag, name = asset['id']
        self.calls.append(('delete', tag, name))
        rel = self.releases[tag]
        rel['assets'] = [a for a in rel['assets'] if a['name'] != name]
    def asset(self, release, name):
        return next((a['raw'] for a in release['assets'] if a['name'] == name), None)
    def public(self, tag, name):
        rel = self.release(tag)
        if not rel or rel['draft']:
            raise RuntimeError('not public')
        raw = self.asset(rel, name)
        if raw is None:
            raise RuntimeError('missing')
        return raw


class PipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name)
        source = Path(__file__).resolve().parents[2] / CATALOG
        package(source, cls.path)
        cls.manifest = valid_manifest((cls.path / 'catalog-manifest.json').read_bytes())
        cls.notices = {'NOTICE.txt': b'license'}
    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()
    def setUp(self):
        self.api = FakeGitHub()
    def seed_channel(self, version, digest=None):
        m = dict(self.manifest, version=version, downloadUrl=f'https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v{version}/catalog.json')
        if digest:
            m['sha256'] = digest
        rel = self.api.create('catalog-latest', False)
        raw = (json.dumps(m) + '\n').encode()
        self.api.upload(rel, 'catalog-manifest.json', raw)
        return raw
    def test_publish_and_idempotent(self):
        publish(self.api, self.path, self.notices)
        first = len(self.api.calls)
        publish(self.api, self.path, self.notices)
        self.assertEqual(first, len(self.api.calls))
        self.assertFalse(self.api.release('catalog-v' + self.manifest['version'])['draft'])
    def test_downgrade_and_same_version_collision_do_not_write(self):
        for version, sha in [('2099.01.01', None), (self.manifest['version'], '0' * 64)]:
            self.api = FakeGitHub()
            old = self.seed_channel(version, sha)
            count = len(self.api.calls)
            with self.assertRaises(ValueError):
                publish(self.api, self.path, self.notices)
            self.assertEqual(count, len(self.api.calls))
            self.assertEqual(old, self.api.public('catalog-latest', 'catalog-manifest.json'))
    def test_dated_failure_and_channel_upload_failure_preserve_pointer(self):
        for fail in [('catalog-v' + self.manifest['version'], 'NOTICE.txt'), ('catalog-latest', 'catalog-manifest.json')]:
            self.api = FakeGitHub()
            old = self.seed_channel('2020.01.01')
            self.api.fail = fail
            with self.assertRaises(RuntimeError):
                publish(self.api, self.path, self.notices)
            self.assertEqual(old, self.api.public('catalog-latest', 'catalog-manifest.json'))
    def test_existing_dated_collision_does_not_touch_channel(self):
        old = self.seed_channel('2020.01.01')
        rel = self.api.create('catalog-v' + self.manifest['version'], True)
        self.api.upload(rel, 'catalog.json', b'wrong')
        with self.assertRaises(ValueError):
            publish(self.api, self.path, self.notices)
        self.assertEqual(old, self.api.public('catalog-latest', 'catalog-manifest.json'))
    def test_strict_json_and_shrink(self):
        for raw in [b'{"a":NaN}', b'{"a":1,"a":2}']:
            with self.assertRaises(ValueError):
                loads(raw)
        old = {'records': [{'key': {'kind': 'X'}}] * 10, 'links': [1], 'searchAliases': [1]}
        new = copy.deepcopy(old)
        new['records'] = new['records'][:8]
        with self.assertRaisesRegex(ValueError, 'manual review'):
            check_shrink(old, new)

    def test_refresh_promotes_then_noops_and_rejects_same_day_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            base = root / MANIFEST.parent
            base.mkdir(parents=True)
            fixture = RebuildCatalogTest().fixture(base)
            (base / 'manifest.json').rename(root / MANIFEST)
            (root / CATALOG).parent.mkdir(parents=True)
            (root / REPORT).parent.mkdir(parents=True)
            rebuild(root / MANIFEST, root / CATALOG, root / REPORT)
            anime = json.loads(gzip.decompress((base / 'anime.json.gz').read_bytes()))
            anime['items'][0]['titleTranslate']['zh-Hans'] = ['新标题']
            def get(url, **kwargs):
                if 'api.github.com' in url:
                    return json.dumps({'sha': 'b' * 40}).encode()
                if 'raw.githubusercontent.com' in url:
                    return json.dumps(anime).encode() if 'bangumi-data' in url else (base / 'aliases.json').read_bytes()
                query = parse_qs(urlparse(url).query)['query'][0]
                entry = next(e for e in fixture['wikidata'] if e['property'] in query)
                target = entry['identity'] if 'SELECT ?item ?id WHERE' in query else entry
                return (base / target['sourceFile']).read_bytes()
            date = datetime.date(2026, 11, 6)
            self.assertTrue(refresh(root, get, date))
            self.assertEqual('2026.11.06', loads((root / CATALOG).read_bytes())['version'])
            before = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            self.assertFalse(refresh(root, get, date))
            self.assertEqual(before, {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()})
            anime['items'][0]['titleTranslate']['zh-Hans'] = ['又一个标题']
            with self.assertRaisesRegex(ValueError, 'later date'):
                refresh(root, get, date)
            self.assertEqual(before, {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()})

    def test_refresh_guards_media_shrink_and_identity_evidence(self):
        def binding(qid, identifier, label=False):
            row = {'item': {'type': 'uri', 'value': 'http://www.wikidata.org/entity/Q' + str(qid)},
                   'id': {'type': 'literal', 'value': str(identifier)}}
            if label:
                row['label'] = {'type': 'literal', 'xml:lang': 'zh-hans', 'value': '中文' + str(identifier)}
            return row

        for scenario in ('manga-loss', 'resolved-conflict', 'labels-loss', 'identity-loss'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                base = root / MANIFEST.parent
                base.mkdir(parents=True)
                manifest = RebuildCatalogTest().fixture(base)
                anime = json.loads(gzip.decompress((base / 'anime.json.gz').read_bytes()))
                for identifier in range(100, 130):
                    anime['items'].append({'type': 'tv', 'title': 'Raw',
                        'titleTranslate': {'zh-Hans': ['动画' + str(identifier)]},
                        'sites': [{'site': 'aniList', 'id': str(identifier)}]})
                anime_raw = json.dumps(anime).encode()
                (base / 'anime.json.gz').write_bytes(gzip.compress(anime_raw, mtime=0))
                import hashlib
                manifest['anime']['sha256'] = hashlib.sha256(anime_raw).hexdigest()
                manga = next(e for e in manifest['wikidata'] if e['property'] == 'P8731')
                labels = [binding(n, n + 1000, True) for n in range(1, 11)]
                identities = [binding(n, n + 1000) for n in range(1, 11)]
                if scenario == 'resolved-conflict':
                    identities.append(binding(99, 1001))
                for target, rows in ((manga, labels), (manga['identity'], identities)):
                    raw = json.dumps({'results': {'bindings': rows}}).encode()
                    (base / target['sourceFile']).write_bytes(raw)
                    target['sha256'] = hashlib.sha256(raw).hexdigest()
                    target['rowCount'] = len(rows)
                (base / 'manifest.json').write_text(json.dumps(manifest))
                (base / 'manifest.json').rename(root / MANIFEST)
                (root / CATALOG).parent.mkdir(parents=True)
                (root / REPORT).parent.mkdir(parents=True)
                rebuild(root / MANIFEST, root / CATALOG, root / REPORT)
                new_labels, new_identities = copy.deepcopy(labels), copy.deepcopy(identities)
                if scenario == 'manga-loss':
                    # Competing claims remove half the manga while raw row counts grow;
                    # the many anime records hide this in the MEDIA aggregate.
                    new_identities.extend(binding(200 + n, 1000 + n) for n in range(1, 6))
                elif scenario == 'resolved-conflict':
                    new_identities.remove(binding(99, 1001))
                    new_identities.append(binding(999, 9999))  # Same count, different claim.
                elif scenario == 'labels-loss':
                    new_labels = new_labels[:8]
                else:
                    new_identities.extend(binding(300 + n, 2000 + n) for n in range(10))
                    # Add extra identities to the old evidence too; candidate loses
                    # more than 10% without losing any label claims.
                    identities.extend(binding(300 + n, 2000 + n) for n in range(10))
                    target = manga['identity']
                    raw = json.dumps({'results': {'bindings': identities}}).encode()
                    (base / target['sourceFile']).write_bytes(raw)
                    target['sha256'] = hashlib.sha256(raw).hexdigest()
                    target['rowCount'] = len(identities)
                    (root / MANIFEST).write_text(json.dumps(manifest))
                    rebuild(root / MANIFEST, root / CATALOG, root / REPORT)
                    new_identities = new_identities[:10]
                def get(url, **kwargs):
                    if 'api.github.com' in url:
                        return json.dumps({'sha': 'b' * 40}).encode()
                    if 'raw.githubusercontent.com' in url:
                        return anime_raw if 'bangumi-data' in url else (base / 'aliases.json').read_bytes()
                    query = parse_qs(urlparse(url).query)['query'][0]
                    entry = next(e for e in manifest['wikidata'] if e['property'] in query)
                    identity = 'SELECT ?item ?id WHERE' in query
                    if entry['property'] == 'P8731':
                        rows = new_identities if identity else new_labels
                        return json.dumps({'results': {'bindings': rows}}).encode()
                    target = entry['identity'] if identity else entry
                    return (base / target['sourceFile']).read_bytes()
                before = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
                message = {'manga-loss': 'records:MEDIA:MANGA',
                           'resolved-conflict': 'Previously ambiguous P8731 IDs now accepted',
                           'labels-loss': 'P8731 labels shrink',
                           'identity-loss': 'P8731 identity shrink'}[scenario]
                with self.assertRaisesRegex(ValueError, message):
                    refresh(root, get, datetime.date(2026, 11, 6))
                self.assertEqual(before, {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()})

    def test_refresh_late_failure_leaves_all_production_files_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            base = root / MANIFEST.parent
            base.mkdir(parents=True)
            fixture = RebuildCatalogTest().fixture(base)
            (base / 'manifest.json').rename(root / MANIFEST)
            (root / CATALOG).parent.mkdir(parents=True)
            (root / REPORT).parent.mkdir(parents=True)
            rebuild(root / MANIFEST, root / CATALOG, root / REPORT)
            before = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            wd_count = 0
            def get(url, **kwargs):
                nonlocal wd_count
                if 'api.github.com' in url:
                    return json.dumps({'sha': 'b' * 40}).encode()
                if 'raw.githubusercontent.com' in url:
                    return (base / ('anime.json.gz' if 'bangumi-data' in url else 'aliases.json')).read_bytes() if 'soruly' in url else gzip.decompress((base / 'anime.json.gz').read_bytes())
                wd_count += 1
                if wd_count == 6:
                    raise RuntimeError('last acquisition failed')
                prop = fixture['wikidata'][(wd_count - 1) // 2]
                entry = prop['identity'] if wd_count % 2 == 0 else prop
                return (base / entry['sourceFile']).read_bytes()
            with self.assertRaisesRegex(RuntimeError, 'last acquisition'):
                refresh(root, get, datetime.date(2026, 11, 6))
            self.assertEqual(before, {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()})


if __name__ == '__main__':
    unittest.main()
