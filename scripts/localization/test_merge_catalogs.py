import copy
import unittest

from merge_catalogs import merge_catalogs


def catalog(source="anime", kind="MEDIA", media_id=20, media_kind="ANIME", name="\u4f5c\u54c1"):
    record = {"key": {"platform": "ANILIST", "kind": kind, "id": media_id},
              "displayName": name, "aliases": [], "sourceId": source}
    if media_kind:
        record["mediaKind"] = media_kind
    return {"schemaVersion": 1, "version": source, "sources": [
        {"id": source, "revision": "rev", "url": "https://example.org", "license": "CC0-1.0", "sha256": "a" * 64}
    ], "records": [record]}


class CatalogMergeTest(unittest.TestCase):
    def test_search_alias_validation(self):
        component = catalog()
        component["searchAliases"] = [{"aniListId": 20, "names": ["x", "x"], "sourceId": "anime"}]
        merged, _ = merge_catalogs([component], "v")
        self.assertEqual(component["searchAliases"], merged["searchAliases"])
        for change in (lambda a: a.update(aniListId="20"), lambda a: a.update(sourceId="missing"),
                       lambda a: a.update(names=[]), lambda a: a.update(names=[" "]),
                       lambda a: a.update(mediaKind="MANGA")):
            bad = copy.deepcopy(component)
            change(bad["searchAliases"][0])
            with self.assertRaises(ValueError):
                merge_catalogs([bad], "v")
        bad = copy.deepcopy(component)
        bad["searchAliases"].append(copy.deepcopy(bad["searchAliases"][0]))
        with self.assertRaises(ValueError):
            merge_catalogs([bad], "v")

    def test_optional_links_preserve_typed_identity_without_title_record(self):
        component = catalog()
        component["links"] = [{"mediaKind": "ANIME", "aniListId": 21, "bangumiSubjectId": 3425, "sourceId": "anime"}]
        merged, report = merge_catalogs([component], "combined")
        self.assertEqual(component["links"], merged["links"])
        self.assertEqual(1, report["link_count"])
        for mutation in [
            lambda link: link.update(aniListId="21"),
            lambda link: link.update(mediaKind="STAFF"),
            lambda link: link.update(sourceId="missing"),
        ]:
            bad = copy.deepcopy(component)
            mutation(bad["links"][0])
            with self.assertRaises(ValueError):
                merge_catalogs([bad], "combined")
        duplicate_target = copy.deepcopy(component)
        duplicate_target["links"].append({"mediaKind": "ANIME", "aniListId": 22, "bangumiSubjectId": 3425, "sourceId": "anime"})
        with self.assertRaises(ValueError):
            merge_catalogs([duplicate_target], "combined")

    def test_unknown_fields_are_rejected_at_every_catalog_level(self):
        for path in [(), ("sources", 0), ("records", 0), ("records", 0, "key")]:
            value = catalog()
            target = value
            for part in path:
                target = target[part]
            target["unexpected"] = "x"
            with self.subTest(path=path), self.assertRaises(ValueError):
                merge_catalogs([value], "v1")

    def test_namespace_separation_and_anime_data_preserved(self):
        anime = catalog()
        original = copy.deepcopy(anime)
        staff = catalog("staff", "STAFF", 20, None, "\u5236\u4f5c\u4eba\u5458")
        character = catalog("characters", "CHARACTER", 20, None, "\u89d2\u8272")
        merged, report = merge_catalogs([anime, staff, character], "combined")
        self.assertEqual(3, len(merged["records"]))
        self.assertEqual(original, anime)
        self.assertIn(original["records"][0], merged["records"])
        self.assertEqual(3, report["record_count"])

    def test_conflicting_cross_source_identity_is_rejected_not_overwritten(self):
        first, second = catalog(), catalog("second", name="\u5176\u4ed6\u4f5c\u54c1")
        with self.assertRaises(ValueError):
            merge_catalogs([first, second], "combined")

    def test_same_source_revision_must_not_describe_different_data(self):
        first = catalog()
        second = catalog(media_id=21)
        second["sources"][0]["sha256"] = "b" * 64
        with self.assertRaises(ValueError):
            merge_catalogs([first, second], "combined")

    def test_identical_component_merge_is_idempotent(self):
        source = catalog()
        combined, _ = merge_catalogs([source, source], "combined")
        self.assertEqual(1, len(combined["sources"]))
        self.assertEqual(1, len(combined["records"]))

    def test_equal_labels_retain_distinct_raw_tag_keys(self):
        tag_table = {"schemaVersion": 1, "version": "tags-v1", "entries": [
            {"rawName": "A", "displayName": "\u540c\u540d"},
            {"rawName": "B", "displayName": "\u540c\u540d"},
        ]}
        merged, report = merge_catalogs([catalog()], "combined", tag_table, "b" * 64)
        self.assertEqual(["A", "B"], [t["rawName"] for t in merged["tags"]])
        self.assertEqual(2, report["tag_count"])
        self.assertEqual("anihyou-tag-labels", merged["tags"][0]["sourceId"])

    def test_duplicate_and_placeholder_tag_keys_rejected(self):
        for entries in [
            [{"rawName": "A", "displayName": "a"}, {"rawName": "A", "displayName": "b"}],
            [{"rawName": "{{PERSON_unknown}}", "displayName": "unknown"}],
            [{"rawName": "A", "displayName": " "}],
        ]:
            with self.subTest(entries=entries):
                with self.assertRaises(ValueError):
                    merge_catalogs([catalog()], "combined", {"schemaVersion": 1, "version": "tags", "entries": entries}, "b" * 64)

    def test_invalid_identity_types_or_missing_provenance_rejected(self):
        for mutation in [
            lambda x: x["records"][0]["key"].update(id="20"),
            lambda x: x["records"][0]["key"].update(kind="UNKNOWN"),
            lambda x: x["records"][0].update(sourceId="missing"),
            lambda x: x["records"][0].update(mediaKind=None),
            lambda x: x.update(schemaVersion="1"),
            lambda x: x["sources"][0].update(sha256="bad"),
        ]:
            value = catalog()
            mutation(value)
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    merge_catalogs([value], "combined")

    def test_merge_order_does_not_change_output(self):
        components = [catalog(), catalog("staff", "STAFF", 20, None)]
        self.assertEqual(merge_catalogs(components, "v1")[0], merge_catalogs(list(reversed(components)), "v1")[0])


if __name__ == "__main__":
    unittest.main()
