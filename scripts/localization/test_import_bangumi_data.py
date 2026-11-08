import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("import_bangumi_data.py")


class ImportBangumiDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not MODULE_PATH.exists():
            raise AssertionError("The deterministic catalog importer is not implemented")
        spec = importlib.util.spec_from_file_location("catalog_importer", MODULE_PATH)
        cls.importer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.importer)

    def item(self, media_id="20", names=None, title="NARUTO", media_type="tv"):
        return {
            "title": title,
            "type": media_type,
            "titleTranslate": {"zh-Hans": names if names is not None else ["\u706b\u5f71\u5fcd\u8005"]},
            "sites": [{"site": "aniList", "id": media_id}],
        }

    def source(self, items):
        return {"siteMeta": {"aniList": {"urlTemplate": "https://anilist.co/anime/{{id}}"}}, "items": items}

    def convert(self, items):
        return self.importer.build_catalog(self.source(items), "a" * 40, "b" * 64)

    def test_explicit_id_and_aliases_keep_original_text(self):
        catalog, report = self.convert([self.item(names=["\u706b\u5f71\u5fcd\u8005", " \u706b\u5f71 ", "\u706b\u5f71", ""])])
        self.assertEqual(1, len(catalog["records"]))
        record = catalog["records"][0]
        self.assertEqual({"platform": "ANILIST", "kind": "MEDIA", "id": 20}, record["key"])
        self.assertEqual("ANIME", record["mediaKind"])
        self.assertEqual("\u706b\u5f71\u5fcd\u8005", record["displayName"])
        self.assertEqual(["\u706b\u5f71", "NARUTO"], record["aliases"])
        self.assertEqual(1, report["accepted_records"])

    def test_shared_id_is_quarantined_even_when_one_entry_has_no_chinese(self):
        catalog, report = self.convert([self.item(), self.item(names=[], title="Other edition")])
        self.assertEqual([], catalog["records"])
        self.assertEqual([20], report["conflicting_ids"])

    def test_no_guess_from_native_title_or_missing_platform_id(self):
        missing_id = self.item()
        missing_id["sites"] = [{"site": "bangumi", "id": "20"}]
        catalog, report = self.convert([self.item(names=[], title="\u539f\u6587"), missing_id])
        self.assertEqual([], catalog["records"])
        self.assertEqual(1, report["without_anilist_id"])
        self.assertEqual(1, report["without_simplified_title"])

    def test_invalid_id_and_unsupported_type_do_not_produce_records(self):
        for media_id in ["0", "-1", "x", "1.5", True, 2147483648]:
            with self.subTest(media_id=media_id):
                with self.assertRaises(ValueError):
                    self.convert([self.item(media_id=media_id)])
        with self.assertRaises(ValueError):
            self.convert([self.item(media_type="manga")])

    def test_duplicate_links_in_one_item_are_not_duplicate_entities(self):
        item = self.item()
        item["sites"].append({"site": "aniList", "id": "20"})
        catalog, report = self.convert([item])
        self.assertEqual(1, len(catalog["records"]))
        self.assertEqual([], report["conflicting_ids"])

    def test_multilingual_names_are_searchable_without_changing_display(self):
        item = self.item(names=["中文", "别名"])
        item["titleTranslate"].update({"zh-Hant": ["繁體", "中文"], "en": ["English", "别名"]})
        record = self.convert([item])[0]["records"][0]
        self.assertEqual("中文", record["displayName"])
        self.assertEqual(["别名", "NARUTO", "English", "繁體"], record["aliases"])

    def test_distinct_ids_on_one_item_are_all_quarantined(self):
        item = self.item()
        item["sites"].append({"site": "aniList", "id": "21"})
        catalog, report = self.convert([item, self.item("21", title="Other")])
        self.assertEqual([], catalog["records"])
        self.assertEqual([20, 21], report["ambiguous_item_ids"])

    def test_same_output_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            source.write_text(json.dumps(self.source([self.item()])), encoding="utf-8")
            target = root / "out.json"
            target.write_bytes(b"previous")
            with self.assertRaises(ValueError):
                self.importer.import_catalog(source, target, root / "." / "out.json", "a" * 40, hashlib.sha256(source.read_bytes()).hexdigest())
            self.assertEqual(b"previous", target.read_bytes())

    def test_second_replacement_failure_restores_old_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output, report = (root / n for n in ("source.json", "catalog.json", "report.json"))
            source.write_text(json.dumps(self.source([self.item()])), encoding="utf-8")
            output.write_bytes(b"old catalog")
            report.write_bytes(b"old report")
            original_replace = self.importer.os.replace
            calls = 0
            def fail_second(src, dst):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise OSError("injected second replacement failure")
                return original_replace(src, dst)
            with patch.object(self.importer.os, "replace", side_effect=fail_second):
                with self.assertRaisesRegex(OSError, "injected"):
                    self.importer.import_catalog(source, output, report, "a" * 40, hashlib.sha256(source.read_bytes()).hexdigest())
            self.assertEqual(b"old catalog", output.read_bytes())
            self.assertEqual(b"old report", report.read_bytes())

    def test_anilist_namespace_must_be_explicitly_anime(self):
        source = self.source([self.item()])
        source["siteMeta"]["aniList"]["urlTemplate"] = "https://anilist.co/manga/{{id}}"
        with self.assertRaises(ValueError):
            self.importer.build_catalog(source, "a" * 40, "b" * 64)

    def test_output_is_deterministic_and_numerically_sorted(self):
        a, _ = self.convert([self.item("235"), self.item("20")])
        b, _ = self.convert([self.item("20"), self.item("235")])
        self.assertEqual(a, b)
        self.assertEqual([20, 235], [r["key"]["id"] for r in a["records"]])

    def test_checksum_failure_does_not_overwrite_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            source.write_text(json.dumps(self.source([self.item()])), encoding="utf-8")
            output = root / "catalog.json"
            output.write_text("previous catalog", encoding="utf-8")
            with self.assertRaises(ValueError):
                self.importer.import_catalog(source, output, root / "report.json", "a" * 40, "0" * 64)
            self.assertEqual("previous catalog", output.read_text(encoding="utf-8"))

    def test_successful_import_records_input_hash_and_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            source.write_text(json.dumps(self.source([self.item()])), encoding="utf-8")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            self.importer.import_catalog(source, root / "catalog.json", root / "report.json", "a" * 40, digest)
            result = json.loads((root / "catalog.json").read_text(encoding="utf-8"))
            self.assertEqual(digest, result["sources"][0]["sha256"])
            self.assertEqual("a" * 40, result["sources"][0]["revision"])
            self.assertEqual("CC-BY-4.0", result["sources"][0]["license"])
            report = json.loads((root / "report.json").read_text(encoding="utf-8"))
            self.assertEqual(hashlib.sha256((root / "catalog.json").read_bytes()).hexdigest(), report["catalog_sha256"])

    def test_invalid_or_empty_source_never_publishes(self):
        for value in [[], {}, {"items": []}, self.source([])]:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    self.importer.build_catalog(value, "a" * 40, "b" * 64)


if __name__ == "__main__":
    unittest.main()
