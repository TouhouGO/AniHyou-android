import unittest
from import_soruly import build_catalog

class ImportSorulyTest(unittest.TestCase):
    def test_quarantine_and_types(self):
        components = [{'records': [{'key': {'kind': 'MEDIA', 'id': 1}, 'mediaKind': 'ANIME'}], 'links': [{'aniListId': 2, 'mediaKind': 'MANGA'}]}]
        rows = [{'id': i, 'title': '标题', 'synonyms': ['标题', '別名']} for i in range(1, 5)]
        catalog, report = build_catalog(rows, 'a' * 40, 'b' * 64, components, {3})
        aliases = catalog['searchAliases']
        self.assertEqual([1, 2, 4], [a['aniListId'] for a in aliases])
        self.assertEqual(['标题', '別名'], aliases[0]['names'])
        self.assertEqual(['ANIME', 'MANGA', None], [a.get('mediaKind') for a in aliases])
        self.assertEqual([3], report['excluded_ids'])
        self.assertEqual(2, report['new_searchable_ids'])

    def test_invalid_and_duplicate_id_rejected(self):
        row = {'id': 1, 'title': 'x', 'synonyms': []}
        for payload in ([row, row], [{**row, 'id': '1'}], [{**row, 'title': ' '}], [{**row, 'synonyms': ['']} ]):
            with self.assertRaises(ValueError):
                build_catalog(payload, 'a' * 40, 'b' * 64, [], set())

    def test_source_metadata_requires_pinned_hashes(self):
        rows = [{'id': 1, 'title': 'x', 'synonyms': []}]
        for revision, digest in [('x', 'b' * 64), ('A' * 40, 'b' * 64), ('a' * 40, None), ('a' * 40, 'b')]:
            with self.subTest(revision=revision, digest=digest), self.assertRaises(ValueError):
                build_catalog(rows, revision, digest, [], set())
