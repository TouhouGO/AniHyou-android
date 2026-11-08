# Catalog import, reset and update

The catalog is one schema-version-1 JSON containing sources, entity display names,
tags, explicit media links and optional source-attributed search aliases. It is
separate from the previous fork's localization bundle. Do not import the old ZIP
or point the new client at the old `localization-latest` feed.

## Local actions

The resource controls in Settings are available without login. They show the
active version, entity count and whether data is built in or locally installed.
Import uses Android's document picker, without broad storage permission. Restore
validates the built-in data before removing the overlay. A failed operation keeps
the previous active snapshot and provides a localized error.

Imports and downloads require strict UTF-8, supported catalog schema, valid source
references, a nonempty entity catalog and a zero-padded calendar version such as `2026.11.07`
with years 0001 through 9999. The catalog limit is 20 MiB. Older versions and
same-version/different-content collisions are rejected. Same version plus identical
bytes is a no-op. Reset explicitly allows return to the built-in version.

The host serializes load, install and reset. It publishes a snapshot after a
successful private-file commit. A generation token prevents a completed old
download from undoing a later restore or install. Coroutine cancellation before
commit leaves active data unchanged; commit and publication complete together once
the short commit section starts. The Android adapter syncs the file descriptor
and checks committed bytes; actual crash recovery and filesystem durability remain
platform-validation gaps under the no-device constraint.

## Dedicated update channel

The fixed manifest URL is:

[Catalog manifest](https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-latest/catalog-manifest.json)

The deployment status is recorded in `MAINTENANCE.md`. HTTP 404 is an unavailable
channel, not an up-to-date result.

Automatic checks are enabled after saved preferences are loaded. Entering the
foreground starts a nonblocking check when due: successful checks wait 24 hours;
unavailable channels and transient failures wait one hour. A provisional deadline
is persisted before HTTP to avoid a retry storm after process death. Large clock
rollback is bounded to one retry window. The app does not check while fully closed.

Settings can disable automatic checks, show the last attempt, or check manually.
Manual checks bypass the interval and share an in-flight request. A main-screen
notice links to settings when an update is available. Download and installation
remain user-triggered. The persisted notice is bound to the installed version/hash;
a fresh manifest check precedes download so generation tokens are never restored
from preferences. Installation cancellation, reset and import reconcile old offers.

Manifest fields are `formatVersion`, `catalogSchemaVersion`, `version`, `size`,
`sha256` and `downloadUrl`. Format/schema versions are both 1. Manifest size is
limited to 32 KiB. The download URL is fixed by version:

```text
https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-vYYYY.MM.DD/catalog.json
```

Downloads permit only HTTPS on port 443 and GitHub's declared release asset hosts.
Exact size, hash, version and JSON validity are checked before installation.
Hashes detect byte changes; they do not provide an independent publisher signature.
No signing credentials or account tokens are used by this client.

## Offline packaging

Rebuild sources before packaging:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/localization/rebuild_catalog.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/localization/package_catalog.py \
  core/localization/src/main/resources/localization/catalog.json \
  /absolute/path/to/output
```

The script writes exact `catalog.json` bytes and a matching
`catalog-manifest.json`. It does not upload or publish. Retained source evidence,
licenses and exclusions are documented in `SOURCES.md`; a successful package does
not certify all source assertions or third-party content rights.

## Automated maintenance

`Refresh catalog sources` runs weekly or manually. It acquires every source in a
temporary directory, pins revisions and query bytes, rebuilds and validates before
changing tracked files. A loss above 10% within an entity/media group or a
Wikidata label/identity response requires manual review. Previously ambiguous
identities cannot silently become accepted by dropping competing claims, including
when unrelated new rows keep the total count steady. A semantic no-op creates no
version; changed content needs a later UTC date than the active package.

Validated changes go to a review branch and PR. There is no scheduled direct merge
to master. Once a catalog change is merged, `Publish catalog` rebuilds the exact
catalog, runs tests and invokes `publish_catalog.py`. It publishes and downloads
the immutable dated catalog plus notices first, then advances the independent
channel manifest. Repeated identical publication is a no-op; collisions and
downgrades fail. Both workflows use a per-workflow concurrency lock.

GitHub release assets cannot be replaced atomically. Updating an existing channel
manifest requires delete/upload, so the manifest may briefly return 404. Upload
failure attempts to restore the prior manifest; if restoration also fails, a
maintainer must restore it. The already published dated catalog remains intact,
and installed clients retain their active data after any update failure.

Legacy ZIP releases and `localization-latest` remain frozen for old clients. They
are never overwritten by the JSON publisher. Production upgrade compatibility and
on-device end-to-end OTA acceptance still require separate validation; public HTTP
asset checks establish only the published bytes and protocol.
