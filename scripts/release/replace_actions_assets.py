#!/usr/bin/env python3
"""Replace assets of an existing release with independently verified Actions output."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote
import zipfile

from apk_artifacts import verify_artifacts
from release_foss import find_release, tag_commit, validate

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/localization'))
from publish_catalog import REPO


def digest(data):
    return hashlib.sha256(data).hexdigest()


class GitHub:
    def api(self, endpoint, method='GET', payload=None, data=None):
        command = ['gh', 'api', '--method', method, '-H', 'Accept: application/vnd.github+json']
        if data is not None:
            command += ['-H', 'Content-Type: application/octet-stream', '--input', '-']
            body = data
        elif payload is not None:
            command += ['--input', '-']
            body = json.dumps(payload).encode()
        else:
            body = None
        result = subprocess.run(command + [endpoint], input=body, capture_output=True)
        if result.returncode:
            raise RuntimeError('GitHub API operation failed; inspect release before retrying')
        return result.stdout

    def json(self, endpoint, **kwargs):
        return json.loads(self.api(endpoint, **kwargs))

    def release(self, tag):
        return find_release(REPO, tag)

    def tag(self, tag):
        return tag_commit(REPO, tag)

    def download(self, asset_id):
        result = subprocess.run(['gh', 'api', '-H', 'Accept: application/octet-stream',
                                 f'repos/{REPO}/releases/assets/{asset_id}'], capture_output=True)
        if result.returncode:
            raise RuntimeError('Failed to download release asset')
        return result.stdout

    def upload(self, release_id, name, data):
        url = f'https://uploads.github.com/repos/{REPO}/releases/{release_id}/assets?name={quote(name, safe="")}'
        return self.json(url, method='POST', data=data)

    def rename(self, asset_id, name):
        self.json(f'repos/{REPO}/releases/assets/{asset_id}', method='PATCH', payload={'name': name})

    def delete(self, asset_id):
        self.api(f'repos/{REPO}/releases/assets/{asset_id}', method='DELETE')

    def notes(self, release_id, body):
        self.json(f'repos/{REPO}/releases/{release_id}', method='PATCH', payload={'body': body})


def assets_by_name(release):
    assets = release['assets']
    names = [a['name'] for a in assets]
    if len(names) != len(set(names)):
        raise ValueError('Duplicate release asset names')
    return {a['name']: a for a in assets}


def verify_run(api, run_id, workflow_head, version, files):
    run = api.json(f'repos/{REPO}/actions/runs/{run_id}')
    if (str(run.get('id')) != str(run_id) or run.get('event') != 'workflow_dispatch'
            or run.get('status') != 'completed' or run.get('conclusion') != 'success'
            or run.get('repository', {}).get('full_name') != REPO
            or run.get('head_repository', {}).get('full_name') != REPO
            or run.get('head_sha') != workflow_head or run.get('head_branch') != 'master'):
        raise ValueError('Actions run is not a successful dispatch in the trusted repository')
    workflow = api.json(f'repos/{REPO}/actions/workflows/{run["workflow_id"]}')
    if workflow.get('path') != '.github/workflows/release-foss.yml':
        raise ValueError('Unexpected Actions workflow')
    listing = api.json(f'repos/{REPO}/actions/runs/{run_id}/artifacts?per_page=100')
    artifacts = listing['artifacts']
    if listing.get('total_count') != len(artifacts):
        raise ValueError('Incomplete Actions artifact listing')
    matches = []
    selected = [a for a in artifacts if a.get('name') == f'foss-release-{version}-{run_id}']
    if (len(selected) != 1 or selected[0].get('expired')
            or not isinstance(selected[0].get('size_in_bytes'), int)
            or not 0 < selected[0]['size_in_bytes'] <= 200 * 1024 * 1024):
        raise ValueError('Expected Actions artifact is missing, expired, or too large')
    for artifact in selected:
        blob = api.api(f'repos/{REPO}/actions/artifacts/{artifact["id"]}/zip')
        if len(blob) > 200 * 1024 * 1024:
            raise ValueError('Actions artifact archive is too large')
        with zipfile.ZipFile(io.BytesIO(blob)) as archive:
            entries = archive.infolist()
            if sum(entry.file_size for entry in entries) > 200 * 1024 * 1024:
                raise ValueError('Actions artifact contents are too large')
            names = [entry.filename for entry in entries]
            if len(names) == len(set(names)) and set(names) == set(files):
                if any(entry.is_dir() or entry.file_size != len(files[entry.filename]) for entry in entries):
                    raise ValueError('Unexpected Actions artifact entry')
                if all(digest(archive.read(name)) == digest(data) for name, data in files.items()):
                    matches.append(artifact['id'])
    if len(matches) != 1:
        raise ValueError('Actions output does not uniquely match the verified local files')


def verify_snapshot(api, release, expected):
    actual = assets_by_name(release)
    if set(actual) != set(expected):
        raise ValueError('Release asset set changed')
    for name, (asset_id, checksum) in expected.items():
        asset = actual[name]
        if asset['id'] != asset_id or digest(api.download(asset_id)) != checksum:
            raise ValueError('Release asset changed')


def replace(api, artifacts, version, head, workflow_head, run_id, expected_manifest, expected_prior, source_root):
    if not re.fullmatch(r'[0-9a-f]{40}', workflow_head) or not re.fullmatch(r'[0-9]+', str(run_id)) or any(not re.fullmatch(r'[0-9a-f]{64}', x) for x in (expected_manifest, expected_prior)):
        raise ValueError('Invalid run ID or manifest digest')
    validate(version, head, source_root)
    manifest = verify_artifacts(artifacts, source_root)
    if manifest['sourceCommit'] != head:
        raise ValueError('Artifact source commit differs')
    files = {p.name: p.read_bytes() for p in Path(artifacts).iterdir()}
    if len(files) != 7 or digest(files['release-manifest.json']) != expected_manifest:
        raise ValueError('Unexpected artifacts or manifest digest')
    verify_run(api, run_id, workflow_head, version, files)
    tag = 'v' + version
    release = api.release(tag)
    if not release or release['draft'] or release['tag_name'] != tag or api.tag(tag) != head:
        raise ValueError('Expected published release and tag were not found')
    all_assets = assets_by_name(release)
    rid = release['id']
    baseline_body = release.get('body') or ''
    stage = {name: f'actions-{run_id}-{name}' for name in files}
    backup = {name: f'backup-{run_id}-{name}' for name in files}
    if len(set(stage.values()) | set(backup.values()) | set(files)) != 21:
        raise ValueError('Asset name collision')
    if not set(files).issubset(all_assets) or not set(all_assets).issubset(set(files) | set(stage.values())):
        raise ValueError('Existing release asset set differs')
    old = {name: all_assets[name] for name in files}
    snapshot = {name: (asset['id'], digest(api.download(asset['id']))) for name, asset in old.items()}
    if snapshot['release-manifest.json'][1] != expected_prior:
        raise ValueError('Existing manifest digest differs from expected')
    staged = {}
    for name, data in files.items():
        existing = all_assets.get(stage[name])
        if existing:
            if digest(api.download(existing['id'])) != digest(data):
                raise ValueError('Existing staged asset differs; inspect it before retrying')
            staged[name] = existing['id']
    for name, data in files.items():
        if name not in staged:
            result = api.upload(rid, stage[name], data)
            staged[name] = result['id']
    current = api.release(tag)
    expected = dict(snapshot)
    expected.update({stage[n]: (staged[n], digest(data)) for n, data in files.items()})
    if not current or current['id'] != rid or current['draft'] or api.tag(tag) != head:
        raise ValueError('Release changed while staging')
    verify_snapshot(api, current, expected)
    notes_attempted = False
    written_body = None
    try:
        for name in files:
            api.rename(old[name]['id'], backup[name])
        for name in files:
            api.rename(staged[name], name)
        current = api.release(tag)
        expected = {n: (staged[n], digest(data)) for n, data in files.items()}
        expected.update({backup[n]: snapshot[n] for n in files})
        if not current or current['id'] != rid or current['draft'] or api.tag(tag) != head:
            raise ValueError('Release changed during replacement')
        verify_snapshot(api, current, expected)
        marker = f'https://github.com/{REPO}/actions/runs/{run_id}'
        body = current.get('body') or ''
        if body != baseline_body:
            raise ValueError('Release notes changed during replacement')
        if marker not in body:
            written_body = body.rstrip() + '\n\nActions build: ' + marker + '\n'
            notes_attempted = True
            api.notes(rid, written_body)
        current = api.release(tag)
        if not current or current['id'] != rid or current['draft'] or api.tag(tag) != head:
            raise ValueError('Release changed before backup cleanup')
        verify_snapshot(api, current, expected)
        if marker not in (current.get('body') or ''):
            raise ValueError('Actions build note was not retained')
    except Exception as exc:
        # Re-read by ID: a failed API call may have completed remotely.
        try:
            current = api.release(tag)
            by_id = {a['id']: a['name'] for a in current['assets']}
            for name in files:
                if by_id.get(staged[name]) == name:
                    api.rename(staged[name], stage[name])
            for name in files:
                if by_id.get(old[name]['id']) == backup[name]:
                    api.rename(old[name]['id'], name)
            restored = api.release(tag)
            if notes_attempted:
                actual_body = restored.get('body') or ''
                if actual_body == written_body:
                    api.notes(rid, baseline_body)
                    restored = api.release(tag)
                    if (restored.get('body') or '') != baseline_body:
                        raise ValueError('Release notes not restored')
                elif actual_body != baseline_body:
                    raise ValueError('Release notes changed concurrently; not overwriting')
            names = assets_by_name(restored)
            if any(names.get(n, {}).get('id') != snapshot[n][0] for n in files):
                raise ValueError('Old names not restored')
        except Exception as rollback_error:
            raise RuntimeError('Replacement failed and rollback is incomplete; inspect release assets immediately') from rollback_error
        raise RuntimeError('Replacement failed; old asset names restored, staged files retained') from exc
    try:
        for name in files:
            api.delete(old[name]['id'])
    except Exception as exc:
        raise RuntimeError('New assets were verified; backup cleanup failed and backups may remain; inspect release') from exc
    current = api.release(tag)
    if not current or current['id'] != rid or current['draft'] or api.tag(tag) != head:
        raise RuntimeError('New assets were verified but final release state could not be confirmed')
    verify_snapshot(api, current, {n: (staged[n], digest(data)) for n, data in files.items()})
    print(f'Replaced assets on https://github.com/{REPO}/releases/tag/{tag}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--source-head', required=True)
    parser.add_argument('--workflow-head', required=True)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--expected-manifest-sha256', required=True)
    parser.add_argument('--expected-prior-manifest-sha256', required=True)
    args = parser.parse_args()
    replace(GitHub(), args.artifacts, args.version, args.source_head, args.workflow_head, args.run_id,
            args.expected_manifest_sha256, args.expected_prior_manifest_sha256, args.source_root)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f'Replacement failed: {error}', file=sys.stderr)
        raise SystemExit(1)
