#!/usr/bin/env python3
"""Merge validated offline catalog components without choosing conflicting IDs."""

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re

from import_bangumi_data import json_bytes, publish_pair
from strict_json import loads


def require_text(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError("Expected nonblank text")
    return value


def require_digest(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", value):
        raise ValueError("Expected a SHA-256 digest")
    return value


def exact_fields(value, required, optional=()):
    if not isinstance(value, dict) or not set(required) <= value.keys() or value.keys() - set(required) - set(optional):
        raise ValueError("Unexpected or missing object fields")


def merge_catalogs(components, version, tag_table=None, tag_sha256=None):
    require_text(version)
    sources, records, tags, links, link_targets, aliases = {}, {}, {}, {}, {}, {}
    component_versions = []
    for component in components:
        if not isinstance(component, dict) or type(component.get("schemaVersion")) is not int or component["schemaVersion"] != 1:
            raise ValueError("Unsupported catalog schema")
        exact_fields(component, {"schemaVersion", "version", "sources", "records"}, {"tags", "links", "searchAliases"})
        component_versions.append(require_text(component.get("version")))
        for field in ("sources", "records"):
            if not isinstance(component.get(field), list):
                raise ValueError(f"Expected {field} array")
        local_sources = set()
        for source in component["sources"]:
            if not isinstance(source, dict):
                raise ValueError("Invalid source")
            exact_fields(source, {"id", "revision", "url", "license", "sha256"})
            for field in ("id", "revision", "url", "license"):
                require_text(source.get(field))
            require_digest(source.get("sha256"))
            identifier = source["id"]
            if identifier in local_sources:
                raise ValueError("Duplicate source definition")
            local_sources.add(identifier)
            if identifier in sources and sources[identifier] != source:
                raise ValueError("A source ID describes different revisions")
            sources[identifier] = copy.deepcopy(source)
        local_keys = set()
        for record in component["records"]:
            if not isinstance(record, dict) or not isinstance(record.get("key"), dict):
                raise ValueError("Invalid record")
            exact_fields(record, {"key", "displayName", "aliases", "sourceId"}, {"mediaKind"})
            key = record["key"]
            exact_fields(key, {"platform", "kind", "id"})
            if key.get("platform") != "ANILIST" or key.get("kind") not in {"MEDIA", "CHARACTER", "STAFF"}:
                raise ValueError("Invalid entity namespace")
            if type(key.get("id")) is not int or not 0 < key["id"] <= 2147483647:
                raise ValueError("Invalid entity ID")
            require_text(record.get("displayName"))
            if record.get("sourceId") not in local_sources:
                raise ValueError("Record references an undeclared source")
            if not isinstance(record.get("aliases"), list):
                raise ValueError("Invalid alias array")
            for alias in record["aliases"]:
                require_text(alias)
            if key["kind"] == "MEDIA":
                if record.get("mediaKind") not in {"ANIME", "MANGA"}:
                    raise ValueError("Media kind is required")
            elif record.get("mediaKind") is not None:
                raise ValueError("Only media records may have a media kind")
            identity = (key["platform"], key["kind"], key["id"])
            if identity in local_keys:
                raise ValueError("Duplicate identity within a component")
            local_keys.add(identity)
            if identity in records and records[identity] != record:
                raise ValueError(f"Conflicting catalog identity: {identity}")
            records[identity] = copy.deepcopy(record)
        local_tags = component.get("tags", [])
        if not isinstance(local_tags, list):
            raise ValueError("Invalid tag array")
        seen_tags = set()
        for tag in local_tags:
            validate_tag(tag, local_sources)
            raw = tag["rawName"]
            if raw in seen_tags or (raw in tags and tags[raw] != tag):
                raise ValueError("Conflicting raw tag key")
            seen_tags.add(raw)
            tags[raw] = copy.deepcopy(tag)
        local_links = component.get("links", [])
        if not isinstance(local_links, list):
            raise ValueError("Invalid links array")
        local_link_keys = set()
        for link in local_links:
            exact_fields(link, {"mediaKind", "aniListId", "bangumiSubjectId", "sourceId"})
            if link["mediaKind"] not in {"ANIME", "MANGA"} or link["sourceId"] not in local_sources:
                raise ValueError("Invalid link kind or source")
            for field in ("aniListId", "bangumiSubjectId"):
                if type(link[field]) is not int or not 0 < link[field] <= 2147483647:
                    raise ValueError("Invalid link identity")
            identity = (link["mediaKind"], link["aniListId"])
            target = (link["mediaKind"], link["bangumiSubjectId"])
            if identity in local_link_keys or (identity in links and links[identity] != link):
                raise ValueError("Duplicate or conflicting media link")
            if target in link_targets and link_targets[target] != identity:
                raise ValueError("Conflicting Bangumi target")
            local_link_keys.add(identity)
            links[identity] = copy.deepcopy(link)
            link_targets[target] = identity

        local_aliases = component.get("searchAliases", [])
        if not isinstance(local_aliases, list):
            raise ValueError("Invalid search aliases")
        for alias in local_aliases:
            exact_fields(alias, {"aniListId", "names", "sourceId"}, {"mediaKind"})
            identifier = alias["aniListId"]
            if type(identifier) is not int or not 0 < identifier <= 2147483647 or alias["sourceId"] not in local_sources:
                raise ValueError("Invalid search alias identity or source")
            if not isinstance(alias["names"], list) or not alias["names"]:
                raise ValueError("Empty search alias names")
            for name in alias["names"]:
                require_text(name)
            if alias.get("mediaKind") not in {None, "ANIME", "MANGA"}:
                raise ValueError("Invalid search alias kind")
            key = (alias["sourceId"], identifier)
            if key in aliases:
                raise ValueError("Duplicate search alias")
            aliases[key] = copy.deepcopy(alias)

    evidence = {}
    for record in records.values():
        if record["key"]["kind"] == "MEDIA":
            evidence.setdefault(record["key"]["id"], set()).add(record["mediaKind"])
    for link in links.values():
        evidence.setdefault(link["aniListId"], set()).add(link["mediaKind"])
    for alias in aliases.values():
        if alias.get("mediaKind"):
            evidence.setdefault(alias["aniListId"], set()).add(alias["mediaKind"])
    if any(len(evidence.get(alias["aniListId"], set())) > 1 for alias in aliases.values()):
        raise ValueError("Conflicting search alias media kind")

    if tag_table is not None:
        if not isinstance(tag_table, dict) or type(tag_table.get("schemaVersion")) is not int or tag_table["schemaVersion"] != 1:
            raise ValueError("Unsupported local tag schema")
        exact_fields(tag_table, {"schemaVersion", "version", "entries"})
        require_text(tag_table.get("version"))
        if not isinstance(tag_table.get("entries"), list) or not tag_table["entries"]:
            raise ValueError("Empty local tag table")
        source_id = "anihyou-tag-labels"
        if source_id in sources:
            raise ValueError("Local tag source collides with another component")
        sources[source_id] = {"id": source_id, "revision": tag_table["version"],
                              "url": "https://github.com/TouhouGO/AniHyou-android",
                              "license": "GPL-3.0-only", "sha256": require_digest(tag_sha256)}
        for value in tag_table["entries"]:
            if not isinstance(value, dict):
                raise ValueError("Invalid local tag entry")
            exact_fields(value, {"rawName", "displayName"})
            tag = {**value, "sourceId": source_id}
            validate_tag(tag, {source_id})
            if tag["rawName"] in tags:
                raise ValueError("Duplicate raw tag key")
            tags[tag["rawName"]] = tag

    if not records:
        raise ValueError("Refusing an empty media/entity catalog")
    merged = {"schemaVersion": 1, "version": version,
              "sources": [sources[key] for key in sorted(sources)],
              "records": [records[key] for key in sorted(records)],
              "tags": [tags[key] for key in sorted(tags)],
              "links": [links[key] for key in sorted(links)],
              "searchAliases": [aliases[key] for key in sorted(aliases)]}
    counts = {}
    for record in merged["records"]:
        kind = record.get("mediaKind", record["key"]["kind"])
        counts[kind] = counts.get(kind, 0) + 1
    report = {"version": version, "component_versions": sorted(set(component_versions)),
              "source_count": len(sources), "record_count": len(records),
              "records_by_kind": counts, "tag_count": len(tags), "link_count": len(links), "search_alias_count": len(aliases)}
    return merged, report


def validate_tag(tag, source_ids):
    if not isinstance(tag, dict):
        raise ValueError("Invalid tag")
    exact_fields(tag, {"rawName", "displayName", "sourceId"})
    raw = require_text(tag.get("rawName"))
    require_text(tag.get("displayName"))
    if "{{" in raw or "}}" in raw:
        raise ValueError("Placeholder is not a raw tag key")
    if tag.get("sourceId") not in source_ids:
        raise ValueError("Tag references an undeclared source")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--component", action="append", type=Path, required=True)
    parser.add_argument("--tags", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    components = [loads(path.read_text(encoding="utf-8")) for path in args.component]
    tag_raw = args.tags.read_bytes() if args.tags else None
    merged, report = merge_catalogs(components, args.version, loads(tag_raw) if tag_raw else None,
                                    hashlib.sha256(tag_raw).hexdigest() if tag_raw else None)
    encoded = json_bytes(merged)
    report["catalog_sha256"] = hashlib.sha256(encoded).hexdigest()
    report["component_sha256"] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in args.component}
    publish_pair(args.output, encoded, args.report, json_bytes(report))
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
