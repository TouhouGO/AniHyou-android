# Transactional catalog updates

Implement real resource management for the approved raw-data/display architecture.
One complete catalog JSON contains names, tags and links. Do not revive the old
multi-file ZIP bundle. The existing fork and all devices/emulators/simulators/adb
remain untouched. Host tests, compilation and artifact inspection only. No public
upload, push, signing-key access or production account changes.

## Catalog host transaction

- Extend `LocalizationCatalogRepository` using an injected `CatalogStorage`
  interface; Android persistence uses an API-24-compatible atomic file adapter.
  Production binding stores files under application-private `filesDir/localization`.
  Tests inject controlled storage and real temporary files where portable.
- The same mutex covers initial load, validation/activation, and reset. Read and
  parse on IO; no main-thread disk work. A complete valid snapshot publishes only
  after durable commit. Readers hold the previous immutable snapshot during work.
- Before installation enforce a 20 MiB maximum, strict UTF-8, exact SHA-256/size
  when remote metadata is supplied, strict catalog schema and date-based version
  `YYYY.MM.DD` with valid calendar dates. Built-in legacy non-date versions may
  load but cannot authorize a downgrade. Current built-in version is 2026.11.07.
- Reject older installations; identical version+hash is a successful no-op;
  identical version+different bytes is rejected. Reset explicitly restores built-in
  data and invalidates any already-started remote installation token.
- Installing after a download uses a captured host generation; if load/reset/
  another install changed that generation while downloading, revalidate and reject
  stale activation rather than undoing the user's last action. Cancellation before
  commit leaves current data unchanged. The short durable commit plus publication
  section is non-cancellable; report outcome truthfully after commit.
- Store both snapshot and status atomically as one host state where practical;
  status exposes version, digest, record count, built-in/installed and last error.
  No Apollo cache clearing or model rewriting. Same-source description requests
  reuse valid cache; changed links/source cause their existing observer to switch.
- Startup tries installed data, validates, falls back to built-in with visible
  diagnostic if corrupt; do not delete evidence automatically. Reset parses the
  built-in successfully before removing the overlay. Storage errors are failures,
  not a false success. AtomicFile recovery is delegated to Android adapter and
  explicitly treated as runtime-platform validation gap until tested permissibly.

## Update protocol

- New manifest endpoint is fixed:
  `https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-latest/catalog-manifest.json`.
  This channel is separate from the incompatible old `localization-latest` channel.
  A 404 is Unavailable, not UpToDate. No endpoint is claimed deployed by this work.
- Manifest schema 1: `formatVersion`, `catalogSchemaVersion`, `version`, `size`,
  `sha256`, `downloadUrl`; dated URL must be exactly
  `https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-vYYYY.MM.DD/catalog.json`.
  Version/URL date/schema/size/digest must agree; 32 KiB manifest cap, 20 MiB catalog
  cap, HTTPS and allowlisted GitHub release redirect hosts only; no credentials.
- Check and download use cancellable HTTP with bounded time, strict UTF-8 and
  manual redirect validation. Local already-newer data remains installed. No
  automatic update/retry loop. Digest integrity is not publisher authentication;
  trusted fixed channel/TLS is explicit, release signing a separate decision.

## Settings and distribution tools

- All users see resource version/source and actions to check for update, download
  offered version, import a local catalog using the Android document picker, and
  restore the built-in catalog. Busy state prevents concurrent UI actions, but the
  repository still enforces transaction concurrency. Never claim an unavailable
  feed contains no update; show errors without losing working data.
- Local imports share size/schema/version/transaction checks. Display format and
  source/schema errors understandably. No broad storage permission or external
  file writes. Release packaging is an offline script validating the catalog and
  emitting catalog.json + manifest; it does not upload anything.

## Acceptance

Host tests for malformed UTF-8/schema, oversize, invalid dates, hash mismatch,
same-version collisions/no-op, downgrade, storage commit/delete failure, cancellation,
startup corrupt fallback, reset vs late download token, snapshot activation order;
update HTTP errors/404, redirect host rejection, size bounds, version agreement and
successful real fake-transport flow; settings errors/operation serialization and
package manifest compatibility. Re-run existing catalog/search/description tests
and host-only FOSS assembly, inspect packaged resource. No device operations.

Actual new-channel publication, live OTA acceptance and device runtime validation
remain outside this local implementation until explicitly permitted and available.
