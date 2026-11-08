# Chinese search performance and recall

The user requires both fast search and broad coverage. A lower result count,
shorter candidate cap, omitted aliases or disabled online supplementation must not
be presented as a performance improvement.

## Current local index

The immutable catalog indexes every normalized display name and alias by Unicode
code point and media kind. Each posting contains each record once in numeric ID
order. A query uses its rarest code-point posting as a prefilter, then checks the
complete normalized names. Exact, prefix and substring ranks remain unchanged;
ordered result buckets avoid sorting the complete match set on every query.

The current catalog includes source-attributed search aliases and a pinned,
search-only OpenCC character fold. Display values stay unchanged. Actual sessions
use `searchAllMedia`; the 500-result helper remains only as a bounded utility for
callers that explicitly request a prefix. There is no runtime title learning,
query-time index rebuild, or result cache hiding matching cost.
An independent exhaustive test oracle checks randomized records, aliases, repeats,
punctuation, one/two-character queries and supplementary Unicode.

Candidate selection runs on a worker dispatcher, not the UI dispatcher. Native
text queries bypass catalog loading. Within one search sequence, filter/sort and
page changes reuse the selected candidate IDs; explicit query/type reset can use
the current catalog. Delayed planning results cannot overwrite a newer request.

## Historical same-input index measurements

Command:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew \
  :core:localization:benchmarkSearch \
  -PsearchBenchmarkOutput=/path/to/search-benchmark.json \
  --offline --no-daemon --console=plain
```

The historical probe used the production catalog and bounded search method, 17 fixed queries,
150 measured samples per query after warmup, no network, and no result cache. It
consumes all returned IDs and reports a candidate digest and checksum. Baseline
was recorded before the index change; the second optimized run reverified it.

Environment: macOS arm64, Java 21.0.10, 512 MiB maximum heap. Catalog version
`2026.11.07`, 5,715,616 bytes, SHA-256
`191846dc28e72dccbdbf3110a97c6203bb2e2de8e9ec6cfac638c7096df27309`.

| Query | Complete local matches | Baseline P95 ms | Optimized verification P95 ms |
| --- | ---: | ---: | ---: |
| Star character (one-character query) | 293 | 0.973 | 0.124 |
| Common connector character (one-character query) | 965 | 0.966 | 0.351 |
| Naruto prefix | 16 | 0.955 | 0.026 |
| Monogatari substring | 117 | 0.960 | 0.055 |
| Full Naruto title | 16 | 0.978 | 0.023 |
| Detective Conan | 38 | 0.937 | 0.016 |
| Missing title | 0 | 0.560 | 0.012 |

The baseline and both optimized runs have identical result digest
`efa10cdcc12eed0464cac4dc5190c99eede68207e86a54f1eb55a10bbb8622d4`
and consumed checksum `5146930260`. This proves equivalence for this corpus, not
universal recall correctness. The randomized oracle covers additional cases.

The tradeoff is additional postings memory and index construction. First decode
plus index was 416.7 ms before, 420.1 ms in the first indexed run, and 493.2 ms in
the verification run. Warm decode/index median was 137.1 ms before and 148.9 /
161.0 ms afterward. These short runs do not establish a reliable startup delta.
Retained and peak heap costs have not been profiled. All catalog loading remains
off the UI dispatcher. No Android device, emulator, or simulator was used.

## Expanded catalog with complete candidates

`benchmarks/2026-11-07-expanded-full.json` measures the current classifier and
complete candidate set after alias expansion, with the same 17 queries and 150
samples. Catalog size is 7,471,994 bytes, SHA-256
`be12dfeaad442016528d5a83ed648266c04c7cccbcb763dc260070a85e398a9a`.
The source adds 8,683 alias entries and 3,117 searchable media IDs: 12,492 distinct
media IDs total. Of the new source's aliases, 3,034 IDs lack independent type
evidence and are candidates for either query type until AniList filters them.
These counts are source assertions, not verified coverage percentages.

| Query | Complete local candidates | Current host P95 ms |
| --- | ---: | ---: |
| Star character | 369 | 0.127917 |
| Common connector character | 1,212 | 0.382875 |
| Naruto prefix | 17 | 0.025042 |
| Monogatari substring | 158 | 0.073500 |
| Full Naruto title | 17 | 0.005709 |
| Detective Conan | 42 | 0.003958 |

All reported candidates were returned, with no 500-result cutoff. Current full
result digest is `929f80124d6b0ce215089d41092bcf58502bdf3f4b7ede6bd6d592575f9c22d8`.
The changed digest is expected: both source coverage and normalized character
equivalence changed. Do not compare this run with historical timings as an
identical workload. The current probe records one first decode/index observation
(657.137583 ms), discards three in-process JIT warmups, then measures 30 warm
decode/index samples: P50 180.014625 ms and P95 203.502333 ms. This is not a
cold-start P95. Every measured snapshot is consumed outside its timer into checksum
`8465054243557549056` using version, 13,277 records, 6 sources and the hash of the
representative title for media ID 1735. Additional coverage increases
startup/index memory work; retained/peak heap and Android first-result latency
have not been profiled.

## Filter/sort session planning

`benchmarks/2026-11-07-session-planning.json` measures the `feature:explore`
`MediaSearchSessions` and `MediaSearchRequest` paths against the same bundled
catalog and ordered 17-query corpus. Selection uses complete candidates and is
never capped. The probe records 30 warmups and 150 samples per query for each
phase, consumes every returned ID, and emits per-query and per-phase checksums.

Command:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME="/path/to/JBR" ./gradlew \
  :feature:explore:benchmarkMediaSearchPlanning \
  -PmediaSearchPlanningOutput=docs/localization/benchmarks/2026-11-07-session-planning.json \
  --offline --no-daemon --max-workers=2 --console=plain
```

