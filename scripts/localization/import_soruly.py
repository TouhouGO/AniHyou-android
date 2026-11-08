"""Import source titles as search evidence without creating display records."""

import re

SOURCE_ID = "soruly-anilist-chinese"


def build_catalog(payload, revision, sha256, components, excluded):
    if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Expected a full lowercase commit SHA")
    if not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise ValueError("Expected a lowercase SHA-256 digest")
    if not isinstance(payload, list) or not payload:
        raise ValueError("Expected nonempty source array")
    evidence = {}
    for component in components:
        for record in component['records']:
            if record['key']['kind'] == 'MEDIA':
                evidence.setdefault(record['key']['id'], set()).add(record['mediaKind'])
        for link in component.get('links', []):
            evidence.setdefault(link['aniListId'], set()).add(link['mediaKind'])
    existing = {r['key']['id'] for c in components for r in c['records'] if r['key']['kind'] == 'MEDIA'}
    seen, aliases, excluded_seen = set(), [], []
    for row in payload:
        if not isinstance(row, dict) or set(row) != {'id', 'title', 'synonyms'}:
            raise ValueError('Invalid source row')
        identifier = row['id']
        if type(identifier) is not int or not 0 < identifier <= 2147483647 or identifier in seen:
            raise ValueError('Invalid or duplicate source ID')
        seen.add(identifier)
        if not isinstance(row['title'], str) or not row['title'].strip() or not isinstance(row['synonyms'], list) or any(not isinstance(n, str) or not n.strip() for n in row['synonyms']):
            raise ValueError('Invalid source names')
        if identifier in excluded:
            excluded_seen.append(identifier)
            continue
        kinds = evidence.get(identifier, set())
        if len(kinds) > 1:
            excluded_seen.append(identifier)
            continue
        alias = {'aniListId': identifier, 'names': list(dict.fromkeys([row['title'], *row['synonyms']])), 'sourceId': SOURCE_ID}
        if kinds:
            alias['mediaKind'] = next(iter(kinds))
        aliases.append(alias)
    aliases.sort(key=lambda a: a['aniListId'])
    report = {'accepted_aliases': len(aliases), 'new_searchable_ids': sum(a['aniListId'] not in existing for a in aliases),
              'known_types': sum('mediaKind' in a for a in aliases), 'unknown_types': sum('mediaKind' not in a for a in aliases),
              'excluded_ids': sorted(excluded_seen)}
    return {'schemaVersion': 1, 'version': 'soruly-' + revision[:12], 'sources': [{'id': SOURCE_ID, 'revision': revision,
            'url': 'https://github.com/soruly/anilist-chinese/tree/' + revision, 'license': 'MIT', 'sha256': sha256}],
            'records': [], 'searchAliases': aliases}, report
