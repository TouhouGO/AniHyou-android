#!/usr/bin/env python3
"""Rebuild the complete bundled catalog from retained offline source evidence."""

import argparse
import gzip
import hashlib
import json
from pathlib import Path

from import_bangumi_data import build_catalog as import_anime
from import_bangumi_data import json_bytes, publish_pair
from import_bangumi_links import build_links
from import_wikidata import build_catalog as import_wikidata, PROPERTIES
from import_soruly import build_catalog as import_soruly
from strict_json import loads
from merge_catalogs import merge_catalogs


def read_source(base, entry):
    path = (base / entry["sourceFile"]).resolve()
    raw = path.read_bytes()
    if path.suffix == ".gz":
        raw = gzip.decompress(raw)
    if hashlib.sha256(raw).hexdigest() != entry["sha256"]:
        raise ValueError(f"Checksum mismatch for {path.name}")
    return loads(raw)


def verify_query(base, entry, property_id, identity):
    limit = entry.get("queryLimit")
    if type(limit) is not int or limit <= 0:
        raise ValueError("Invalid query limit")
    if entry.get("property") != property_id:
        raise ValueError("Identity property does not match the label property")
    raw = (base / entry["queryFile"]).read_bytes()
    if hashlib.sha256(raw).hexdigest() != entry.get("querySha256"):
        raise ValueError("Query checksum mismatch")
    selection = "?item ?id" if identity else "?item ?id ?label"
    label = "" if identity else '  ?item rdfs:label ?label .\n  FILTER(LANG(?label) IN ("zh-hans", "zh-cn"))\n'
    expected = (f'SELECT {selection} WHERE {{\n  hint:Query hint:optimizer "None" .\n'
                f'  ?item wdt:{property_id} ?id .\n{label}}}\nLIMIT {limit}\n').encode()
    if raw != expected:
        raise ValueError("Unexpected query template, property or limit")


def rebuild(manifest_path, output, report_path):
    manifest_path = Path(manifest_path)
    base = manifest_path.parent
    manifest = loads(manifest_path.read_text(encoding="utf-8"))
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported source manifest")
    entries = manifest.get("wikidata")
    if not isinstance(entries, list) or len(entries) != len(PROPERTIES) or any(not isinstance(e, dict) for e in entries) or {e.get("property") for e in entries} != set(PROPERTIES):
        raise ValueError("Exactly one component for every required Wikidata property is required")
    anime_source = manifest["anime"]
    anime_payload = read_source(base, anime_source)
    anime, anime_report = import_anime(anime_payload, anime_source["revision"], anime_source["sha256"])
    anime["links"], link_report = build_links(anime_payload)
    anime_report["links"] = link_report
    if not anime["records"]:
        raise ValueError("Anime source produced an empty catalog")
    components = [anime]
    source_reports = {"bangumi-data": anime_report}
    for entry in entries:
        if entry.get("httpStatus") != 200:
            raise ValueError("Failed acquisition is not a catalog component")
        property_id = entry["property"]
        verify_query(base, entry, property_id, False)
        identity = entry.get("identity")
        if (not isinstance(identity, dict) or identity.get("httpStatus") != 200 or identity.get("usable") is not True or identity.get("atLimit") is not False):
            raise ValueError("Missing or failed identity acquisition")
        verify_query(base, identity, property_id, True)
        identity_payload = read_source(base, identity)
        payload = read_source(base, entry)
        component, report = import_wikidata(payload, entry["property"], entry["fetchedAt"], entry["sha256"], entry["queryLimit"], identity_payload, identity["queryLimit"])
        report.update(identity_sha256=identity["sha256"], query_sha256=entry["querySha256"], identity_query_sha256=identity["querySha256"])
        if report["input_rows"] != entry["rowCount"] or report["identity_rows"] != identity["rowCount"] or not component["records"]:
            raise ValueError("Source count mismatch or empty entity component")
        components.append(component)
        source_reports[entry["property"]] = report
    alias_entry = manifest["searchAliases"]
    alias_payload = read_source(base, alias_entry)
    manga_report = source_reports["P8731"]
    excluded = set(anime_report["conflicting_ids"]) | set(anime_report["ambiguous_item_ids"])
    excluded |= set(link_report["shared_anilist_ids"]) | set(link_report["ambiguous_anilist_ids"])
    excluded |= set(manga_report["conflicting_ids"]) | set(manga_report["identity_conflicting_ids"]) | set(manga_report["ambiguous_ids"])
    alias_component, alias_report = import_soruly(alias_payload, alias_entry["revision"], alias_entry["sha256"], components, excluded)
    if not alias_component["searchAliases"]:
        raise ValueError("Search alias source produced no aliases")
    components.append(alias_component)
    source_reports["soruly"] = alias_report
    tags_raw = (base / manifest["tags"]).read_bytes()
    catalog, report = merge_catalogs(components, manifest["version"], loads(tags_raw), hashlib.sha256(tags_raw).hexdigest())
    catalog_bytes = json_bytes(catalog)
    report["catalog_sha256"] = hashlib.sha256(catalog_bytes).hexdigest()
    report["source_reports"] = source_reports
    publish_pair(output, catalog_bytes, report_path, json_bytes(report))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("data/localization/sources/catalog-sources.json"))
    parser.add_argument("--output", type=Path, default=Path("core/localization/src/main/resources/localization/catalog.json"))
    parser.add_argument("--report", type=Path, default=Path("docs/localization/catalog-report.json"))
    args = parser.parse_args()
    report = rebuild(args.manifest, args.output, args.report)
    print(json.dumps({k: v for k, v in report.items() if k != "source_reports"}, sort_keys=True))


if __name__ == "__main__":
    main()
