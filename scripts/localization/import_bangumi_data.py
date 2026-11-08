#!/usr/bin/env python3
"""Build a catalog from an explicitly downloaded, pinned bangumi-data snapshot."""

import argparse
from collections import defaultdict
import hashlib
import json
from strict_json import loads
import os
from pathlib import Path
import re
import tempfile


SOURCE_ID = "bangumi-data"


def build_catalog(source, revision, sha256):
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("A full source commit SHA is required")
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise ValueError("A source SHA-256 is required")
    if not isinstance(source, dict) or not isinstance(source.get("items"), list) or not source["items"]:
        raise ValueError("Expected a nonempty bangumi-data items array")
    if source.get("siteMeta", {}).get("aniList", {}).get("urlTemplate") != "https://anilist.co/anime/{{id}}":
        raise ValueError("The input does not declare the expected AniList anime namespace")

    by_id = defaultdict(list)
    ambiguous_ids = set()
    report = {
        "source_revision": revision,
        "source_sha256": sha256,
        "input_items": len(source["items"]),
        "without_anilist_id": 0,
        "without_simplified_title": 0,
        "conflicting_ids": [],
        "ambiguous_item_ids": [],
        "accepted_records": 0,
    }
    for item in source["items"]:
        if not isinstance(item, dict) or item.get("type") not in {"tv", "web", "movie", "ova"}:
            raise ValueError("Unsupported or invalid source item type")
        sites = item.get("sites", [])
        if not isinstance(sites, list) or not all(isinstance(site, dict) for site in sites):
            raise ValueError("Invalid source sites")
        ids = set()
        for site in sites:
            if site.get("site") != "aniList":
                continue
            raw_id = site.get("id")
            if isinstance(raw_id, bool) or not re.fullmatch(r"[1-9][0-9]*", str(raw_id)):
                raise ValueError("Invalid AniList ID")
            media_id = int(raw_id)
            if media_id > 2147483647:
                raise ValueError("AniList ID exceeds the supported integer range")
            ids.add(media_id)
        if not ids:
            report["without_anilist_id"] += 1
        if len(ids) > 1:
            ambiguous_ids.update(ids)
        for media_id in ids:
            by_id[media_id].append(item)

    report["ambiguous_item_ids"] = sorted(ambiguous_ids)
    records = []
    for media_id, items in sorted(by_id.items()):
        # A shared ID may represent a split, merge, or bad link, not a display alias.
        if len(items) != 1:
            report["conflicting_ids"].append(media_id)
            continue
        if media_id in ambiguous_ids:
            continue
        item = items[0]
        translations = item.get("titleTranslate", {})
        if not isinstance(translations, dict):
            raise ValueError("Invalid titleTranslate object")
        names = translations.get("zh-Hans", [])
        if not isinstance(names, list) or not all(isinstance(name, str) for name in names):
            raise ValueError("Invalid zh-Hans names")
        names = list(dict.fromkeys(name.strip() for name in names if name.strip()))
        if not names:
            report["without_simplified_title"] += 1
            continue
        native_title = item.get("title", "")
        if not isinstance(native_title, str):
            raise ValueError("Invalid original title")
        other_names = []
        for language in sorted(translations):
            if language == "zh-Hans":
                continue
            values = translations[language]
            if not isinstance(values, list) or not all(isinstance(name, str) for name in values):
                raise ValueError("Invalid translated names")
            other_names.extend(name.strip() for name in values if name.strip())
        aliases = list(dict.fromkeys(names[1:] + ([native_title.strip()] if native_title.strip() else []) + other_names))
        aliases = [name for name in aliases if name != names[0]]
        records.append({
            "key": {"platform": "ANILIST", "kind": "MEDIA", "id": media_id},
            "displayName": names[0],
            "aliases": aliases,
            "sourceId": SOURCE_ID,
            "mediaKind": "ANIME",
        })
    report["accepted_records"] = len(records)
    catalog = {
        "schemaVersion": 1,
        "version": f"bangumi-data-{revision[:12]}",
        "sources": [{
            "id": SOURCE_ID,
            "revision": revision,
            "url": f"https://github.com/bangumi-data/bangumi-data/tree/{revision}",
            "license": "CC-BY-4.0",
            "sha256": sha256,
        }],
        "records": records,
    }
    return catalog, report


def stage_bytes(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
    except BaseException:
        os.unlink(temporary)
        raise
    return temporary


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=True, indent=2) + "\n").encode("utf-8")


def publish_pair(output_path, catalog_bytes, report_path, report_bytes):
    paths = [Path(output_path), Path(report_path)]
    if paths[0].resolve() == paths[1].resolve() or (
        paths[0].exists() and paths[1].exists() and os.path.samefile(*paths)
    ):
        raise ValueError("Catalog and report destinations must differ")
    staged = []
    backup = None
    preserve_backup = False
    replaced_first = False
    try:
        staged = [stage_bytes(paths[0], catalog_bytes)]
        staged.append(stage_bytes(paths[1], report_bytes))
        if paths[0].exists():
            backup = stage_bytes(paths[0], paths[0].read_bytes())
        os.replace(staged[0], paths[0])
        replaced_first = True
        os.replace(staged[1], paths[1])
    except BaseException as error:
        if replaced_first:
            try:
                if backup is None:
                    paths[0].unlink()
                else:
                    os.replace(backup, paths[0])
            except OSError as rollback_error:
                preserve_backup = backup is not None
                raise RuntimeError(
                    f"Output rollback failed; catalog and report may differ; "
                    f"prior catalog backup: {backup or 'none'}: {rollback_error}"
                ) from error
        raise
    finally:
        for temporary in staged + ([backup] if backup and not preserve_backup else []):
            if os.path.exists(temporary):
                os.unlink(temporary)


def import_catalog(source_path, output_path, report_path, revision, expected_sha256):
    raw = Path(source_path).read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != expected_sha256:
        raise ValueError(f"Source checksum mismatch: {digest}")
    catalog, report = build_catalog(loads(raw), revision, digest)
    if not catalog["records"]:
        raise ValueError("Refusing to replace a catalog with zero accepted records")
    catalog_bytes = json_bytes(catalog)
    report["catalog_sha256"] = hashlib.sha256(catalog_bytes).hexdigest()
    publish_pair(output_path, catalog_bytes, report_path, json_bytes(report))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--sha256", required=True)
    args = parser.parse_args()
    report = import_catalog(args.input, args.output, args.report, args.revision, args.sha256)
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
