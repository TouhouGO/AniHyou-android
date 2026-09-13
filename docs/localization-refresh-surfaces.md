# Localized GraphQL Surfaces & Invalidation Contract

This document catalogs every surface in AniHyou that consumes localized GraphQL resources (Chinese titles, descriptions, character names, tags) and specifies its invalidation and refresh contract when `localizationConfig` changes (e.g. user toggles localization preferences, downloads an OTA bundle, or resets to built-in bundle).

---

## 1. 界面接入现状清单 (Current Implementation Baseline - 18 Surfaces)

> [!NOTE]
> 当前 18 个 ViewModel 直接监听 `localizationConfig.drop(1).distinctUntilChangedBy { it.configVersion }` 作为展示层响应式刷新的**实施现状**，所有 18 个页面均已配套单元测试覆盖。
> 更高层级的集中化失效机制（如通过 PagingSource 集中广播或仓库层集中失效调度）已登记在后续架构演进池中，保留为独立的后续决策，避免混入当前稳定发布轮次。

| Surface / ViewModel | Module | Cache Invalidation & Reload Contract | Tested In | 状态说明 |
|---|---|---|---|---|
| `SearchViewModel` | `:feature:explore` | Cancel in-flight jobs, reset page to 1, emit `refreshTrigger` to reload active tab (Anime/Manga/Characters/Staff/Studios/Users) | `SearchLocalizationRefreshTest` | 现状实施已验证 |
| `AnimeExploreViewModel` | `:feature:explore` | Cancel jobs, reload all populated carousels/lists | `ExploreLocalizationRefreshTest` | 现状实施已验证 |
| `MangaExploreViewModel` | `:feature:explore` | Cancel jobs, reload all populated carousels/lists | `ExploreLocalizationRefreshTest` | 现状实施已验证 |
| `SeasonAnimeViewModel` | `:feature:explore` | Clear items, reset page = 1, emit `refreshTrigger` to fetch page 1 | `RemainingExploreLocalizationRefreshTest` | 现状实施已验证 |
| `MediaChartViewModel` | `:feature:explore` | Clear items, reset page = 1, emit `refreshTrigger` to fetch page 1 | `RemainingExploreLocalizationRefreshTest` | 现状实施已验证 |
| `RecommendationsViewModel`| `:feature:explore` | Clear items, reset page = 1, `fetchFromNetwork = true` | `RemainingExploreLocalizationRefreshTest` | 现状实施已验证 |
| `CharacterDetailsViewModel`| `:feature:characterdetails`| Cancel `detailsJob`, reset page = 1, emit `refreshTrigger` for media | `CharacterLocalizationRefreshTest` | 现状实施已验证 |
| `StaffDetailsViewModel` | `:feature:staffdetails`| Cancel `detailsJob`, reset page = 1, emit `refreshTrigger` for media/chars | `StaffLocalizationRefreshTest` | 现状实施已验证 |
| `MediaDetailsViewModel` | `:feature:mediadetails`| Cancel slice jobs (`detailsJob`, `charactersAndStaffJob`, etc.), reload slice | `MediaDetailsLocalizationRefreshTest` | 现状实施已验证 |
| `MediaActivityViewModel` | `:feature:mediadetails`| Clear activities, reset page = 1, emit `refreshTrigger` | `MediaSubpageLocalizationRefreshTest` | 现状实施已验证 |
| `MediaCharactersViewModel`| `:feature:mediadetails`| Clear characters, reset page = 1, emit `refreshTrigger` | `MediaSubpageLocalizationRefreshTest` | 现状实施已验证 |
| `UserMediaListViewModel` | `:feature:usermedialist`| Reset `fetchFromNetwork = true` to re-fetch from network | `UserMediaListLocalizationRefreshTest` | 现状实施已验证 |
| `CalendarViewModel` | `:feature:calendar` | Clear `weeklyAnime`, reset page = 1, `fetchFromNetwork = true` | `CalendarLocalizationRefreshTest` | 现状实施已验证 |
| `CurrentViewModel` | `:feature:home` | Trigger `refresh()` to re-fetch anime, manga, and next season from network | `HomeLocalizationRefreshTest` | 现状实施已验证 |
| `ActivityFeedViewModel` | `:feature:home` | Trigger `refreshList()` with `fetchFromNetwork = true` | `HomeLocalizationRefreshTest` | 现状实施已验证 |
| `ProfileViewModel` | `:feature:profile` | Re-fetch user info and call `onRefreshActivities()` | `ProfileLocalizationRefreshTest` | 现状实施已验证 |
| `UserFavoritesViewModel` | `:feature:profile` | Trigger `onRefresh()` to fetch favorites with `fetchFromNetwork = true` | `ProfileLocalizationRefreshTest` | 现状实施已验证 |
| `StudioDetailsViewModel` | `:feature:studiodetails`| Clear media, reset page = 1, re-fetch studio details & media | `StudioLocalizationRefreshTest` | 现状实施已验证 |

---

## 2. Invalidation Coordinator Guarantees

1. **Mutex Serialization**: `LocalizationInvalidationCoordinator` ensures that concurrent config updates or bundle installations execute sequentially under a mutex.
2. **Single Apollo Cache Eviction**: Apollo normalized cache is cleared exactly once per transition, preventing intermediate stale reads.
3. **Monotonic Config Versioning**: `LocalizationConfigState` increments `configVersion` monotonically. ViewModels observe `localizationConfig.drop(1).distinctUntilChangedBy { it.configVersion }` to ignore initial state replay and only react to real configuration/bundle changes.

---

## 3. 后续集中化失效演进评估 (Future Architectural Evaluation)

- **当前优势**：各 ViewModel 按需精细化重置分页和加载状态，无需对非活跃或未实例化页面发送多余重载请求。
- **演进方向**：评估在 Repository 层或统一 PagingSource / Apollo Cache Invalidation 拦截器中提供集中式刷新机制，彻底将 `configVersion` 的监听从 ViewModel 中移出。
- **评估决议**：当前 18 个 ViewModel 现状运转稳健且测试齐备，集中式改造保留在后续大版本迭代中独立立项验证，不混入本次修复与维护。
