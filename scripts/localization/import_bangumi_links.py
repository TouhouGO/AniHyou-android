"""Extract unambiguous paired anime identifiers from a pinned bangumi-data input."""

from collections import defaultdict
import re


def build_links(source):
    namespaces = {"aniList": "https://anilist.co/anime/{{id}}", "bangumi": "https://bangumi.tv/subject/{{id}}"}
    if not isinstance(source, dict) or not isinstance(source.get("items"), list) or not source["items"]:
        raise ValueError("Expected nonempty bangumi-data items")
    for platform, expected in namespaces.items():
        if source.get("siteMeta", {}).get(platform, {}).get("urlTemplate") != expected:
            raise ValueError("Unexpected platform namespace for paired links")
    by_anilist, by_bangumi = defaultdict(set), defaultdict(set)
    items, ambiguous_anilist, ambiguous_bangumi = [], set(), set()
    for index, item in enumerate(source["items"]):
        if not isinstance(item, dict) or item.get("type") not in {"tv", "web", "movie", "ova"}:
            raise ValueError("Unsupported source media kind")
        ids = {platform: set() for platform in namespaces}
        if not isinstance(item.get("sites", []), list):
            raise ValueError("Invalid sites")
        for site in item.get("sites", []):
            if not isinstance(site, dict):
                raise ValueError("Invalid site")
            platform = site.get("site")
            if platform not in ids:
                continue
            raw_id = site.get("id")
            if isinstance(raw_id, bool) or not re.fullmatch(r"[1-9][0-9]*", str(raw_id)) or int(raw_id) > 2147483647:
                raise ValueError("Invalid platform ID")
            ids[platform].add(int(raw_id))
        for identifier in ids["aniList"]:
            by_anilist[identifier].add(index)
        for identifier in ids["bangumi"]:
            by_bangumi[identifier].add(index)
        if len(ids["aniList"]) > 1 or len(ids["bangumi"]) > 1:
            ambiguous_anilist.update(ids["aniList"])
            ambiguous_bangumi.update(ids["bangumi"])
        items.append(ids)

    links = []
    for ids in items:
        if len(ids["aniList"]) != 1 or len(ids["bangumi"]) != 1:
            continue
        anilist, bangumi = next(iter(ids["aniList"])), next(iter(ids["bangumi"]))
        if anilist in ambiguous_anilist or bangumi in ambiguous_bangumi:
            continue
        if len(by_anilist[anilist]) != 1 or len(by_bangumi[bangumi]) != 1:
            continue
        links.append({"mediaKind": "ANIME", "aniListId": anilist, "bangumiSubjectId": bangumi, "sourceId": "bangumi-data"})
    links.sort(key=lambda link: link["aniListId"])
    report = {"accepted_links": len(links), "input_items": len(items),
              "shared_anilist_ids": sorted(key for key, owners in by_anilist.items() if len(owners) > 1),
              "shared_bangumi_ids": sorted(key for key, owners in by_bangumi.items() if len(owners) > 1),
              "ambiguous_anilist_ids": sorted(ambiguous_anilist), "ambiguous_bangumi_ids": sorted(ambiguous_bangumi)}
    return links, report
