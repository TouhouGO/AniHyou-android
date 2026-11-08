#!/usr/bin/env python3
"""Offline conversion of a saved, checksum-pinned Wikidata label query."""

import argparse
from collections import defaultdict
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re

from import_bangumi_data import json_bytes, publish_pair
from strict_json import loads


PROPERTIES = {"P11227": ("STAFF", None), "P11736": ("CHARACTER", None), "P8731": ("MEDIA", "MANGA")}
LANGUAGES = {"zh-hans": 0, "zh-cn": 1}


def build_catalog(payload, property_id, fetched_at, sha256, query_limit, identity_payload, identity_limit):
    if property_id not in PROPERTIES:
        raise ValueError("Unsupported exact AniList identifier property")
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise ValueError("Missing source SHA-256")
    timestamp = datetime.fromisoformat(fetched_at.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        raise ValueError("Fetched time must include a timezone")
    rows = payload.get("results", {}).get("bindings") if isinstance(payload, dict) else None
    if not isinstance(rows, list) or not rows or "error" in payload or "errors" in payload:
        raise ValueError("Missing, empty or failed SPARQL result")
    if not isinstance(query_limit, int) or isinstance(query_limit, bool) or query_limit <= 0 or len(rows) >= query_limit:
        raise ValueError("Result may be truncated by the query limit")
    identity_rows = identity_payload.get("results", {}).get("bindings") if isinstance(identity_payload, dict) else None
    if (not isinstance(identity_rows, list) or not identity_rows or "error" in identity_payload or "errors" in identity_payload
            or type(identity_limit) is not int or identity_limit <= 0 or len(identity_rows) >= identity_limit):
        raise ValueError("Missing, failed or truncated identity index")
    claims = defaultdict(set)
    entity_claims = defaultdict(set)
    for row in identity_rows:
        if not isinstance(row, dict) or not isinstance(row.get("item"), dict) or not isinstance(row.get("id"), dict):
            raise ValueError("Invalid identity binding")
        item, external_id = row["item"], row["id"]
        match = re.fullmatch(r"https?://www\.wikidata\.org/entity/(Q[1-9][0-9]*)", item.get("value", ""))
        value = external_id.get("value", "")
        if item.get("type") != "uri" or match is None or external_id.get("type") != "literal" or not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]*", value) or int(value) > 2147483647:
            raise ValueError("Invalid identity claim")
        claims[int(value)].add(match.group(1))
        entity_claims[match.group(1)].add(int(value))
    by_id = defaultdict(list)
    by_entity = defaultdict(set)
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("Invalid SPARQL binding")
        item, external_id, label = (row.get(name, {}) for name in ("item", "id", "label"))
        if not all(isinstance(value, dict) for value in (item, external_id, label)):
            raise ValueError("Invalid binding values")
        match = re.fullmatch(r"https?://www\.wikidata\.org/entity/(Q[1-9][0-9]*)", item.get("value", ""))
        value = external_id.get("value", "")
        language = label.get("xml:lang", "").lower()
        name = label.get("value")
        if item.get("type") != "uri" or match is None:
            raise ValueError("Missing Wikidata entity identity")
        if external_id.get("type") != "literal" or not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]*", value) or int(value) > 2147483647:
            raise ValueError("Invalid exact AniList ID")
        if label.get("type") != "literal" or language not in LANGUAGES or not isinstance(name, str) or not name.strip():
            raise ValueError("Not an explicit simplified Chinese label")
        entity = match.group(1)
        identifier = int(value)
        by_id[identifier].append((entity, language, name.strip()))
        by_entity[entity].add(identifier)

    ambiguous = set().union(*(ids for ids in entity_claims.values() if len(ids) > 1)) if entity_claims else set()
    source_id = f"wikidata-{property_id.lower()}"
    kind, media_kind = PROPERTIES[property_id]
    records, accepted_entities, conflicting_ids, conflicting_labels = [], {}, [], []
    for identifier, values in sorted(by_id.items()):
        qids = {value[0] for value in values}
        if not qids <= claims.get(identifier, set()):
            raise ValueError("Identity index does not contain every label claim")
        if len(claims[identifier]) != 1 or len(qids) != 1:
            conflicting_ids.append(identifier)
            continue
        if identifier in ambiguous:
            continue
        best_rank = min(LANGUAGES[value[1]] for value in values)
        preferred = {value[2] for value in values if LANGUAGES[value[1]] == best_rank}
        if len(preferred) != 1:
            conflicting_labels.append(identifier)
            continue
        display_name = next(iter(preferred))
        aliases = sorted({value[2] for value in values} - {display_name})
        record = {"key": {"platform": "ANILIST", "kind": kind, "id": identifier},
                  "displayName": display_name, "aliases": aliases, "sourceId": source_id}
        if media_kind:
            record["mediaKind"] = media_kind
        records.append(record)
        accepted_entities[str(identifier)] = {"qid": next(iter(qids)), "languages": sorted({value[1] for value in values})}
    report = {"property": property_id, "fetched_at": fetched_at, "source_sha256": sha256,
              "query_limit": query_limit, "input_rows": len(rows), "identity_rows": len(identity_rows), "accepted_records": len(records),
              "conflicting_ids": conflicting_ids, "identity_conflicting_ids": sorted(identifier for identifier, items in claims.items() if len(items) > 1), "ambiguous_ids": sorted(ambiguous),
              "conflicting_labels": conflicting_labels, "entities": accepted_entities}
    catalog = {"schemaVersion": 1, "version": f"{source_id}-{sha256[:12]}",
               "sources": [{"id": source_id, "revision": f"{fetched_at}:{sha256}",
                            "url": f"https://www.wikidata.org/wiki/Property:{property_id}",
                            "license": "CC0-1.0", "sha256": sha256}], "records": records}
    return catalog, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--property", choices=PROPERTIES, required=True)
    parser.add_argument("--fetched-at", required=True)
    parser.add_argument("--query-limit", type=int, required=True)
    parser.add_argument("--identity-input", type=Path, required=True)
    parser.add_argument("--identity-sha256", required=True)
    parser.add_argument("--identity-limit", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    raw = args.input.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != args.sha256:
        raise ValueError("Source checksum mismatch")
    identity_raw = args.identity_input.read_bytes()
    if hashlib.sha256(identity_raw).hexdigest() != args.identity_sha256:
        raise ValueError("Identity checksum mismatch")
    catalog, report = build_catalog(loads(raw), args.property, args.fetched_at, digest, args.query_limit, loads(identity_raw), args.identity_limit)
    report["identity_sha256"] = args.identity_sha256
    if not catalog["records"]:
        raise ValueError("No unambiguous source records accepted")
    catalog_bytes = json_bytes(catalog)
    report["catalog_sha256"] = hashlib.sha256(catalog_bytes).hexdigest()
    publish_pair(args.output, catalog_bytes, args.report, json_bytes(report))
    print(json.dumps({key: value for key, value in report.items() if key != "entities"}, sort_keys=True))


if __name__ == "__main__":
    main()
