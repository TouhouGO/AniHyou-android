# Localization data sources

## Initial anime title baseline

Source: [bangumi-data](https://github.com/bangumi-data/bangumi-data)

- Revision: `94ea82be75d9e617c7970bb0fd0670ae5c60e53d`
- Input: [dist/data.json](https://raw.githubusercontent.com/bangumi-data/bangumi-data/94ea82be75d9e617c7970bb0fd0670ae5c60e53d/dist/data.json)
- Input SHA-256: `c21466156d528eea24f2b32c7b36c9ec11273801037efdc698eccf3a475a893f`
- License declared by the source: [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/)
- [Pinned source license statement](https://github.com/bangumi-data/bangumi-data/blob/94ea82be75d9e617c7970bb0fd0670ae5c60e53d/README.md#license)

Credit: bangumi-data contributors. Changes made by this project: selected explicit
AniList anime references and existing simplified Chinese names, excluded shared
and one-to-many IDs requiring identity review, preserved source language title aliases, and converted the
records into the versioned AniHyou catalog schema. No endorsement is implied.
The source's license declaration is not a blanket verification of every upstream
content right. No synopses, artwork, character biographies, or scraped content are
included in this baseline.

This source provides partial anime title coverage, not a complete AniList database.
Missing Chinese labels and ambiguous links fall back to original AniList text.
Manga and people are supplied separately by typed Wikidata imports. Excluded shared
anime IDs are listed in `bangumi-data-import-report.json`.
Simplified Chinese supplies the display name; original and other translated names
are retained as search aliases. The report also lists IDs from items that claim
multiple distinct AniList IDs.

## Typed Wikidata labels

The source snapshots were retrieved on 2026-11-05 and include only explicit
`zh-hans` and `zh-cn` labels with direct AniList external identifiers:

| Property | Identity namespace | Label rows | Identity-claim rows | Accepted records |
| --- | --- | ---: | ---: | ---: |
| [P11227](https://www.wikidata.org/wiki/Property:P11227) | STAFF | 4,227 | 15,276 | 3,103 |
| [P11736](https://www.wikidata.org/wiki/Property:P11736) | CHARACTER | 931 | 6,165 | 799 |
| [P8731](https://www.wikidata.org/wiki/Property:P8731) | MEDIA / MANGA | 1,963 | 9,121 | 1,590 |

Credit: Wikidata contributors. Wikidata structured data is offered under
[CC0](https://www.wikidata.org/wiki/Wikidata:Licensing). These source assertions do
not independently prove every mapping or translation correct. The data covers
the selected language labels and properties visible to WDQS at retrieval time,
not every AniList entry; query results below the cap do not establish universal
coverage or currentness. Sources may be incomplete or later corrected.

The importer accepts `zh-hans` before `zh-cn`, preserves the other selected label
as an alias, and cross-checks labels against a separate property-only identity query.
That query uses truthy `wdt:` claims without a label join, so competing entities
without a selected Chinese label still cause quarantine. It excludes deprecated and
other non-truthy statement ranks. The importer quarantines multiple-QID claims, one-QID multiple-ID claims, and
conflicting best-language labels. IDs are used directly without offsets. Staff
can include people, groups and companies. P8731 covers manga/light novels, not
animation. No Japanese-name conversion or name-only matching establishes identity.

Compressed original response bytes, complete label and identity query texts, exact retrieval times,
row counts and hashes are retained in `data/localization/sources/`. The rebuild
checks each query SHA-256, expected property, exact template and limit. These hashes
provide integrity pinning, not publisher authentication. Identity metadata also
retains the SHA-256 and byte count of the original compressed transfer separately
from the decompressed JSON retained in the deterministic gzip file. HTTP cookies
and unrelated diagnostic headers are excluded. Earlier timeout/error responses
were not imported. Queries used a 50,000-row cap; an at-cap response fails import.

## Tag display labels

The project independently authored 426 simplified Chinese display labels. Their
raw keys come from a historical reference inventory, not a successful current API
snapshot; see `TAG_TRANSLATIONS.md` for the precise scope and exclusions. These
labels are shipped under the project license and do not imply official AniList
translations. Raw tag keys remain unchanged for filtering and persistence.

## Rebuild the combined catalog

The complete inputs are retained, so normal rebuilds require no network access:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/localization/rebuild_catalog.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s scripts/localization -p 'test_*.py' -v
```

The source manifest is `data/localization/sources/catalog-sources.json`.
`docs/localization/catalog-report.json` records every source import, exclusions,
typed counts and the final exact catalog SHA-256. The combined snapshot has
13,277 entity records and 426 tag labels. The historical
`bangumi-data-import-report.json` describes only the initial anime component,
not the combined output.

## Bangumi description links

The catalog also contains 8,169 paired AniList-anime/Bangumi-subject links from
the same pinned bangumi-data input. They are generated independently of translated
titles, so a missing display label does not prevent an otherwise explicit link.
The importer rejects repeated source editions and ambiguous IDs in either
direction, including a competing item that lacks the other platform reference.
It never infers links from names or offsets.

These source assertions are not a verification of every actual subject. Before
showing online content the client must validate the returned subject ID and type.
The source's anime namespace does not supply manga links; those remain absent
until a separate verified source is integrated. No synopsis content is bundled.
Online summaries are displayed as literal text with Bangumi attribution, without
executing markup. The current API contract is documented by
[Bangumi's pinned OpenAPI](https://github.com/bangumi/api/blob/65d29cff2331e08c0110d30d515f7f1b6488f845/open-api/v0.yaml).

## Reproduce only the anime component

Download the exact input URL above to a local file. The importer performs no
network requests, and normal app builds never refresh external data.

```sh
python3 scripts/localization/import_bangumi_data.py \
  --input /path/to/bangumi-data.json \
  --output /path/to/anime-component.json \
  --report /path/to/anime-component-report.json \
  --revision 94ea82be75d9e617c7970bb0fd0670ae5c60e53d \
  --sha256 c21466156d528eea24f2b32c7b36c9ec11273801037efdc698eccf3a475a893f

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s scripts/localization -p 'test_*.py' -v
```

The SHA-256 checks the pinned input; it is not publisher authentication. The
source revision, data hash, license, and record source ID remain in the catalog.
The report contains `catalog_sha256`, the SHA-256 of the exact generated catalog
bytes. Both files are staged before replacement. Each replacement is atomic, but
the pair is not atomic against process interruption or a crash. If the second
replacement fails during normal execution, the importer attempts to restore the
previous catalog; a failed rollback reports the saved backup path when available.
After interruption or rollback failure, compare the catalog hash to the report
and rerun the import to recover a matching pair.


### soruly/anilist-chinese 搜索别名

固定提交 `913bb1dcd8f65aac18199d8ee6819f703596658a` 的 `anilist-chinese.json` 原始字节 SHA-256 为 `95860c98e796f5ae3a9e224224e80858c1caa1ceb549a5a715072be6cc23f3cc`，以确定性 gzip 保存于 `data/localization/sources/soruly-913bb1dc.json.gz`。上游仓库声明 MIT，原始版权和许可文本保存在同目录 `soruly-MIT-LICENSE`；该声明不能单独证明各上游内容的权利。来源含繁体及非中文名称，没有逐条媒体类型或语言标签。仅将原始 title 和 synonyms 用作搜索名称，不覆盖显示名称；已有记录或映射提供类型证据，否则保留未知类型。Bangumi 数据和 Wikidata P8731 的冲突或歧义 ID 被隔离。

最终接受 8,683 条来源别名，补入 3,117 个此前没有媒体显示记录的可检索 ID。
其中 3,034 个别名 ID 没有类型证据，须由 AniList 的查询类型筛选；不能将本地
候选数当成已验证动画数量。46 个来源内已知身份冲突被隔离。仅显示标签冲突不
代表 ID 身份冲突，不会阻止独立来源提供检索别名。

### Search folding and packaged attribution

OpenCC TSCharacters is pinned at revision
`556ed22496d650bd0b13b6c163be9814637970ae`, source SHA-256
`6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a`.
`scripts/localization/opencc/` retains the original dictionary, Apache-2.0 license,
and deterministic generator. The map uses first-target canonical chains strictly
as search equivalence; it does not rewrite display titles or claim phrase-aware
translation. Fixed CJK ranges keep the shipped mappings searchable on old Android
Unicode tables.

Packaged resources include `localization/NOTICE`, the full
`localization/soruly-MIT-LICENSE`, and `localization/opencc/LICENSE`. The bundled
catalog also retains source revision, URL, declared license and byte hash. These
notices travel with APKs; source rebuild inputs remain repository evidence.
