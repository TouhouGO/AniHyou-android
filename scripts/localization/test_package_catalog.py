import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from package_catalog import package


class PackageTest(unittest.TestCase):
    def test_manifest_matches_exact_catalog_bytes(self):
        source = Path(__file__).resolve().parents[2] / 'core/localization/src/main/resources/localization/catalog.json'
        with tempfile.TemporaryDirectory() as tmp:
            manifest = package(source, tmp)
            raw = (Path(tmp) / 'catalog.json').read_bytes()
            self.assertEqual(source.read_bytes(), raw)
            self.assertEqual(len(raw), manifest['size'])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), manifest['sha256'])
            self.assertEqual(manifest, json.loads((Path(tmp) / 'catalog-manifest.json').read_text()))
            self.assertEqual(manifest['downloadUrl'], f"https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v{manifest['version']}/catalog.json")

    def test_alias_validation_and_default_platform(self):
        source = Path(__file__).resolve().parents[2] / 'core/localization/src/main/resources/localization/catalog.json'
        catalog = json.loads(source.read_text())
        catalog['records'][0]['key'].pop('platform', None)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'source.json'
            path.write_text(json.dumps(catalog))
            package(path, Path(tmp) / 'out')
            catalog['searchAliases'] = [{'aniListId': 1, 'names': [], 'sourceId': catalog['sources'][0]['id']}]
            path.write_text(json.dumps(catalog))
            with self.assertRaises(ValueError): package(path, Path(tmp) / 'invalid')

    def test_rejects_invalid_date_and_duplicates(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'bad.json'
            path.write_text('{"version":"2026.02.30","version":"2026.02.30"}')
            with self.assertRaises(ValueError): package(path, Path(tmp) / 'out')

    def test_rejects_empty_year_zero_and_placeholder_tag(self):
        source = Path(__file__).resolve().parents[2] / 'core/localization/src/main/resources/localization/catalog.json'
        catalog = json.loads(source.read_text())
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'catalog.json'
            empty = {**catalog, 'records': [], 'tags': [], 'links': [], 'searchAliases': []}
            path.write_text(json.dumps(empty))
            with self.assertRaises(ValueError): package(path, Path(tmp) / 'empty')
            year_zero = {**catalog, 'version': '0000.01.01'}
            path.write_text(json.dumps(year_zero))
            with self.assertRaises(ValueError): package(path, Path(tmp) / 'year-zero')
            placeholder = {**catalog, 'tags': [{'rawName': '{{Action}}', 'displayName': '动作', 'sourceId': catalog['sources'][0]['id']}]}
            path.write_text(json.dumps(placeholder))
            with self.assertRaises(ValueError): package(path, Path(tmp) / 'placeholder')
            path.write_text('{"schemaVersion":1,"version":"2026.02.30","sources":[],"records":[]}')
            with self.assertRaises(ValueError): package(path, Path(tmp) / 'out')


if __name__ == '__main__': unittest.main()
