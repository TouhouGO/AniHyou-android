#!/usr/bin/env python3
"""Refresh all public inputs in isolation; promote only a validated catalog."""
import datetime
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import urllib.parse
import urllib.request

from rebuild_catalog import rebuild, verify_query
from strict_json import loads as unique_loads


def loads(raw):
    # Reject JSON extensions such as NaN as well as duplicate keys.
    json.loads(raw, parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Invalid JSON constant: " + value)))
    return unique_loads(raw)

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path('data/localization/sources/catalog-sources.json')
CATALOG = Path('core/localization/src/main/resources/localization/catalog.json')
REPORT = Path('docs/localization/catalog-report.json')
MAX_BYTES = 100 * 1024 * 1024
UA = 'AniHyou-Catalog-Maintenance/1.0 (https://github.com/TouhouGO/AniHyou-android)'


def fetch(url, *, accept='application/json'):
    request = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': accept, 'Accept-Encoding': 'identity'})
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status != 200:
            raise ValueError('Unexpected HTTP status')
        data = response.read(MAX_BYTES + 1)
    if not data or len(data) > MAX_BYTES:
        raise ValueError('Empty or oversized response')
    return data


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def semantic(catalog):
    return {k: v for k, v in catalog.items() if k not in ('version', 'sources')}


def check_shrink(old, new):
    def counts(cat):
        groups = {}
        for row in cat['records']:
            kind = row['key']['kind']
            group = 'records:' + kind + ':' + str(row.get('mediaKind', 'NONE'))
            groups[group] = groups.get(group, 0) + 1
        for name in ('links', 'searchAliases'):
            groups[name] = len(cat.get(name, []))
            for row in cat.get(name, []):
                if isinstance(row, dict):
                    group = name + ':' + str(row.get('mediaKind', 'UNKNOWN'))
                    groups[group] = groups.get(group, 0) + 1
        return groups
    old_counts, new_counts = counts(old), counts(new)
    for group, count in old_counts.items():
        if new_counts.get(group, 0) * 10 < count * 9:
            raise ValueError(f'Suspicious shrink {group}: {count} -> {new_counts.get(group, 0)}; manual review required')


def check_identity_resolutions(old_report, new_catalog, old_catalog):
    # A previously ambiguous identity cannot silently become an accepted record
    # merely because a competing identity claim disappeared.
    old_aliases = {row['aniListId'] for row in old_catalog.get('searchAliases', [])}
    new_aliases = {row['aniListId'] for row in new_catalog.get('searchAliases', [])} - old_aliases
    for prop in ('P11227', 'P11736', 'P8731'):
        evidence = old_report['source_reports'][prop]
        ambiguous = set(evidence['identity_conflicting_ids']) | set(evidence['ambiguous_ids'])
        accepted = {row['key']['id'] for row in new_catalog['records']
                    if row['sourceId'] == 'wikidata-' + prop.lower()}
        if prop == 'P8731':
            accepted |= new_aliases
        exposed = sorted(ambiguous & accepted)
        if exposed:
            raise ValueError(f'Previously ambiguous {prop} IDs now accepted: {exposed}; manual review required')


def commit_files(root, stage, files):
    # Roll back ordinary I/O failures, retaining previous bytes even if promotion fails midway.
    original = {p: (root / p).read_bytes() if (root / p).exists() else None for p in files}
    applied = []
    try:
        for p in files:
            destination = root / p
            destination.parent.mkdir(parents=True, exist_ok=True)
            os.replace(stage / p, destination)
            applied.append(p)
    except BaseException:
        for p in reversed(applied):
            destination = root / p
            if original[p] is None:
                destination.unlink(missing_ok=True)
            else:
                destination.write_bytes(original[p])
        raise


def refresh(root=ROOT, get=fetch, today=None):
    root = Path(root)
    today = today or datetime.datetime.now(datetime.timezone.utc).date()
    old_manifest = loads((root / MANIFEST).read_bytes())
    old_catalog = loads((root / CATALOG).read_bytes())
    version = today.strftime('%Y.%m.%d')
    with tempfile.TemporaryDirectory(prefix='catalog-refresh-') as tmp:
        stage = Path(tmp)
        shutil.copytree(root / 'data/localization', stage / 'data/localization')
        base = stage / MANIFEST.parent
        # Rebuild retained evidence first, so comparisons use validated source
        # counts and ambiguity sets rather than a stale hand-edited report.
        baseline_catalog = stage / 'baseline-catalog.json'
        baseline_report = stage / 'baseline-report.json'
        old_report = rebuild(stage / MANIFEST, baseline_catalog, baseline_report)
        if baseline_catalog.read_bytes() != (root / CATALOG).read_bytes():
            raise ValueError('Current catalog does not match retained source evidence')
        manifest = old_manifest
        files = []
        for key, repo, path, prefix in (
            ('anime', 'bangumi-data/bangumi-data', 'dist/data.json', 'bangumi-data'),
            ('searchAliases', 'soruly/anilist-chinese', 'anilist-chinese.json', 'soruly')):
            info = loads(get(f'https://api.github.com/repos/{repo}/commits/master'))
            rev = info['sha']
            if not isinstance(rev, str) or not re.fullmatch('[0-9a-f]{40}', rev):
                raise ValueError('Invalid upstream revision')
            raw = get(f'https://raw.githubusercontent.com/{repo}/{rev}/{path}')
            loads(raw)
            name = f'{prefix}-{rev}.json.gz'
            (base / name).write_bytes(gzip.compress(raw, mtime=0))
            manifest[key].update(sourceFile=name, revision=rev, sha256=digest(raw))
            files.append(MANIFEST.parent / name)
        timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
        for entry in manifest['wikidata']:
            for identity in (False, True):
                target = entry['identity'] if identity else entry
                verify_query(base, target, entry['property'], identity)
                query = (base / target['queryFile']).read_bytes()
                url = 'https://query.wikidata.org/sparql?' + urllib.parse.urlencode({'query': query.decode(), 'format': 'json'})
                raw = get(url, accept='application/sparql-results+json')
                payload = loads(raw)
                rows = payload['results']['bindings']
                if not isinstance(rows, list) or not rows or len(rows) >= target['queryLimit']:
                    raise ValueError(f"Empty or capped Wikidata query: {entry['property']}")
                count_field = 'identity_rows' if identity else 'input_rows'
                old_count = old_report['source_reports'][entry['property']][count_field]
                if len(rows) * 10 < old_count * 9:
                    kind = 'identity' if identity else 'labels'
                    raise ValueError(f"Suspicious Wikidata {entry['property']} {kind} shrink: {old_count} -> {len(rows)}; manual review required")
                name = f"wikidata-{entry['property'].lower()}-{'identity' if identity else 'labels'}-{digest(raw)}.json.gz"
                (base / name).write_bytes(gzip.compress(raw, mtime=0))
                # Remove stale transfer/derived statistics from the previous acquisition.
                for field in ('transferSha256', 'transferBytes', 'transferEncoding', 'uniqueIdCount', 'duplicateAssertionRows', 'idsWithMultipleItems'):
                    target.pop(field, None)
                target.update(sourceFile=name, sha256=digest(raw), rowCount=len(rows), httpStatus=200, fetchedAt=timestamp, endpoint='https://query.wikidata.org/sparql')
                if identity:
                    target.update(usable=True, atLimit=False)
                files.append(MANIFEST.parent / name)
        manifest['version'] = version
        (stage / MANIFEST).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        for p in (CATALOG, REPORT):
            (stage / p).parent.mkdir(parents=True, exist_ok=True)
        rebuild(stage / MANIFEST, stage / CATALOG, stage / REPORT)
        new_catalog = loads((stage / CATALOG).read_bytes())
        check_shrink(old_catalog, new_catalog)
        check_identity_resolutions(old_report, new_catalog, old_catalog)
        if semantic(old_catalog) == semantic(new_catalog):
            print('Catalog data unchanged; no files modified')
            return False
        if version <= old_catalog['version']:
            raise ValueError('Changed data requires a later date version; defer until tomorrow')
        commit_files(root, stage, list(dict.fromkeys(files + [MANIFEST, CATALOG, REPORT])))
        print(f'Validated catalog {version}; review generated changes')
        return True


if __name__ == '__main__':
    refresh()
