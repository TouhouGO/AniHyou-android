# Chinese localization implementation status

Baseline: upstream `927c0f15525d2fffdfd43481e15a6cd67e648901`.
Official upstream revisions are retained only as provenance; see `MAINTENANCE.md` for the current synchronization policy.

## Current implementation

- Pure Kotlin catalog separates MEDIA, CHARACTER, and STAFF identity namespaces.
  Validated snapshots and candidate lists are immutable; lookups do not learn or
  mutate translations. Catalog source metadata and tag keys are preserved.
- The pinned bangumi-data import contains 7,785 unique explicit AniList links with
  zh-Hans display names and source aliases. Shared/ambiguous IDs are quarantined.
  See `SOURCES.md` and the import report for provenance and reproduction.
- Title display is independent of Apollo transport/cache data. The persisted
  setting affects display only; missing entries and disabled display return the
  original text. Initial empty state performs no network translation.
- Connected title surfaces include Explore previews, season lists, charts,
  recommendations, search results, media details, related media, personal list
  layouts, current lists, calendars, studios, people media and favorites.
- The combined snapshot contains 7,785 anime titles, 1,590 manga/light-novel
  labels, 3,103 staff names, 799 character names, and 426 tag labels. These are
  source-backed partial records, not complete AniList coverage. Wikidata labels
  are checked against separate truthy identifier-claim indexes, including entities
  without Chinese labels. Ambiguous identities are quarantined.
- Independent title, people and tag display settings are available when logged
  out as well as logged in. People display always distinguishes character and
  staff IDs. Ordinary Compose credits, details, search, favorites and statistics
  are connected; explicit original-language values remain raw.
- Tag filtering matches raw names and enabled Chinese labels without changing
  `SelectableGenre.name`, selected/excluded keys, saved filters or navigation.
  Two tags may share a display label without becoming the same filter.
- Chinese search uses a declared GraphQL `id_in` variable and a fixed local
  candidate list per generation. Actual sessions keep all local candidates.
  Normal pools use one server filter; pools above 5,000 candidates are partitioned
  to remain within documented paging depth. All filters and per-partition server
  order remain authoritative. Relevance-to-popularity fallback and partition scope
  are disclosed. Native text retains the original AniList search path.
- Search-only aliases from the pinned soruly source add 3,117 searchable media IDs
  without changing display names. The total is 12,492 distinct media candidate IDs;
  3,034 source-alias IDs lack type evidence and rely on server type filtering.
  OpenCC character folding is search-only and works with old Android Unicode tables.
- Request identity is separate from loading/error/display state. Search type
  changes cancel the old flow; a title-language preference that changes server
  sorting starts again at page one. Filter failures do not self-trigger retries.
- Local candidate matching now uses an immutable code-point posting index rather
  than scanning every media alias. Worker-dispatched planning and native-query
  catalog bypass remove unnecessary UI-path work. Filter/sort/page changes reuse
  candidate selections. The fixed-corpus before/after result digest is unchanged;
  host latency and startup/memory tradeoffs are recorded in `SEARCH_PERFORMANCE.md`.
- Media details now support independent, source-backed Bangumi descriptions.
  The bundled snapshot has 8,169 explicit anime links, with conflicting source
  editions excluded. A dedicated unauthenticated client checks returned subject
  ID/type, limits response size and strictly decodes UTF-8. Summaries remain
  literal text; AniList models, descriptions and caches are not rewritten.
- Description requests are cancellable, coalesced and bounded; failures use short
  cooldowns rather than permanent negative caching. Disabling the preference or
  changing mappings immediately publishes fallback state and discards old results.
  Source credit, retry/cooldown and translation of the currently rendered text are
  available. The source does not guarantee every summary is Chinese; unmapped
  manga and unavailable content retain the original description.
- Paged Bangumi search and original-name AniList suggestions run in a separate
  progressive result group. They use explicit reverse links or ordinary text
  searches, never persist inferred identity links. The group has its own loading,
  completion, retry and further-discovery state. Primary results do not wait for it.
- Local user-list search includes complete Chinese/source-alias matches even when
  Chinese display is disabled. Local title sort follows displayed titles, with
  null fallback and stable ID ties. One cancellable worker pipeline observes list,
  query, filter, catalog, preference and sort changes; custom membership survives
  status/progress editing.
- Activity and notification title slots use explicit display projections. System
  notification workers, Glance widgets and Wear load their own catalog state.
  Widget refresh observes preference/catalog changes during the app lifetime;
  outside it, existing RemoteViews refresh on their next scheduled/manual update.
  Wear preferences are local, with no implicit phone-to-watch synchronization.
- Settings supports bounded local import, restore and a dedicated-format update
  client. Persistent commits precede snapshot publication; stale download tokens,
  invalid schemas, source references, empty records, incompatible versions and
  mismatched hashes are rejected. A shared foreground monitor automatically checks
  every 24 hours after success, or after a one-hour failure cooldown. Manual checks
  bypass the interval; download remains manual. Persisted offers never restore
  stale installation generations, and cancellation during commit reconciles the
  installed version. Publication status is recorded in `MAINTENANCE.md`.

## Validation boundaries

Only host JVM tests, Android host unit tests, compilation, and APK ZIP inspection
are allowed in this work. No simulator/emulator/device calls, adb, device discovery,
connected tests, managed-device tests, instrumentation, baseline profile generation,
installation, or launch. Before assembly, inspect its Gradle dry-run graph for any
device-producing tasks.

