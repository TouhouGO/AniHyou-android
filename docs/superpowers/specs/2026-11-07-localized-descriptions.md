# Source-backed online descriptions

Continue the approved raw-data/display architecture. This slice adds online
Bangumi descriptions to media details without modifying AniList models, titles,
or caches. The user wants ongoing implementation to completion. Existing checkout
and all emulator/simulator/device/adb actions remain out of scope; use host tests
and compilation only. No public release, remote writes, or signing secrets.

## Identity and data

- Extend catalog schema 1 with optional `links`, default empty for old bundles.
  A link has `mediaKind: ANIME|MANGA`, `aniListId`, `bangumiSubjectId`, `sourceId`.
  IDs are positive Int, source exists, and links are unique by AniList media
  identity and Bangumi subject within a media kind. No name-based mapping.
- `MediaIdentity(kind: MediaKind, id: Int)` and `BangumiLink` are immutable.
  `LocalizationSnapshot.mediaLink(identity)` returns an optional exact link.
- Build anime links from the fixed bangumi-data snapshot's paired AniList and
  Bangumi references, independent of whether a Chinese title exists. Require the
  declared site namespaces. Quarantine one-item multi-ID, either direction
  conflicting mappings, and duplicated source editions. Runtime verifies subject
  ID and type again. This source supplies anime only; manga requires a separate
  verifiable mapping, never an ID offset or guessed anime link.
- Link addition does not change existing record/tag/source provenance. Integrate
  deterministic link generation into offline rebuild and report counts/exclusions.

## Network and cache

- A dedicated `BangumiDescriptionApi` in core/network receives a validated link,
  uses fixed HTTPS `/v0/subjects/{id}`, a project User-Agent, and no AniList token.
  Use cancellable asynchronous OkHttp calls, finite timeout and 256 KiB body cap.
- Verify integer response `id` equals requested subject and `type` matches
  ANIME=2 or MANGA=1. Only a nonblank string summary is Available. Missing/blank
  summaries are NoContent; wrong ID/type, malformed JSON, oversize, HTTP failures,
  timeouts, network errors and rate limits remain explicit failures.
- Preserve summary text and line breaks exactly, with no HTML/Markdown evaluation.
  Never mutate catalog titles or learn aliases from the description response.
- `ChineseDescriptionRepository` in core/domain coalesces same-key calls with a
  per-key suspending mutex and caches only bounded results: 256 entries, positive
  24 hours, confirmed NoContent 15 minutes, transient failure cooldown 30 seconds.
  429 respects parsed Retry-After (delta/date) within 1 second to 24 hours, default
  60 seconds. No auto-retry loops. Cancellation is propagated and never cached.
  Cache key includes typed identity, target and source revision/hash. A canceled
  leading request releases the key so another still-interested caller can retry;
  no global fire-and-forget scope is introduced.

## Reactive detail integration

- Add independent persisted `chineseDescriptions` preference, default true after
  load to retain the existing enhancement behavior, initial false until read.
  Settings explain that this option uses Bangumi online and falls back to AniList.
- A tested domain observer/controller combines verified detail identity, enabled
  state, catalog snapshot and explicit retry generation. Switching any request
  identity cancels prior work and rejects late results. Repeated raw detail state
  or title/tag/people preference changes do not issue additional requests. The
  relevant generation is the typed identity, link and source revision/hash;
  equivalent snapshot replacements and name/tag-only changes reuse ongoing work.
- States: Disabled, Unmapped, Loading, Available(summary, subjectId), NoContent,
  Failed(kind, retryAt). Loading/error/unmapped preserve original description;
  only current Available selects supplemental text. Source and retry/cooldown are
  visible. Original DTO continues serving mutations, links and cache operations.
- UI renders Bangumi summary as literal AnnotatedString. Existing AniList HTML
  rendering remains for the raw fallback. External translate action uses the text
  actually shown. Available state includes a fixed-source Bangumi link.

## Tests and scope

Test loader compatibility/typed IDs/duplicates, importer ambiguity/no-title links,
HTTP ID/type/limit/Retry-After/raw-text/cancellation, bounded cache expiry/coalescing,
failure then recovery and no network before cooldown; observer toggle/mapping/
retry cancellation and raw-state preservation. Host-only feature integration tests
and FOSS assembly with device-task graph check are required.

No completeness claim for mapping or Chinese summary coverage. OTA, runtime online
search supplementation, inline activity/notifications, Glance/Wear and final data
acceptance remain project gates after this slice.
