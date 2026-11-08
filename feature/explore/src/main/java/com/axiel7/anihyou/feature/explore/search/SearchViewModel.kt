package com.axiel7.anihyou.feature.explore.search

import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.common.viewmodel.PagedUiStateViewModel
import com.axiel7.anihyou.core.domain.model.SavedFilter
import com.axiel7.anihyou.core.domain.localization.ChineseSearchRepository
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LocalizationCatalogRepository
import com.axiel7.anihyou.core.domain.repository.SavedFiltersRepository
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.model.SearchType
import com.axiel7.anihyou.core.model.genre.GenresAndTagsForSearch
import com.axiel7.anihyou.core.model.media.CountryOfOrigin
import com.axiel7.anihyou.core.model.media.MediaFormatLocalizable
import com.axiel7.anihyou.core.model.media.MediaSourceLocalizable
import com.axiel7.anihyou.core.model.media.MediaStatusLocalizable
import com.axiel7.anihyou.core.network.SearchMediaQuery
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaSeason
import com.axiel7.anihyou.core.network.type.MediaSort
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.ui.common.navigation.Route
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    @InjectedParam arguments: Route.Search,
    @InjectedParam isLoggedIn: Boolean,
    private val searchRepository: SearchRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
    defaultPreferencesRepository: DefaultPreferencesRepository,
    private val localizationCatalogRepository: LocalizationCatalogRepository,
    private val planningDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val chineseSearchRepository: ChineseSearchRepository? = null,
) : PagedUiStateViewModel<SearchUiState>(), SearchEvent {

    private val mediaSessions = MediaSearchSessions()
    private var candidatePager: CandidatePageSession<SearchMediaQuery.Medium>? = null
    private var pagerGeneration: Long? = null
    private var publishedMediaGeneration: Long? = null
    private var supplementSession: SupplementalSearchSession<SearchMediaQuery.Medium>? = null
    private var supplementJob: Job? = null
    private var supplementGeneration: Long? = null
    private var candidateSnapshot = LocalizationSnapshot.EMPTY
    private var activeCatalogGeneration: Long? = null
    private var activeCatalogVersion: String? = null

    override fun onLoadMore() {
        mutableUiState.update {
            if (!it.hasNextPage || it.isLoading || it.localPageFailed) it
            else it.copy(page = it.page + 1, isLoading = true)
        }
    }

    override fun retryMediaPage() {
        mutableUiState.update {
            if (!it.searchType.isSearchMedia || it.isLoading || !it.localPageFailed) it
            else it.copy(mediaRetry = it.mediaRetry + 1, isLoading = true, localPageFailed = false, error = null)
        }
    }

    override fun loadMoreSupplemental() {
        val current = uiState.value
        if (current.supplementLoading || !current.supplementHasMore) return
        val session = supplementSession ?: return
        if (supplementGeneration != current.searchGeneration) return
        launchSupplement(session, current.searchGeneration, MediaSearchKey.from(current))
    }

    private fun clearSupplement() {
        supplementJob?.cancel()
        supplementJob = null
        supplementSession = null
        supplementGeneration = null
    }

    override val initialState =
        SearchUiState(
            searchType = if (arguments.mediaType == MediaType.MANGA) SearchType.MANGA else SearchType.ANIME,
            mediaSort = arguments.mediaSort ?: MediaSort.SEARCH_MATCH,
            season = arguments.season,
            startYear = arguments.year,
            endYear = arguments.year,
            onMyList = arguments.onList,
            isLoggedIn = isLoggedIn,
            isAdult = if (isLoggedIn) null else false,
            hasNextPage = arguments.mediaSort != null
        )

    override fun setQuery(value: String) {
        mediaSessions.reset()
        clearSupplement()
        candidateSnapshot = LocalizationSnapshot.EMPTY
        candidatePager = null
        pagerGeneration = null
        activeCatalogGeneration = null
        activeCatalogVersion = null
        mutableUiState.update {
            val shouldLoad = value.isNotBlank() || (it.searchType.isSearchMedia && it.hasFiltersApplied)
            if (!shouldLoad) it.media.clear()
            it.copy(
                query = value,
                page = if (shouldLoad) 1 else 0,
                hasNextPage = shouldLoad,
                isLoading = shouldLoad,
                searchGeneration = it.searchGeneration + 1,
                localPageFailed = false,
                supplementalMedia = persistentListOf(),
                supplementStarted = false,
                supplementLoading = false,
                supplementHasMore = false,
                supplementFailed = false,
                supplementDepthLimited = false,
                supplementNamelessCount = 0,
                supplementRetryAfterMillis = null,
                supplementUsesPopularityFallback = false,
                isLocalTitleSearch = if (value.isBlank()) false else it.isLocalTitleSearch,
                localCandidateCount = if (value.isBlank()) 0 else it.localCandidateCount,
                localCandidatesTruncated = if (value.isBlank()) false else it.localCandidatesTruncated,
                usesLocalPopularitySort = if (value.isBlank()) false else it.usesLocalPopularitySort,
            )
        }
    }

    override fun setSearchType(value: SearchType) {
        mediaSessions.reset()
        clearSupplement()
        candidateSnapshot = LocalizationSnapshot.EMPTY
        candidatePager = null
        pagerGeneration = null
        activeCatalogGeneration = null
        activeCatalogVersion = null
        mutableUiState.update {
            val shouldLoad = value.isSearchMedia || it.query.isNotBlank()
            it.copy(
                searchType = value,
                selectedMediaFormats = persistentListOf(),
                page = 1,
                hasNextPage = shouldLoad,
                isLoading = shouldLoad,
                searchGeneration = it.searchGeneration + 1,
                isLocalTitleSearch = false,
                localPageFailed = false,
                supplementalMedia = persistentListOf(),
                supplementStarted = false,
                supplementLoading = false,
                supplementHasMore = false,
                supplementFailed = false,
                supplementDepthLimited = false,
                supplementNamelessCount = 0,
                supplementRetryAfterMillis = null,
                supplementUsesPopularityFallback = false,
            )
        }
    }

    /** A filter selection that leaves the request unchanged must also leave loading unchanged. */
    private fun updateSearchFilters(transform: (SearchUiState) -> SearchUiState) {
        mutableUiState.update { current ->
            val next = transform(current)
            if (current.searchType == next.searchType && MediaSearchKey.from(current) == MediaSearchKey.from(next)) {
                current
            } else {
                clearSupplement()
                val shouldLoad = next.searchType.isSearchMedia || next.query.isNotBlank()
                next.copy(
                    page = 1,
                    hasNextPage = shouldLoad,
                    isLoading = shouldLoad,
                    error = null,
                    localPageFailed = false,
                    supplementalMedia = persistentListOf(),
                    supplementStarted = false,
                    supplementLoading = false,
                    supplementHasMore = false,
                    supplementFailed = false,
                    supplementDepthLimited = false,
                    supplementNamelessCount = 0,
                    supplementRetryAfterMillis = null,
                    supplementUsesPopularityFallback = false,
                    searchGeneration = current.searchGeneration + 1,
                )
            }
        }
    }

    override fun setMediaSort(value: MediaSort) = updateSearchFilters {
        it.copy(mediaSort = value, page = 1, hasNextPage = true, isLoading = true)
    }

    override fun setMediaFormats(values: List<MediaFormatLocalizable>) = updateSearchFilters {
        it.copy(
            selectedMediaFormats = values.toImmutableList(),
            page = 1,
            hasNextPage = true,
            isLoading = true,
            mediaFormatsChanged = true,
        )
    }

    override fun setMediaStatuses(values: List<MediaStatusLocalizable>) = updateSearchFilters {
        it.copy(
            selectedMediaStatuses = values.toImmutableList(),
            page = 1,
            hasNextPage = true,
            isLoading = true,
            mediaStatusesChanged = true,
        )
    }

    override fun setStartYear(value: Int?) = updateSearchFilters {
        it.copy(startYear = value, page = 1, hasNextPage = true, isLoading = true)
    }

    override fun setEndYear(value: Int?) = updateSearchFilters {
        it.copy(endYear = value, page = 1, hasNextPage = true, isLoading = true)
    }

    override fun setSeason(value: MediaSeason?) = updateSearchFilters {
        it.copy(season = value, page = 1, hasNextPage = true, isLoading = true)
    }

    override fun setEpisodesChapters(value: IntRange?) = updateSearchFilters {
        it.copy(episodesChaptersRange = value)
    }

    override fun setDurationVolumes(value: IntRange?) = updateSearchFilters {
        it.copy(durationVolumesRange = value)
    }

    override fun setOnMyList(value: Boolean?) = updateSearchFilters {
        it.copy(onMyList = value, page = 1, hasNextPage = true, isLoading = true)
    }

    override fun setIsDoujin(value: Boolean?) = updateSearchFilters {
        it.copy(isDoujin = value, page = 1, hasNextPage = true, isLoading = true)
    }

    override fun setIsAdult(value: Boolean?) = updateSearchFilters {
        it.copy(isAdult = value, page = 1, hasNextPage = true, isLoading = true)
    }

    override fun setCountry(value: CountryOfOrigin?) = updateSearchFilters {
        it.copy(country = value, page = 1, hasNextPage = true, isLoading = true)
    }

    override fun setSources(values: List<MediaSourceLocalizable>) = updateSearchFilters {
        it.copy(
            selectedSources = values.toImmutableList(),
            page = 1,
            hasNextPage = true,
            isLoading = true,
            sourcesChanged = true
        )
    }

    override fun onGenreTagStateChanged(genresAndTagsForSearch: GenresAndTagsForSearch) =
        updateSearchFilters {
            if (it.genresAndTagsForSearch == genresAndTagsForSearch) return@updateSearchFilters it
            it.copy(
                genresAndTagsForSearch = genresAndTagsForSearch,
                genresOrTagsChanged = true,
                page = 1,
                hasNextPage = true,
                isLoading = true
            )
        }

    override fun clearFilters() = updateSearchFilters {
        it.copy(
            genresAndTagsForSearch = GenresAndTagsForSearch(),
            genresOrTagsChanged = true,
            selectedMediaFormats = persistentListOf(),
            selectedMediaStatuses = persistentListOf(),
            startYear = null,
            endYear = null,
            onMyList = null,
            isDoujin = null,
            isAdult = null,
            country = null,
            season = null,
            episodesChaptersRange = null,
            durationVolumesRange = null,
            selectedSources = persistentListOf(),
            clearedFilters = true,
            page = 1,
            hasNextPage = true,
            isLoading = true
        )
    }

    override fun selectMediaItem(value: SearchMediaQuery.Medium?) = mutableUiState.update {
        it.copy(selectedMediaItem = value)
    }

    override fun onUpdateListEntry(newListEntry: BasicMediaListEntry?) {
        mutableUiState.value.run {
            selectedMediaItem?.let { selectedItem ->
                val index = media.indexOf(selectedItem)
                if (index != -1) {
                    media[index] = selectedItem.copy(
                        mediaListEntry = newListEntry?.let {
                            SearchMediaQuery.MediaListEntry(
                                __typename = "SearchMediaQuery.MediaListEntry",
                                id = newListEntry.id,
                                mediaId = newListEntry.mediaId,
                                basicMediaListEntry = newListEntry
                            )
                        }
                    )
                }
                val supplementalIndex = supplementalMedia.indexOfFirst { it.id == selectedItem.id }
                if (supplementalIndex != -1) {
                    val updated = supplementalMedia.toMutableList()
                    updated[supplementalIndex] = selectedItem.copy(mediaListEntry = newListEntry?.let {
                        SearchMediaQuery.MediaListEntry("SearchMediaQuery.MediaListEntry", it.id, it.mediaId, it)
                    })
                    mutableUiState.update { it.copy(supplementalMedia = updated.toImmutableList()) }
                }
            }
        }
    }

    override fun onShowSavedFilters() {
        if (uiState.value.savedFilters.isEmpty()) {
            viewModelScope.launch {
                savedFiltersRepository.getAllFilters().collectLatest { filters ->
                    mutableUiState.update {
                        it.copy(savedFilters = filters.toImmutableList())
                    }
                }
            }
        }
    }

    override fun saveCurrentFilter(name: String) {
        viewModelScope.launch {
            with(uiState.value) {
                val filter = SavedFilter(
                    id = 0,
                    name = name,
                    searchType = searchType,
                    mediaSort = mediaSort,
                    genreIn = genresAndTagsForSearch.genreIn.toList().takeIf { it.isNotEmpty() },
                    genreNot = genresAndTagsForSearch.genreNot.toList().takeIf { it.isNotEmpty() },
                    tagIn = genresAndTagsForSearch.tagIn.toList().takeIf { it.isNotEmpty() },
                    tagNot = genresAndTagsForSearch.tagNot.toList().takeIf { it.isNotEmpty() },
                    minimumTagPercentage = genresAndTagsForSearch.minimumTagPercentage,
                    mediaFormats = selectedMediaFormats.map { it.value }.takeIf { it.isNotEmpty() },
                    mediaStatuses = selectedMediaStatuses.map { it.value }.takeIf { it.isNotEmpty() },
                    startYear = startYear,
                    endYear = endYear,
                    season = season,
                    episodesChaptersRange = episodesChaptersRange,
                    durationVolumesRange = durationVolumesRange,
                    onMyList = onMyList,
                    isDoujin = isDoujin,
                    isAdult = isAdult,
                    country = country,
                    sources = selectedSources.map { it.value }.takeIf { it.isNotEmpty() },
                )
                savedFiltersRepository.insertFilter(filter)
            }
        }
    }

    override fun deleteFilter(id: Int) {
        viewModelScope.launch {
            savedFiltersRepository.deleteFilter(id)
        }
    }

    override fun applySavedFilter(filter: SavedFilter) {
        updateSearchFilters {
            it.copy(
                searchType = filter.searchType,
                mediaSort = filter.mediaSort ?: it.mediaSort,
                genresAndTagsForSearch = GenresAndTagsForSearch(
                    genreIn = filter.genreIn?.toSet().orEmpty(),
                    genreNot = filter.genreNot?.toSet().orEmpty(),
                    tagIn = filter.tagIn?.toSet().orEmpty(),
                    tagNot = filter.tagNot?.toSet().orEmpty(),
                    minimumTagPercentage = filter.minimumTagPercentage,
                ),
                genresOrTagsChanged = true,
                selectedMediaFormats = filter.mediaFormats
                    ?.mapNotNull { value -> MediaFormatLocalizable.findValue(value) }
                    ?.toImmutableList() ?: persistentListOf(),
                mediaFormatsChanged = true,
                selectedMediaStatuses = filter.mediaStatuses
                    ?.mapNotNull { value -> MediaStatusLocalizable.findValue(value) }
                    ?.toImmutableList() ?: persistentListOf(),
                mediaStatusesChanged = true,
                startYear = filter.startYear,
                endYear = filter.endYear,
                season = filter.season,
                episodesChaptersRange = filter.episodesChaptersRange,
                durationVolumesRange = filter.durationVolumesRange,
                onMyList = filter.onMyList,
                isDoujin = filter.isDoujin,
                isAdult = filter.isAdult,
                country = filter.country,
                selectedSources = filter.sources
                    ?.mapNotNull { value -> MediaSourceLocalizable.findValue(value) }
                    ?.toImmutableList() ?: persistentListOf(),
                sourcesChanged = true,
                clearedFilters = true,
                page = 1,
                hasNextPage = true,
                isLoading = true,
            )
        }
    }

    private fun searchMediaPage(
        state: SearchUiState,
        plan: MediaSearchPlan,
        page: Int,
        ids: List<Int>? = plan.mediaIds,
    ): kotlinx.coroutines.flow.Flow<PagedResult<SearchMediaQuery.Medium>> {
        val request = requireNotNull(MediaSearchRequest.from(state))
        return searchRepository.searchMedia(
            mediaType = request.mediaType,
            query = plan.query,
            sort = if (ids == null || plan.sort in listOf(MediaSort.ID, MediaSort.ID_DESC)) listOf(plan.sort)
                else listOf(plan.sort, MediaSort.ID),
            genreIn = request.genreIn,
            genreNotIn = request.genreNotIn,
            tagIn = request.tagIn,
            tagNotIn = request.tagNotIn,
            minimumTagPercentage = request.minimumTagPercentage,
            formatIn = request.formatIn,
            statusIn = request.statusIn,
            episodesLesser = request.episodesLesser,
            episodesGreater = request.episodesGreater,
            durationLesser = request.durationLesser,
            durationGreater = request.durationGreater,
            chaptersLesser = request.chaptersLesser,
            chaptersGreater = request.chaptersGreater,
            volumesLesser = request.volumesLesser,
            volumesGreater = request.volumesGreater,
            startYear = request.startYear,
            endYear = request.endYear,
            season = request.season,
            onList = request.onList,
            isLicensed = request.isLicensed,
            isAdult = request.isAdult,
            country = request.country,
            sourceIn = request.sourceIn,
            page = page,
            mediaIds = ids,
        )
    }

    private fun startSupplement(state: SearchUiState, selection: CandidateSelection) {
        val repository = chineseSearchRepository ?: return
        if (!MediaSearchSessions.requiresCatalog(state) || supplementGeneration == state.searchGeneration) return
        clearSupplement()
        mutableUiState.update { it.copy(
            supplementalMedia = persistentListOf(), supplementStarted = true, supplementLoading = false,
            supplementHasMore = false, supplementFailed = false, supplementRetryAfterMillis = null,
            supplementDepthLimited = false, supplementNamelessCount = 0,
            supplementUsesPopularityFallback = false,
        ) }
        val kind = if (state.isAnime) MediaKind.ANIME else MediaKind.MANGA
        val session = SupplementalSearchSession(
            query = state.query,
            kind = kind,
            snapshot = candidateSnapshot,
            localIds = selection.ids.orEmpty(),
            idOf = { media: SearchMediaQuery.Medium -> media.id },
            discover = { repository.search(state.query, kind, it) },
            fetch = { text, ids, page ->
                val sort = if (ids != null && state.mediaSort == MediaSort.SEARCH_MATCH)
                    MediaSort.POPULARITY_DESC else state.mediaSortForSearch
                searchMediaPage(state, MediaSearchPlan(text, ids, sort, null, 0, false, false), page, ids)
                    .first { it !is PagedResult.Loading }
            },
            mappedIdsUsePopularityFallback = state.mediaSort == MediaSort.SEARCH_MATCH,
        )
        supplementSession = session
        supplementGeneration = state.searchGeneration
        launchSupplement(session, state.searchGeneration, MediaSearchKey.from(state))
    }

    private fun launchSupplement(
        session: SupplementalSearchSession<SearchMediaQuery.Medium>,
        generation: Long,
        key: MediaSearchKey,
    ) {
        mutableUiState.update { it.copy(supplementLoading = true, supplementFailed = false, supplementRetryAfterMillis = null) }
        supplementJob = viewModelScope.launch {
            val result = try {
                session.loadMoreWithMetadata { rows, mappedPopularityFallback ->
                    mutableUiState.update { current ->
                        if (current.searchGeneration != generation || MediaSearchKey.from(current) != key) current
                        else {
                            val seen = if (publishedMediaGeneration == generation)
                                current.media.mapTo(HashSet()) { it.id } else HashSet()
                            seen.addAll(current.supplementalMedia.map { it.id })
                            current.copy(
                                supplementalMedia = (current.supplementalMedia + rows.filter { seen.add(it.id) }).toImmutableList(),
                                supplementUsesPopularityFallback =
                                    current.supplementUsesPopularityFallback || mappedPopularityFallback,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { SupplementProgress(hasMore = true, failed = true) }
            mutableUiState.update {
                if (it.searchGeneration != generation || MediaSearchKey.from(it) != key) it
                else it.copy(
                    supplementLoading = false,
                    supplementHasMore = result.hasMore,
                    supplementFailed = result.failed,
                    supplementRetryAfterMillis = result.retryAfterMillis,
                    supplementDepthLimited = result.depthLimited,
                    supplementNamelessCount = result.unmappedWithoutName,
                    supplementUsesPopularityFallback =
                        it.supplementUsesPopularityFallback || result.mappedIdsUsePopularityFallback,
                )
            }
        }
    }

    init {
        defaultPreferencesRepository.titleLanguage
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { value ->
                mutableUiState.update { current ->
                    val updated = current.copy(titleLanguage = value)
                    if (current.titleLanguage != value && current.searchType.isSearchMedia &&
                        (current.hasNextPage || current.page > 0) &&
                        updated.mediaSortForSearch != current.mediaSortForSearch) {
                        clearSupplement()
                        current.media.clear()
                        updated.restartForGeneration(current.searchGeneration + 1)
                    } else updated
                }
            }
            .launchIn(viewModelScope)

        if (arguments.genre != null || arguments.tag != null) {
            onGenreTagStateChanged(
                genresAndTagsForSearch = GenresAndTagsForSearch(
                    genreIn = setOfNotNull(arguments.genre),
                    tagIn = setOfNotNull(arguments.tag),
                )
            )
        }

        // media search
        mutableUiState
            .map { state ->
                MediaRequest(MediaSearchKey.from(state), state.searchGeneration, state.page,
                    state.searchType.isSearchMedia && state.hasNextPage, state.mediaRetry) to state
            }
            .distinctUntilChanged { old, new -> old.first == new.first }
            .flatMapLatest { (request, uiState) ->
                if (!request.enabled) return@flatMapLatest emptyFlow()
                val key = MediaSearchKey.from(uiState)
                flow {
                    var snapshotForSelection: LocalizationSnapshot? = null
                    val selection = mediaSessions.cached(uiState, candidateSnapshot) ?: run {
                        if (MediaSearchSessions.requiresCatalog(uiState)) {
                            localizationCatalogRepository.load()
                            val snapshot = localizationCatalogRepository.snapshot.value
                            snapshotForSelection = snapshot
                            withContext(planningDispatcher) { MediaSearchSessions.select(uiState, snapshot) }
                        } else {
                            MediaSearchSessions.select(uiState, com.axiel7.anihyou.core.localization.LocalizationSnapshot.EMPTY)
                        }
                    }
                    val current = mutableUiState.value
                    if (current.searchGeneration != uiState.searchGeneration ||
                        MediaSearchKey.from(current) != key || current.page != uiState.page || current.mediaRetry != request.retry) return@flow
                    snapshotForSelection?.let { candidateSnapshot = it }
                    activeCatalogGeneration = if (MediaSearchSessions.requiresCatalog(uiState)) {
                        uiState.searchGeneration
                    } else null
                    activeCatalogVersion = if (MediaSearchSessions.requiresCatalog(uiState)) {
                        selection.catalogVersion
                    } else null
                    val plan = mediaSessions.plan(uiState, selection)
                    startSupplement(uiState, selection)
                    val pager = if (plan.mediaIds != null) {
                        if (pagerGeneration != uiState.searchGeneration || candidatePager == null) {
                            candidatePager = CandidatePageSession(plan.mediaIds, { it.id }) { ids, page, _ ->
                                searchMediaPage(uiState, plan, page, ids).first { it !is PagedResult.Loading }
                            }
                            pagerGeneration = uiState.searchGeneration
                        }
                        candidatePager
                    } else null
                    mutableUiState.update { current ->
                        if (current.searchGeneration != uiState.searchGeneration || MediaSearchKey.from(current) != key) current
                        else current.copy(
                            isLocalTitleSearch = plan.isLocalSearch,
                            localCandidateCount = plan.totalCandidates,
                            localCandidatesTruncated = plan.isTruncated,
                            usesLocalPopularitySort = plan.usesPopularityFallback,
                            localCandidatesPartitioned = pager?.isPartitioned == true,
                        )
                    }
                    if (pager != null) {
                        emit(MediaSearchResponse(key, uiState.searchGeneration, uiState.page, request.retry, pager.next(), pager))
                    } else {
                        searchMediaPage(uiState, plan, uiState.page).collect { result ->
                            emit(MediaSearchResponse(key, uiState.searchGeneration, uiState.page, request.retry, result))
                        }
                    }
                }
            }
            .onEach { response ->
                mutableUiState.update {
                    if (it.searchGeneration != response.generation || MediaSearchKey.from(it) != response.key || it.page != response.page || it.mediaRetry != response.retry) {
                        return@update it
                    }
                    val result = response.result
                    if (result is PagedResult.Success) {
                        if (publishedMediaGeneration != response.generation) {
                            it.media.clear()
                            publishedMediaGeneration = response.generation
                        }
                        val existingIds = it.media.mapTo(HashSet()) { media -> media.id }
                        it.media.addAll(result.list.filter { media -> existingIds.add(media.id) })
                        response.pager?.acknowledge()
                        it.copy(
                            hasNextPage = result.hasNextPage,
                            isLoading = false,
                            error = null,
                            genresOrTagsChanged = false,
                            mediaFormatsChanged = false,
                            mediaStatusesChanged = false,
                            sourcesChanged = false,
                            clearedFilters = false,
                            localPageFailed = false,
                            supplementalMedia = it.supplementalMedia.filter { media -> media.id !in existingIds }.toImmutableList(),
                        )
                    } else {
                        result.toUiState(loadingWhen = it.page == 1).copy(
                            localPageFailed = result is PagedResult.Error,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)

        // Catalog activation ends the old query session and rebuilds its local candidates.
        localizationCatalogRepository.snapshot
            .map { it.version }
            .distinctUntilChanged()
            .onEach { version ->
                val current = uiState.value
                val generation = activeCatalogGeneration ?: return@onEach
                if (generation != current.searchGeneration || !MediaSearchSessions.requiresCatalog(current) ||
                    version.isBlank() || version == activeCatalogVersion) return@onEach

                val activatedSnapshot = localizationCatalogRepository.snapshot.value
                mediaSessions.reset()
                clearSupplement()
                candidatePager = null
                pagerGeneration = null
                candidateSnapshot = activatedSnapshot
                val nextGeneration = generation + 1
                activeCatalogGeneration = nextGeneration
                activeCatalogVersion = version
                mutableUiState.update { state ->
                    if (state.searchGeneration != generation || !MediaSearchSessions.requiresCatalog(state)) state
                    else {
                        state.media.clear()
                        state.restartForGeneration(nextGeneration)
                    }
                }
            }
            .launchIn(viewModelScope)

        // character search
        mutableUiState
            .distinctUntilChanged { old, new ->
                old.searchType == new.searchType
                        && old.searchGeneration == new.searchGeneration
                        && old.page == new.page
                        && old.query == new.query
            }
            .flatMapLatest { uiState ->
                if (uiState.searchType != SearchType.CHARACTER || !uiState.hasNextPage || uiState.query.isBlank()) return@flatMapLatest emptyFlow()
                searchRepository.searchCharacter(
                    query = uiState.query,
                    page = uiState.page
                )
            }
            .onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        if (it.page == 1) it.characters.clear()
                        it.characters.addAll(result.list)
                        it.copy(
                            hasNextPage = result.hasNextPage,
                            isLoading = false,
                        )
                    } else {
                        result.toUiState(loadingWhen = it.page == 1)
                    }
                }
            }
            .launchIn(viewModelScope)

        // staff search
        mutableUiState
            .distinctUntilChanged { old, new ->
                old.searchType == new.searchType
                        && old.searchGeneration == new.searchGeneration
                        && old.page == new.page
                        && old.query == new.query
            }
            .flatMapLatest { uiState ->
                if (uiState.searchType != SearchType.STAFF || !uiState.hasNextPage || uiState.query.isBlank()) return@flatMapLatest emptyFlow()
                searchRepository.searchStaff(
                    query = uiState.query,
                    page = uiState.page
                )
            }
            .onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        if (it.page == 1) it.staff.clear()
                        it.staff.addAll(result.list)
                        it.copy(
                            hasNextPage = result.hasNextPage,
                            isLoading = false,
                        )
                    } else {
                        result.toUiState(loadingWhen = it.page == 1)
                    }
                }
            }
            .launchIn(viewModelScope)

        // studio search
        mutableUiState
            .distinctUntilChanged { old, new ->
                old.searchType == new.searchType
                        && old.searchGeneration == new.searchGeneration
                        && old.page == new.page
                        && old.query == new.query
            }
            .flatMapLatest { uiState ->
                if (uiState.searchType != SearchType.STUDIO || !uiState.hasNextPage || uiState.query.isBlank()) return@flatMapLatest emptyFlow()
                searchRepository.searchStudio(
                    query = uiState.query,
                    page = uiState.page
                )
            }
            .onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        if (it.page == 1) it.studios.clear()
                        it.studios.addAll(result.list)
                        it.copy(
                            hasNextPage = result.hasNextPage,
                            isLoading = false,
                        )
                    } else {
                        result.toUiState(loadingWhen = it.page == 1)
                    }
                }
            }
            .launchIn(viewModelScope)

        // user search
        mutableUiState
            .distinctUntilChanged { old, new ->
                old.searchType == new.searchType
                        && old.searchGeneration == new.searchGeneration
                        && old.page == new.page
                        && old.query == new.query
            }
            .flatMapLatest { uiState ->
                if (uiState.searchType != SearchType.USER || !uiState.hasNextPage || uiState.query.isBlank()) return@flatMapLatest emptyFlow()
                searchRepository.searchUser(
                    query = uiState.query,
                    page = uiState.page
                )
            }
            .onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        if (it.page == 1) it.users.clear()
                        it.users.addAll(result.list)
                        it.copy(
                            hasNextPage = result.hasNextPage,
                            isLoading = false,
                        )
                    } else {
                        result.toUiState(loadingWhen = it.page == 1)
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private data class MediaRequest(
        val key: MediaSearchKey,
        val generation: Long,
        val page: Int,
        val enabled: Boolean,
        val retry: Long,
    )

    private data class MediaSearchResponse(
        val key: MediaSearchKey,
        val generation: Long,
        val page: Int,
        val retry: Long,
        val result: PagedResult<SearchMediaQuery.Medium>,
        val pager: CandidatePageSession<SearchMediaQuery.Medium>? = null,
    )
}