FOSS debug assembly passed after the coverage extension. Its five APK variants
each contain the exact combined catalog resource. This proves packaging, not Android runtime resource loading,
visual correctness, or device performance. APKs are local debug artifacts, not
published releases or upgrades for an installed production app.

The last recorded slice checks include 25 catalog tests, 22 network tests,
39 domain host tests, 48 search/planner host tests, 11 model tests, 12 local-list
tests, 2 settings-operation tests, 4 detail/presentation tests, 2 display helper
tests, 1 raw tag state/filter test, 57 importer/package tests and 2 OpenCC generator
tests. These exercise the new
contracts, including independent preferences, identity collision checks, repeated
filter selections and range-filter changes after an exhausted second page.
They are not a replacement for real-device rendering or real-service acceptance.

The untouched upstream `core/ui` test suite has three failures in
`MarkdownUtilsTest`: YouTube, youtu.be, and uppercase variants expect linked
thumbnails, while production deliberately emits ordinary links for an upstream
renderer workaround. These failed before localization changes. They are recorded
rather than hidden by changing unrelated test expectations. Other inherited
warnings include non-positional airing format strings and a Koin compiler adapter
warning for Kotlin 2.4.21; assembly nevertheless completed.

## Remaining project work

Feature slices for inline titles, local list search, progressive search, resource
transactions and independent Widget/Wear lifecycles are implemented. Final
integration review, host verification and packaging results are recorded in the
completion section when available. Source coverage remains partial, especially
people and manga links; this is a source assertion/coverage boundary, not a claim
that every AniList entity has a Chinese translation.

Source-refresh PRs, a separate JSON publisher and reviewed upstream-sync workflow
are implemented; see `MAINTENANCE.md` for current remote verification. Production
signing, on-device OTA acceptance and visual/runtime/performance device validation
remain unperformed. The user prohibited simulator
operations; this implementation has not operated devices or copied previous-fork
code or aggregate resource files. See `ARCHITECTURE_DECISIONS.md`,
`SEARCH_PERFORMANCE.md` and `CATALOG_UPDATES.md` for tradeoffs and exact boundaries.

## Final local validation checkpoint

The historical host batch passed on 2026-11-07, followed
by a focused search test/app compile after the last notice-timing fix and another
FOSS Debug assembly. Final per-module counts are the counts listed above. Python
rebuild/package tests passed 57/57; OpenCC generation tests passed 2/2. Rebuilding
the retained sources reproduced the shipped catalog and report byte for byte.
The inspected packages contained the documented source notices.
Whole-branch static review found no concrete P1/P2 issues; see `REVIEW.md` for
the reviewed paths, low-priority maintainability note and remaining evidence gaps.
The subsequent completion audit also verified the separate filter/sort planning
probe and 30-sample warm decode/index P50/P95. Every measured result is consumed,
the catalog/corpus digest stays unchanged, and no timing assertion gates builds.
Final targeted reruns of `TitleDisplayTest` (2) and `GenresTagsUiStateTest` (1)
passed as well; together with the 163-test batch this is 166 relevant host tests.

### Deliberate original-text boundaries

| Surface | Behavior |
| --- | --- |
| Preferred media/character/staff titles with matching IDs | Chinese projection when its display preference is enabled |
| Explicit original/native names and alias details | Preserve source text so the original remains inspectable |
| Studio names | Original AniList text; no separate studio-name translation source |
| Episode titles, threads, comments and free-form activity text | Original content; no automatic text rewriting |
| Deleted-media notifications without a safe media ID | Original supplied text, with no guessed title association |
| Missing/conflicting identity or missing Chinese label | Original fallback; no name-only identity assertion |

The assembly dry run contained 648 tasks and no install, connected, managed-device,
instrumentation or baseline-generation tasks. Five APK variants were built and
each was checked as a ZIP for exact catalog bytes, the OpenCC binary and full
OpenCC/soruly licenses plus `localization/NOTICE`. The universal debug APK was
27,729,890 bytes at this checkpoint. No APK was installed or launched.
Wear Debug assembly also passed after its 164-task dry run, and its APK contains
the same verified catalog and attribution resources. See `ARTIFACTS.json` and
`DELIVERY.md` for the exact six local APK paths, hashes and scope.

```sh
./gradlew :core:localization:test :core:network:test \
  :core:domain:testDebugUnitTest :core:model:testDebugUnitTest \
  :feature:explore:testDebugUnitTest :feature:settings:testDebugUnitTest \
  :feature:usermedialist:testDebugUnitTest :feature:mediadetails:testDebugUnitTest \
  :app:assembleFossDebug :wearos:compileDebugKotlin \
  --offline --no-daemon --console=plain --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx3g \
  -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
```

This local development build and host validation do not establish Android runtime
resource loading, UI behavior, network SLA, production signing compatibility or a
published OTA channel. The inherited three MarkdownUtilsTest failures remain
outside this successful targeted batch and remain documented above.

## Publication follow-up

The 2026-11-07 checkpoint above is historical build evidence. The 2026-11-08
follow-up integrates the next 11 upstream commits and adds foreground resource
checks, source-refresh review PRs, immutable resource publication and upstream-sync
review branches. Independent review identified and closed cancellation-during-commit
offer cleanup, per-media-kind shrink detection and disappearing identity-conflict
evidence. Historical host checks are recorded in `MAINTENANCE.md`; earlier APK hashes must not be attributed to current source.
