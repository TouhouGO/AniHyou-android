# Resource maintenance and in-app updates

The former migration and publication procedure is superseded by the official
fork policy in `docs/localization/MAINTENANCE.md`. The functional resource
and update requirements below remain design guidance; they are not evidence of
current deployment or authorization to publish.

## Resource automation

- New JSON channel catalog-latest remains separate from old ZIP localization-latest.
  The channels are independent; publication must not modify unrelated assets.
- A manual/scheduled source refresh pins current bangumi-data and soruly revisions
  plus complete Wikidata label/identity query bytes. Validate every source before
  changing the production manifest/catalog; failed/empty/capped responses keep the
  previous data. Content loss guards report suspicious changes for review.
- Source refresh opens a PR. A merged catalog change triggers offline rebuild,
  tests, packaging and publication. No scheduled unreviewed code merge to master.
- Publish and verify immutable catalog-vYYYY.MM.DD assets first; only then update
  catalog-latest/catalog-manifest.json. Reject same-version/different-byte assets,
  downgrade, partial publication and malformed manifests. Re-running is idempotent.
  A shared Actions concurrency group prevents competing publishers.
- Publish the first already-reviewed catalog to the new channel and verify both
  downloaded manifest and catalog bytes without invoking an Android runtime.

## In-app checks

- Add persisted enable/disable for automatic resource checks, default enabled after
  preferences load. Check on foreground entry, at most once per 24 hours after a
  successful response; use a shorter bounded retry window for unavailable/failure.
- Automatic checks never block first-screen rendering, auto-download, auto-install,
  mutate raw AniList data or require login. Main UI offers a nonblocking link to
  resource settings when a new version is found. Settings exposes last check and
  available version with existing manual download/import/reset controls.
- Manual checks bypass automatic interval but share in-flight work/state. Disabling
  checks cancels or suppresses late automatic results. Reset/import invalidates
  stale offers; downloads always use current generation checks.
- Clock rollback, offline errors, cancellation, concurrent foreground/manual checks,
  default/disabled state and process restart cannot cause a hot retry loop.
  The app does not promise checks while fully closed; foreground polling is explicit.

## Validation and scope

Use relevant host tests, fake transports, script tests and safe task graphs. Historical
validation did not use devices or signing secrets. Actual deployment and runtime
acceptance must be verified separately.
