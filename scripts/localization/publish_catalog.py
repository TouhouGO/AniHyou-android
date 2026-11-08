#!/usr/bin/env python3
"""Publish immutable catalog assets and then advance the separate catalog channel."""
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

from package_catalog import package
from strict_json import loads as unique_loads


def loads(raw):
    # Reject JSON extensions such as NaN as well as duplicate keys.
    json.loads(raw, parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Invalid JSON constant: " + value)))
    return unique_loads(raw)

REPO = 'TouhouGO/AniHyou-android'
API = f'https://api.github.com/repos/{REPO}'
PUBLIC = f'https://github.com/{REPO}/releases/download'


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected and urllib.parse.urlsplit(newurl).hostname != urllib.parse.urlsplit(req.full_url).hostname:
            redirected.remove_header('Authorization')
        return redirected


class GitHub:
    def __init__(self, token):
        if not token:
            raise ValueError('GITHUB_TOKEN is required')
        self.token = token

    def request(self, url, method='GET', data=None, content_type='application/json', public=False, missing=False, binary=False):
        allowed = ('https://api.github.com/', 'https://uploads.github.com/') if not public else ('https://github.com/',)
        if not url.startswith(allowed):
            raise ValueError('Unexpected GitHub host')
        headers = {'User-Agent': 'AniHyou-Catalog-Maintenance/1.0', 'Accept': 'application/octet-stream' if binary else 'application/vnd.github+json'}
        if not public:
            headers['Authorization'] = 'Bearer ' + self.token
        if data is not None:
            headers['Content-Type'] = content_type
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.build_opener(SafeRedirect()).open(req, timeout=120) as response:
                raw = response.read(25 * 1024 * 1024 + 1)
                if len(raw) > 25 * 1024 * 1024:
                    raise ValueError('Response too large')
                return raw
        except urllib.error.HTTPError as exc:
            if missing and exc.code == 404:
                return None
            raise

    def release(self, tag):
        raw = self.request(API + '/releases/tags/' + urllib.parse.quote(tag, safe=''), missing=True)
        if raw is not None:
            return loads(raw)
        # Draft releases have no published tag and may be invisible to the
        # tag endpoint. Search the authenticated release list to completion,
        # including later pages, and fail closed if uniqueness is uncertain.
        matches = []
        for page in range(1, 21):
            raw = self.request(API + f'/releases?per_page=100&page={page}')
            releases = loads(raw)
            if not isinstance(releases, list) or len(releases) > 100:
                raise ValueError('Invalid releases page')
            for release in releases:
                if not isinstance(release, dict):
                    raise ValueError('Invalid release in page')
                if release.get('tag_name') == tag:
                    matches.append(release)
            if len(matches) > 1:
                raise ValueError(f'Multiple releases for tag: {tag}')
            if len(releases) < 100:
                return matches[0] if matches else None
        raise ValueError('Release listing exceeded 20 pages; cannot prove uniqueness')

    def refresh(self, release):
        current = loads(self.request(API + f"/releases/{release['id']}"))
        if current.get('id') != release['id'] or current.get('tag_name') != release.get('tag_name'):
            raise ValueError('Release identity changed during publication')
        return current

    def create(self, tag, draft):
        body = {'tag_name': tag, 'name': tag, 'body': 'Chinese localization catalog data. See included notices.', 'draft': draft, 'prerelease': False, 'make_latest': 'false'}
        return loads(self.request(API + '/releases', 'POST', json.dumps(body).encode()))

    def update(self, release, **values):
        values['make_latest'] = 'false'
        return loads(self.request(API + f"/releases/{release['id']}", 'PATCH', json.dumps(values).encode()))

    def upload(self, release, name, raw):
        url = f"https://uploads.github.com/repos/{REPO}/releases/{release['id']}/assets?" + urllib.parse.urlencode({'name': name})
        self.request(url, 'POST', raw, 'application/octet-stream')

    def delete(self, asset):
        self.request(API + f"/releases/assets/{asset['id']}", 'DELETE')

    def asset(self, release, name):
        matches = [a for a in release['assets'] if a['name'] == name]
        if len(matches) > 1:
            raise ValueError('Duplicate asset')
        if not matches:
            return None
        raw = self.request(API + f"/releases/assets/{matches[0]['id']}", binary=True)
        # GitHub requires an octet-stream Accept header for binary asset download.
        return raw

    def public(self, tag, name):
        return self.request(f'{PUBLIC}/{urllib.parse.quote(tag, safe="")}/{urllib.parse.quote(name, safe="")}', public=True)


def assets(release):
    return {a['name']: a for a in release['assets']}


def valid_manifest(raw):
    m = loads(raw)
    if not isinstance(m, dict) or set(m) != {'formatVersion', 'catalogSchemaVersion', 'version', 'size', 'sha256', 'downloadUrl'}:
        raise ValueError('Malformed channel manifest')
    if type(m['formatVersion']) is not int or m['formatVersion'] != 1 or type(m['catalogSchemaVersion']) is not int or m['catalogSchemaVersion'] != 1:
        raise ValueError('Unsupported channel manifest')
    import datetime
    if not isinstance(m['version'], str) or not re.fullmatch(r'\d{4}\.\d{2}\.\d{2}', m['version']):
        raise ValueError('Invalid version')
    datetime.date(*(int(x) for x in m['version'].split('.')))
    if type(m['size']) is not int or not 0 < m['size'] <= 20 * 1024 * 1024 or not isinstance(m['sha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', m['sha256']):
        raise ValueError('Invalid size or hash')
    if m['downloadUrl'] != f'{PUBLIC}/catalog-v{m["version"]}/catalog.json':
        raise ValueError('Unexpected catalog URL')
    return m


def publish(api, directory, notice_files):
    directory = Path(directory)
    expected = (directory / 'catalog-manifest.json').read_bytes()
    manifest = valid_manifest(expected)
    catalog = (directory / 'catalog.json').read_bytes()
    if len(catalog) != manifest['size'] or hashlib.sha256(catalog).hexdigest() != manifest['sha256']:
        raise ValueError('Package hash mismatch')
    tag = 'catalog-v' + manifest['version']
    # Channel preflight occurs before any write, including dated draft creation.
    channel = api.release('catalog-latest')
    prior = None
    if channel and channel['draft']:
        existing = api.asset(channel, 'catalog-manifest.json')
        if existing is not None and existing != expected:
            raise ValueError('Draft channel contains a different manifest')
    elif channel:
        prior = api.public('catalog-latest', 'catalog-manifest.json')
        previous = valid_manifest(prior)
        if previous['version'] > manifest['version']:
            raise ValueError('Channel downgrade rejected')
        if previous['version'] == manifest['version'] and previous['sha256'] != manifest['sha256']:
            raise ValueError('Channel version collision')
    release = api.release(tag)
    payloads = {'catalog.json': catalog, **notice_files}
    if release is None:
        release = api.create(tag, True)
    for name, raw in payloads.items():
        existing = api.asset(release, name) if name in assets(release) else None
        if existing is None:
            if not release['draft']:
                raise ValueError(f'Published release missing {name}')
            api.upload(release, name, raw)
            release = api.refresh(release)
        elif existing != raw:
            raise ValueError(f'Immutable release collision: {name}')
    # Re-read every uploaded draft asset before making the release public.
    for name, raw in payloads.items():
        if api.asset(release, name) != raw:
            raise ValueError(f'Draft asset verification failed: {name}')
    if release['draft']:
        release = api.update(release, draft=False)
    for name, raw in payloads.items():
        if api.public(tag, name) != raw:
            raise ValueError(f'Public asset verification failed: {name}')
    if prior == expected:
        print('Catalog already published')
        return
    if prior is not None and previous['version'] == manifest['version']:
        raise ValueError('Same catalog version has different manifest bytes')
    if channel is None or channel['draft']:
        if channel is None:
            channel = api.create('catalog-latest', True)
        if 'catalog-manifest.json' not in assets(channel):
            api.upload(channel, 'catalog-manifest.json', expected)
            channel = api.refresh(channel)
        if api.asset(channel, 'catalog-manifest.json') != expected:
            raise ValueError('Draft channel verification failed')
        channel = api.update(channel, draft=False)
    else:
        asset = assets(channel).get('catalog-manifest.json')
        if not asset:
            raise ValueError('Channel asset missing')
        api.delete(asset)
        try:
            api.upload(channel, 'catalog-manifest.json', expected)
        except Exception:
            # GitHub release assets cannot be replaced atomically. Restore on upload failure.
            api.upload(channel, 'catalog-manifest.json', prior)
            raise
    if api.public('catalog-latest', 'catalog-manifest.json') != expected:
        raise ValueError('Channel public verification failed')
    print('Published ' + tag)


if __name__ == '__main__':
    import argparse
    import tempfile
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalog', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    notice = (root / 'core/localization/src/main/resources/localization/NOTICE').read_text()
    source = loads(args.catalog.read_bytes())['sources']
    # The catalog carries authoritative current source revisions.
    notice += '\nCatalog source provenance (authoritative for this release):\n' + json.dumps(source, ensure_ascii=False, indent=2) + '\n'
    for old in ('94ea82be75d9e617c7970bb0fd0670ae5c60e53d', '913bb1dcd8f65aac18199d8ee6819f703596658a'):
        notice = notice.replace('Source revision: ' + old, 'Source revision: see catalog provenance below')
    notices = {'NOTICE.txt': notice.encode(), 'soruly-MIT-LICENSE.txt': (root / 'data/localization/sources/soruly-MIT-LICENSE').read_bytes(), 'OpenCC-LICENSE.txt': (root / 'scripts/localization/opencc/LICENSE').read_bytes()}
    with tempfile.TemporaryDirectory(prefix='catalog-package-') as tmp:
        package(args.catalog, tmp)
        publish(GitHub(os.environ.get('GITHUB_TOKEN')), tmp, notices)
