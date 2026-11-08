# Chinese search latency and recall

The user explicitly prioritizes both fast Chinese search and broad coverage,
after repeated optimizations in the prior fork. Performance work must not improve
reported timing by searching fewer entries, dropping aliases, truncating recall,
or silently removing online enrichment. The original redesign and no-simulator
constraint remain binding; all measurements here are host JVM measurements and
must not be described as Android-device latency.

## Requirements

- Preserve a fixed corpus covering exact names, sequels, one/two-character broad
  terms, punctuation, simplified/traditional source aliases, manga and cold misses.
  Record local source coverage separately from query behavior and remote discovery.
- Measure catalog decode/index construction, warm local matching, repeated query,
  and filtered/sorted session construction independently. P50/P95 must use the same
  data and corpus before/after; return values must be consumed and checksummed.
  Never time only a cached answer and label it uncached matching.
- Local candidate lookup must run off the main/UI dispatcher. Native text searches
  should not wait for unrelated catalog load when they do not use local matching.
  Explicit search changes cancel stale planning and late results cannot commit.
- Any index optimization must preserve the complete local matching set, exact /
  prefix / substring rank and deterministic IDs under normalization. A query cap
  is a transport budget, not a proof of complete search; it remains visible until
  a full-result batching/pagination design replaces it.
- No query-time index rebuild, mutable title learning or unbounded caches. Keep
  original data and typed identities untouched. Prefer measured, bounded indexes
  with a clear memory/load tradeoff over ad hoc per-keyword cases.
- Online Bangumi supplementation remains an outstanding delivery requirement.
  It must not block the local first-screen path nor change a result set halfway
  through existing server-offset pagination. Missing local/online coverage must
  be shown honestly, not counted as fast successful search.

## Verification scope

Regression tests prove candidate sets/rank are unchanged, filtering/sort changes
reuse the candidate list where appropriate, no index growth on repeated search,
native queries do not invoke catalog loading, and planning uses the injected worker
dispatcher. A dedicated repeatable host probe records baseline/final metrics.
No adb, emulator/simulator discovery, install, launch, instrumentation, device
benchmark or modification of another project's runtime.
