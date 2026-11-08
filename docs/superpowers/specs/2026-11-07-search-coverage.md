# Chinese search coverage without blocking local results

The user explicitly prioritizes both speed and coverage. This extends the measured
index; fewer candidates are not an acceptable performance shortcut.
The existing AniList transport and models remain raw.

## Source aliases and character matching

- Add optional catalog `searchAliases` entries with `aniListId`, `names`,
  `sourceId`, and optional `mediaKind`. These are search evidence, not display
  translations. The names retain their source bytes/wording. Sources remain
  revision/hash/license pinned. Unknown media kind stays unknown; query candidates
  may enter either type's ID request and AniList's explicit type filter decides.
- Existing typed records or explicit media links may narrow an alias's type.
  Conflicting known types fail validation. Known source identity conflicts are
  quarantined during rebuild; another alias source must not bypass quarantine.
- Integrate the retained `soruly/anilist-chinese` source revision
  `913bb1dcd8f65aac18199d8ee6819f703596658a`, SHA-256
  `95860c98e796f5ae3a9e224224e80858c1caa1ceb549a5a715072be6cc23f3cc`.
  Do not infer a per-record locale or overwrite existing display names.
- Apply deterministic, one-code-point search folding using OpenCC's pinned
  TSCharacters dictionary (`556ed22496d650bd0b13b6c163be9814637970ae`,
  SHA-256 `6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a`).
  NFKC, lowercasing and punctuation removal remain. This is a lossy search key,
  not a language translation; raw display names and source aliases stay intact.
  Preserve old substring matches, support traditional queries and simplified
  queries symmetrically, and keep data/license/derivation reproducible offline.

## Candidate and network lifecycle

- Return the complete ranked local candidate set for search sessions. AniList's
  documented `id_in` maximum is 10,000, not the project's former 500. Use a single
  full candidate filter for normal queries and the existing server pagination.
  Server paging depth is limited to 5,000 results; candidate pools above 5,000
  therefore need partitions of at most 5,000 to remain exhaustible. Never discard
  the rest or fetch every detail before showing the first page. All partitions
  carry the same AniList filters and media type. Cross-partition sort scope must
  be explicit; do not manufacture a globally sorted list by concatenation.
- Local relevance is exact, prefix, substring, then stable ID. AniList cannot
  supply SEARCH_MATCH for ID-only queries; do not label popularity as relevance.
  Preserve deliberate user sorts within their stated scope and describe any
  cross-batch or progressive-source ordering limitation accurately.
- Start online supplementation for every nonblank Han media query, including
  partial local hits. Local results must not wait for Bangumi. Keep the original
  Chinese AniList search as an independent fallback/source.
- Bangumi search uses its official unauthenticated subject search API with type
  constraints, bounded time/body/concurrency and real cancellation. Merge only
  through explicit reverse links. Unmapped subjects may supply distinct original
  names for ordinary AniList text search with the same filters; those results are
  candidates, not newly asserted cross-platform identity links.
- Source cursors and seen IDs belong to a search session. Query/type/filter/sort
  changes cancel stale work; pagination reuses the same session. Preserve already
  shown results when supplementation is slow or fails. Show completion, partial
  failure and retry accurately. Late responses cannot update another generation.
- Supplement discovery is paged on demand with no arbitrary lifetime result cap.
  Concurrency and one-interaction work bounds are allowed and must be resumable.

## Validation

Use source fixtures, an independent exhaustive index oracle, real-catalog recall
examples, controlled asynchronous API fakes and host measurements. Cover aliases,
unknown and conflicting types, malformed/duplicate source data, 501+ candidates,
filtered-empty batches, duplicate sources, retries and cancellation races.
Re-run the fixed host corpus after expansion, recording catalog/hash/count changes
separately from index speed. Do not claim unchanged digest after adding coverage.

No emulator, simulator, adb, device discovery, install, launch, instrumentation,
managed-device tasks or baseline profile generation. No publication was performed during the historical validation. Existing upstream MarkdownUtilsTest failures remain reported.
