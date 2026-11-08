#!/usr/bin/env python3
"""Validate and package an offline catalog for the dedicated release channel."""
import argparse
import datetime
import copy
import hashlib
import json
from pathlib import Path
import re
from merge_catalogs import merge_catalogs
from strict_json import loads


def package(source, output):
    raw = Path(source).read_bytes()
    if len(raw) > 20 * 1024 * 1024:
        raise ValueError("Catalog exceeds size limit")
    catalog = loads(raw.decode("utf-8", errors="strict"))
    version = catalog["version"]
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]{4}\.[0-9]{2}\.[0-9]{2}", version):
        raise ValueError("Invalid catalog version")
    datetime.date(*(int(x) for x in version.split(".")))
    # EntityKey.platform defaults to ANILIST in the app decoder. Preserve the
    # original bytes while supplying that default to the merger validator.
    validation_catalog = copy.deepcopy(catalog)
    for record in validation_catalog.get("records", []):
        if isinstance(record, dict) and isinstance(record.get("key"), dict):
            record["key"].setdefault("platform", "ANILIST")
    merge_catalogs([validation_catalog], version)
    manifest = {"formatVersion": 1, "catalogSchemaVersion": 1, "version": version,
                "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                "downloadUrl": f"https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v{version}/catalog.json"}
    manifest_raw = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "catalog.json").write_bytes(raw)
    (output / "catalog-manifest.json").write_bytes(manifest_raw)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalog")
    parser.add_argument("output")
    args = parser.parse_args()
    package(args.catalog, args.output)
