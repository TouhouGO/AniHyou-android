# Typed localization coverage

## Approved direction

Continue the user-approved raw AniList data plus separate localized display and
explicit Chinese search architecture. This slice expands the existing foundation,
not a new rewrite.

User constraint: do not operate any emulator or simulator. This project also
avoids all device/adb discovery, connection, install, launch, instrumentation,
managed-device, and baseline-profile generation commands. Validation is host-only.
No push, publication, signing credentials, or remote account modifications.

## Deliverables

1. Independent title, people, and tag display preferences, each persisted locally.
   Initial preferences are disabled until read; no network requests or Apollo
   cache invalidation are caused by display settings.
2. Typed character/staff display resolution by AniList ID, separate from MEDIA.
   Unknown or missing IDs preserve original text. No name-only identity guessing,
   no automatic Japanese-name conversion, no mutable learned translations.
3. Explicit tag labels keyed by the exact original AniList tag name; only display
   changes. Filter selection, exclusion, saved filters, navigation and API requests
   continue using raw keys. Identical translated labels remain distinct options.
   Tag option search matches both raw text and enabled localized display text.
4. Connect ordinary Compose title surfaces still missing after the foundation:
   personal list layouts, current list, calendar layouts, studios, recommendations,
   character/staff media, favorites; connect person names in details, media credits,
   voice-actor sheets, search, favorites and statistics. Explicit English/native
   detail fields, URLs and server-sorted identity fields remain unchanged.
5. Add reproducible source adapters and a usable attributable offline dataset for
   people and manga titles where available. Only exact external IDs and explicitly
   labeled simplified Chinese values are accepted. Ambiguous/shared IDs are
   quarantined, source bytes/checksum/query/revision recorded. Do not treat a
   bounded sample as complete data; do not silently publish truncated/error input.
6. Preserve the pinned anime catalog and extend its schema compatibly with an
   optional tag-label list. Loader, merger and provenance checks must agree.

## Data and state contracts

- A catalog remains a single immutable snapshot. Records retain source IDs; source
  definitions survive parsing and can be inspected without exposing mutable maps.
- Optional `tags` contains `{rawName, displayName, sourceId}`. Duplicate raw keys,
  missing sources, blank values and invalid types are rejected. Missing `tags`
  remains compatible with the original schema-version-1 anime bundle.
- Public pure methods resolve media/character/staff names and tag labels. They
  never return translated text as an identity key. Display state is delivered by
  a tracked CompositionLocal, not global mutable flags or per-item network jobs.
- Local data generation is separate from runtime loading. Acquisition commands
  save exact bytes and evidence; import/merge commands run offline and are tested.
- Source preferences are explicit and deterministic. An existing validated anime
  source is not overwritten by lower-quality unrelated data. Multiple QIDs for
  one typed AniList ID or conflicting preferred labels are excluded and reported.
- A source 429, timeout, missing payload, or cap-sized response does not become a
  successful empty snapshot. Honor service cooldowns; no request loops.

## Verification

Write failing behavior tests before new production logic: namespace collisions,
independent toggles, exact fallback, mutable inputs, duplicate tag labels with
different raw keys, Chinese tag filtering and raw selections, conflicting source
IDs, deterministic import/merge and damaged/truncated input. Compile all affected
Android modules and assemble FOSS debug only after checking the task graph. Inspect
APK resources. No visual or runtime-device claims without permitted evidence.

## Remaining slices

This scope expands display/data coverage, not the final project gate. Inline
activity/notification text, Glance/Wear lifecycle, local user-list Chinese search,
online description/identity enrichment, OTA and release tooling, live service
contracts and permitted final acceptance remain work items. Existing upstream
genre translations should be retained, not needlessly duplicated as tag data.
