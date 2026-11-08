import unittest

from import_wikidata import build_catalog


def binding(qid="Q1", media_id="7", name="\u4e2d\u6587", language="zh-hans"):
    return {
        "item": {"type": "uri", "value": f"http://www.wikidata.org/entity/{qid}"},
        "id": {"type": "literal", "value": media_id},
        "label": {"type": "literal", "xml:lang": language, "value": name},
    }


class WikidataImportTest(unittest.TestCase):
    def test_unlabelled_identity_competitor_is_quarantined(self):
        rows = [binding(), binding(qid="Q3", media_id="8")]
        identities = [{k: v for k, v in row.items() if k != "label"} for row in rows]
        identities.append({k: v for k, v in binding(qid="Q2").items() if k != "label"})
        catalog, report = build_catalog({"results": {"bindings": rows}}, "P11227", "2026-11-05T00:00:00Z", "a" * 64, 100,
                                        {"results": {"bindings": identities}}, 100)
        self.assertEqual([8], [r["key"]["id"] for r in catalog["records"]])
        self.assertEqual([7], report["conflicting_ids"])

    def test_missing_or_truncated_identity_is_rejected(self):
        payload = {"results": {"bindings": [binding()]}}
        identity = {"results": {"bindings": [{k: v for k, v in binding(qid="Q2").items() if k != "label"}]}}
        for index, limit in [({}, 100), (identity, 1), (identity, 100)]:
            with self.subTest(index=index, limit=limit), self.assertRaises(ValueError):
                build_catalog(payload, "P11227", "2026-11-05T00:00:00Z", "a" * 64, 100, index, limit)

    def convert(self, rows, property_id="P11227", limit=100):
        return build_catalog(
            {"head": {"vars": ["item", "id", "label"]}, "results": {"bindings": rows}},
            property_id, "2026-11-05T00:00:00Z", "a" * 64, limit,
            {"results": {"bindings": [{k: v for k, v in row.items() if k != "label"} for row in rows]}}, 1000,
        )

    def test_external_properties_define_identity_without_id_offsets(self):
        expected = {"P11227": ("STAFF", None), "P11736": ("CHARACTER", None), "P8731": ("MEDIA", "MANGA")}
        for property_id, (kind, media_kind) in expected.items():
            record = self.convert([binding(media_id="86")], property_id)[0]["records"][0]
            self.assertEqual({"platform": "ANILIST", "kind": kind, "id": 86}, record["key"])
            self.assertEqual(media_kind, record.get("mediaKind"))

    def test_language_priority_aliases_and_row_order_are_deterministic(self):
        rows = [binding(name="\u522b\u540d", language="zh-cn"), binding(name="\u4e2d\u6587", language="zh-hans")]
        first = self.convert(rows)[0]
        self.assertEqual(first, self.convert(list(reversed(rows)))[0])
        self.assertEqual("\u4e2d\u6587", first["records"][0]["displayName"])
        self.assertEqual(["\u522b\u540d"], first["records"][0]["aliases"])

    def test_different_entities_claiming_one_id_are_quarantined(self):
        catalog, report = self.convert([binding(), binding(qid="Q2"), binding(qid="Q3", media_id="8")])
        self.assertEqual([8], [r["key"]["id"] for r in catalog["records"]])
        self.assertEqual([7], report["conflicting_ids"])

    def test_one_entity_claiming_multiple_ids_is_quarantined(self):
        catalog, report = self.convert([binding(), binding(media_id="8"), binding(qid="Q2", media_id="9")])
        self.assertEqual([9], [r["key"]["id"] for r in catalog["records"]])
        self.assertEqual([7, 8], report["ambiguous_ids"])

    def test_duplicate_rows_do_not_grow_records(self):
        catalog, _ = self.convert([binding(), binding()])
        self.assertEqual(1, len(catalog["records"]))

    def test_conflicting_preferred_labels_do_not_pick_arbitrarily(self):
        catalog, report = self.convert([binding(name="A"), binding(name="B")])
        self.assertEqual([], catalog["records"])
        self.assertEqual([7], report["conflicting_labels"])

    def test_truncated_empty_or_error_results_are_not_valid_data(self):
        with self.assertRaises(ValueError):
            self.convert([binding()], limit=1)
        with self.assertRaises(ValueError):
            self.convert([])
        with self.assertRaises(ValueError):
            build_catalog({"error": "timeout"}, "P11227", "time", "a" * 64, 100, {}, 100)

    def test_invalid_ids_qids_properties_languages_are_rejected(self):
        for row in [binding(media_id="0"), binding(media_id="-1"), binding(media_id="1.0"), binding(media_id="2147483648"), binding(qid="wrong"), binding(language="ja"), binding(name=" ")]:
            with self.subTest(row=row):
                with self.assertRaises(ValueError):
                    self.convert([row])
        with self.assertRaises(ValueError):
            self.convert([binding()], property_id="P1")


if __name__ == "__main__":
    unittest.main()
