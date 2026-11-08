# Chinese localization foundation

## Decision and scope

The user approved a fresh implementation on the new upstream checkout, preserving
raw AniList data and adding a separate display layer and explicit Chinese search.
This first implementation is a vertical slice, not feature parity with the old
fork: a typed, immutable local catalog; reproducible title data; reactive title
display on media cards, search and details; a persisted title-display preference;
and local Chinese candidate search using the existing AniList filters and paging.

The initial validation was host-only; it did not use devices or signing credentials.

## Architecture

- `core/localization` is a pure Kotlin/JVM module, independent of Android, Apollo,
  HTTP clients, and Compose. It owns typed identities, validated immutable catalog
  snapshots, pure display resolution, and deterministic alias candidate planning.
- An AniList entity is identified by platform, kind, and positive ID. Media,
  characters, and staff never share a lookup namespace. Tags retain their raw key.
- Catalog records contain a selected display title and searchable aliases with
  source metadata. Name matching never establishes cross-site identity. Runtime
  lookups cannot mutate records or learn new display titles.
- Catalog parsing happens off the UI thread. Only a fully validated snapshot is
  published. Missing or invalid data falls back to upstream text and an observable
  load error, without modifying the upstream cache.
- Compose receives a display context and resolves strings by ID. Generated Apollo
  models, request filters, navigation, and mutation/cache writes remain raw.
- Search candidates are selected once per new search, including exact, prefix,
  and substring matches, deduplicated and type-filtered. The candidate snapshot is
  fixed across server pages. A new query/filter/sort or explicit refresh may start
  a new search; merely toggling title display does not request network data.
- `id_in` is a declared GraphQL variable. There is no HTTP query/response rewriting.
  Existing non-local searches and filters keep their upstream behavior. Server
  sort is authoritative; `SEARCH_MATCH` without a text query uses an explicitly
  disclosed popularity fallback. Remote ranking changes are not claimed frozen.
- Candidate limits and local-only source coverage must be disclosed, not presented
  as exhaustive AniList search. No post-pagination content filtering or reordering.

## Data policy

Import a pinned, attributable upstream source, not the old aggregate or its code.
Prefer explicit AniList references and existing zh-Hans names from bangumi-data;
retain source aliases but never invent Chinese translations from Japanese Kanji.
Record source revision, input SHA-256, license, and output record count. Reject
ambiguous/conflicting IDs and invalid records rather than silently choosing one.
Keep the source license/attribution. This is a local development baseline, not a
claim of complete translation coverage or a new public release.

JSON plus an in-memory immutable index is the initial implementation, not a claim
of optimal performance. A fixed candidate budget of 500 limits query size; any
truncation is explicit. No database or transliteration dependency is introduced.

## Acceptance

1. Same numeric ID in media/character/staff resolves independently; missing data
   and disabled title display return the exact original text.
2. Repeated lookup/search is idempotent. Source collection mutation cannot alter
   an already published snapshot. Conflicts and invalid schema do not activate.
3. Exact search keeps matching sequels; punctuation normalization is consistent;
   duplicates are removed; empty/native queries and candidate overflow are tested.
4. Existing raw models, navigation IDs, and filter/mutation values are unchanged.
   Preference or catalog display updates do not subscribe to a new network call.
5. A search uses the same candidates across pages, cancels stale work when changing
   search type/query, and never submits an empty ID filter as an unrestricted query.
6. Core JVM tests, changed-module host unit tests, and FOSS debug compile/assembly
   are run where the host toolchain permits. No device-based verification.

## Deferred without removing the capability goal

Full people/tag display coverage, live Bangumi/Wikidata enrichment, descriptions,
OTA installation/publishing, Widget/Wear localization, production signing, and
device performance/visual acceptance belong to later slices. Do not add nonworking
settings or claim these complete. Preserve upstream behavior on untouched surfaces.

## Alternatives

HTTP JSON rewriting was rejected as the baseline because it conflates transport,
display, identity, paging, and cache writes. Typed DTO rewriting is useful locally
but must not replace raw models used for business operations. The approved approach
keeps raw data and resolves display text separately, with explicit search planning.