The probe task runs from the repository root, so the relative output path above
resolves under the tracked `docs/localization/benchmarks/` directory.

The decoded catalog and immutable index are reused, so these numbers exclude
catalog decode/index. “Fresh” means a fresh session and selection over that warm
decoded snapshot, not an Android cold start. The change fixture alternates a
sort-only `TITLE_ROMAJI` plan and a populated-filter `SEARCH_MATCH` plan whose
effective request sort is the production `POPULARITY_DESC` fallback. Both use
one selected candidate list and preserve its identity.

Across all 2,550 samples in each phase, the single-process phase summaries were:

| Phase | P50 ms | P95 ms |
| --- | ---: | ---: |
| Warm cached page plan | 0.000708 | 0.005417 |
| Changed filter/sort plan, reused candidates | 0.000833 | 0.003041 |
| Fresh selection | 0.008500 | 0.146041 |
| Fresh selection plus plan | 0.008958 | 0.109291 |

The aggregate mixes two native queries that bypass local selection with Han
queries that exercise the catalog. Representative per-query P95 results are:

| Query | Warm plan | Changed plan | Fresh select | Fresh select + plan |
| --- | ---: | ---: | ---: | ---: |
| Star character | 0.014708 | 0.006958 | 0.320250 | 0.068875 |
| Common connector character | 0.002750 | 0.003000 | 0.331417 | 0.125416 |
| Naruto prefix | 0.000625 | 0.000875 | 0.015500 | 0.012791 |
| Monogatari substring | 0.000875 | 0.000917 | 0.031458 | 0.027667 |
| Detective Conan | 0.000875 | 0.001000 | 0.018958 | 0.018042 |
| Missing native title | 0.002500 | 0.001250 | 0.003500 | 0.003958 |

The normal direct catalog probe produced the same result digest
`929f80124d6b0ce215089d41092bcf58502bdf3f4b7ede6bd6d592575f9c22d8`. Of 17
queries, 15 catalog-backed queries had session IDs and counts equal to a direct
catalog lookup. `Re:Zero` and the ASCII miss intentionally bypass local
selection; `Re:Zero` therefore reports 2 direct catalog matches but 0 session
local candidates. This is product behavior, not a recall regression. The
planning benchmark consumed checksum is `4752639926312736364`.

These phases run sequentially in one JVM, so JIT order and timer resolution can
make a later phase appear faster than an earlier one. The results do not support
a comparative latency claim between phases, an Android latency claim, or a
startup claim. They are host-only evidence that selection, cached planning, and
filter/sort replanning are separately measurable without changing result counts.

## Network paging and coverage boundaries

The former 500-ID limit was local, not an AniList requirement. The pinned
[official Page reference](https://github.com/AniList/docs/blob/03281c0a4bbf0c7f2097e0c935cddaed1096aa65/docs/reference/object/page.md)
documents 10,000 IDs per filter and 5,000 results of paging depth. Sessions use
one full filter through 5,000 candidates and partition larger sets at that depth.
Each partition keeps the chosen server filters/order. Cross-partition order is
explicitly scoped; no candidates are discarded at a lifetime cap. One anonymous
1,000-ID/three-row probe returned HTTP 200; this is not a production latency SLA.

Online Bangumi supplementation and original-name AniList queries run in a separate
result group, with explicit retry and further discovery. Their results do not
claim a globally comparable relevance score. ID-only relevance uses a disclosed
popularity fallback. Network availability and anonymous upstream permissions can
limit recall. Text-query fallback remains subject to the service paging depth.

The old inspected resource has 15,696 untyped numeric media keys; the new searchable
set has 12,492 typed or explicitly unknown-type candidates. Raw ID overlap is not
a recall/accuracy metric. Known ambiguous identities remain quarantined. Generic
character folding covers traditional/simplified spellings, not semantic synonyms
or all regional phrases. Missing aliases require source work, not keyword-specific
IDs. No exact-match shortcut discards matching sequels. Host tests and measurements
do not replace later device/network acceptance.
