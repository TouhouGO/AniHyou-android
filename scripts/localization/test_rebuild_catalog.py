import copy
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from rebuild_catalog import rebuild


class RebuildCatalogTest(unittest.TestCase):
    def test_query_tampering_and_duplicate_json_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = self.fixture(root)
            query = root / manifest["wikidata"][0]["identity"]["queryFile"]
            query.write_text(query.read_text() + "# changed\n")
            with self.assertRaises(ValueError):
                rebuild(root / "manifest.json", root / "out.json", root / "report.json")
            manifest = self.fixture(root)
            (root / "manifest.json").write_text('{"schemaVersion":1,"schemaVersion":1}')
            with self.assertRaises(ValueError):
                rebuild(root / "manifest.json", root / "out.json", root / "report.json")
            self.assertFalse((root / "out.json").exists())

    def test_missing_and_duplicate_properties_are_rejected_before_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = self.fixture(root)
            for entries in [[], [{"property": "P11227"}] * 3,
                            [{"property": "P11227"}, {"property": "P11736"}, {"property": "P9999"}]]:
                manifest["wikidata"] = entries
                (root / "manifest.json").write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    rebuild(root / "manifest.json", root / "out.json", root / "report.json")
                self.assertFalse((root / "out.json").exists())

    def fixture(self, root):
        data = {"siteMeta": {"aniList": {"urlTemplate": "https://anilist.co/anime/{{id}}"}}, "items": [
            {"type": "tv", "title": "Raw", "titleTranslate": {"zh-Hans": ["\u52a8\u753b"]}, "sites": [{"site": "aniList", "id": "7"}]}
        ]}
        data["siteMeta"]["bangumi"] = {"urlTemplate": "https://bangumi.tv/subject/{{id}}"}
        data["items"][0]["sites"].append({"site": "bangumi", "id": "3425"})
        raw = json.dumps(data).encode()
        source = root / "anime.json.gz"
        source.write_bytes(gzip.compress(raw, mtime=0))
        manifest = {"schemaVersion": 1, "version": "2026.11.05", "anime": {
            "sourceFile": source.name, "revision": "a" * 40, "sha256": hashlib.sha256(raw).hexdigest()
        }, "wikidata": [], "tags": "tags.json"}
        for prop in ("P11227", "P11736", "P8731"):
            entry = {"property": prop, "fetchedAt": "2026-11-05T00:00:00Z", "httpStatus": 200}
            for identity in (False, True):
                suffix = "identity" if identity else "label"
                target = {"property": prop, "usable": True, "atLimit": False} if identity else entry
                query = (f'SELECT ?item ?id{"" if identity else " ?label"} WHERE {{\n  hint:Query hint:optimizer "None" .\n  ?item wdt:{prop} ?id .\n' + ('' if identity else '  ?item rdfs:label ?label .\n  FILTER(LANG(?label) IN ("zh-hans", "zh-cn"))\n') + '}\nLIMIT 100\n').encode()
                qfile = f"{prop}-{suffix}.rq"
                (root / qfile).write_bytes(query)
                row = {"item": {"type": "uri", "value": "http://www.wikidata.org/entity/Q1"}, "id": {"type": "literal", "value": "8"}}
                if not identity:
                    row["label"] = {"type": "literal", "xml:lang": "zh-hans", "value": "中文"}
                raw_wd = json.dumps({"results": {"bindings": [row]}}).encode()
                source_file = f"{prop}-{suffix}.json"
                (root / source_file).write_bytes(raw_wd)
                target.update(sourceFile=source_file, sha256=hashlib.sha256(raw_wd).hexdigest(), queryFile=qfile, querySha256=hashlib.sha256(query).hexdigest(), queryLimit=100, rowCount=1, httpStatus=200)
                if identity:
                    entry["identity"] = target
            manifest["wikidata"].append(entry)
        alias_raw = json.dumps([{"id": 7, "title": "别名", "synonyms": []}]).encode()
        (root / "aliases.json").write_bytes(alias_raw)
        manifest["searchAliases"] = {"sourceFile": "aliases.json", "revision": "a" * 40, "sha256": hashlib.sha256(alias_raw).hexdigest()}
        (root / "tags.json").write_text(json.dumps({"schemaVersion": 1, "version": "tags-v1", "entries": [{"rawName": "Action", "displayName": "\u52a8\u4f5c"}]}))
        (root / "manifest.json").write_text(json.dumps(manifest))
        return manifest

    def test_offline_rebuild_is_byte_reproducible(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.fixture(root)
            rebuild(root / "manifest.json", root / "one.json", root / "one-report.json")
            rebuild(root / "manifest.json", root / "two.json", root / "two-report.json")
            self.assertEqual((root / "one.json").read_bytes(), (root / "two.json").read_bytes())
            catalog = json.loads((root / "one.json").read_text())
            report = json.loads((root / "one-report.json").read_text())
            self.assertIn(7, [r["key"]["id"] for r in catalog["records"]])
            self.assertEqual("Action", catalog["tags"][0]["rawName"])
            self.assertEqual(hashlib.sha256((root / "one.json").read_bytes()).hexdigest(), report["catalog_sha256"])

    def test_corrupt_compressed_input_does_not_replace_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = self.fixture(root)
            manifest["anime"]["sha256"] = "0" * 64
            (root / "manifest.json").write_text(json.dumps(manifest))
            (root / "catalog.json").write_text("old")
            with self.assertRaises(ValueError):
                rebuild(root / "manifest.json", root / "catalog.json", root / "report.json")
            self.assertEqual("old", (root / "catalog.json").read_text())

    def test_missing_or_tampered_alias_source_keeps_previous_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = self.fixture(root)
            (root / "catalog.json").write_text("old")
            (root / "aliases.json").write_text("[]")
            with self.assertRaises(ValueError):
                rebuild(root / "manifest.json", root / "catalog.json", root / "report.json")
            self.assertEqual("old", (root / "catalog.json").read_text())
            del manifest["searchAliases"]
            (root / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(KeyError):
                rebuild(root / "manifest.json", root / "catalog.json", root / "report.json")
            self.assertEqual("old", (root / "catalog.json").read_text())

    def test_conflicting_labels_do_not_quarantine_unique_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = self.fixture(root)
            entry = next(e for e in manifest["wikidata"] if e["property"] == "P8731")
            for identity in (False, True):
                target = entry["identity"] if identity else entry
                path = root / target["sourceFile"]
                payload = json.loads(path.read_text())
                row = copy.deepcopy(payload["results"]["bindings"][0])
                row["item"]["value"] = "http://www.wikidata.org/entity/Q9"
                row["id"]["value"] = "9"
                payload["results"]["bindings"].append(row)
                if not identity:
                    other = copy.deepcopy(row)
                    other["label"]["value"] = "另一标题"
                    payload["results"]["bindings"].append(other)
                raw = json.dumps(payload).encode()
                path.write_bytes(raw)
                target["sha256"] = hashlib.sha256(raw).hexdigest()
                target["rowCount"] = len(payload["results"]["bindings"])
            raw = json.dumps([{"id": 9, "title": "独立别名", "synonyms": []}]).encode()
            (root / "aliases.json").write_bytes(raw)
            manifest["searchAliases"]["sha256"] = hashlib.sha256(raw).hexdigest()
            (root / "manifest.json").write_text(json.dumps(manifest))
            rebuild(root / "manifest.json", root / "catalog.json", root / "report.json")
            catalog = json.loads((root / "catalog.json").read_text())
            report = json.loads((root / "report.json").read_text())
            self.assertEqual([9], report["source_reports"]["P8731"]["conflicting_labels"])
            self.assertEqual([9], [a["aniListId"] for a in catalog["searchAliases"]])
            self.assertNotIn(9, [r["key"]["id"] for r in catalog["records"] if r["key"]["kind"] == "MEDIA"])

    def test_invalid_alias_revision_keeps_previous_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = self.fixture(root)
            manifest["searchAliases"]["revision"] = "x"
            (root / "manifest.json").write_text(json.dumps(manifest))
            (root / "catalog.json").write_text("old catalog")
            (root / "report.json").write_text("old report")
            with self.assertRaises(ValueError):
                rebuild(root / "manifest.json", root / "catalog.json", root / "report.json")
            self.assertEqual("old catalog", (root / "catalog.json").read_text())
            self.assertEqual("old report", (root / "report.json").read_text())

    def test_failed_acquisition_is_not_a_usable_component(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = self.fixture(root)
            manifest["wikidata"] = [{"httpStatus": 504, "sourceFile": "error.gz", "property": "P11227"}]
            (root / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                rebuild(root / "manifest.json", root / "catalog.json", root / "report.json")
            self.assertFalse((root / "catalog.json").exists())


if __name__ == "__main__":
    unittest.main()
