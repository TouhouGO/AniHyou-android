import copy
import unittest

from import_bangumi_links import build_links


def item(anilist="20", bangumi="3425", title="Original title"):
    return {"title": title, "type": "tv", "sites": [
        {"site": "aniList", "id": anilist}, {"site": "bangumi", "id": bangumi}
    ]}


def payload(items):
    return {"siteMeta": {
        "aniList": {"urlTemplate": "https://anilist.co/anime/{{id}}"},
        "bangumi": {"urlTemplate": "https://bangumi.tv/subject/{{id}}"},
    }, "items": items}


class BangumiLinksImportTest(unittest.TestCase):
    def test_explicit_link_does_not_require_a_chinese_title(self):
        links, report = build_links(payload([item()]))
        self.assertEqual([{"mediaKind": "ANIME", "aniListId": 20, "bangumiSubjectId": 3425, "sourceId": "bangumi-data"}], links)
        self.assertEqual(1, report["accepted_links"])

    def test_duplicate_identical_sites_in_one_item_are_deduplicated(self):
        value = item()
        value["sites"] += copy.deepcopy(value["sites"])
        self.assertEqual(1, len(build_links(payload([value]))[0]))

    def test_many_to_one_and_one_to_many_are_quarantined(self):
        for rows in [[item(), item("21", "3425")], [item(), item("20", "3426")], [item(), item()]]:
            with self.subTest(rows=rows):
                self.assertEqual([], build_links(payload(rows))[0])

    def test_one_item_with_multiple_ids_is_not_arbitrarily_joined(self):
        value = item()
        value["sites"].append({"site": "bangumi", "id": "3426"})
        links, report = build_links(payload([value, item("21", "3426")]))
        self.assertEqual([], links)
        self.assertIn(20, report["ambiguous_anilist_ids"])

    def test_competing_item_without_other_id_still_blocks_mapping(self):
        competitor = item()
        competitor["sites"] = [{"site": "aniList", "id": "20"}]
        self.assertEqual([], build_links(payload([item(), competitor]))[0])

    def test_wrong_namespace_or_invalid_ids_fail(self):
        for key, value in [("aniList", "https://anilist.co/manga/{{id}}"), ("bangumi", "https://bangumi.tv/person/{{id}}")]:
            data = payload([item()])
            data["siteMeta"][key]["urlTemplate"] = value
            with self.assertRaises(ValueError):
                build_links(data)
        for invalid in ["0", "-1", True, "2147483648", "x"]:
            with self.assertRaises(ValueError):
                build_links(payload([item(bangumi=invalid)]))

    def test_order_is_numeric_and_input_is_not_modified(self):
        rows = [item("235", "899"), item()]
        original = copy.deepcopy(rows)
        links, _ = build_links(payload(rows))
        self.assertEqual([20, 235], [link["aniListId"] for link in links])
        self.assertEqual(links, build_links(payload(list(reversed(rows))))[0])
        self.assertEqual(original, rows)


if __name__ == "__main__":
    unittest.main()
