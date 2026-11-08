package com.axiel7.anihyou.feature.usermedialist

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.base.extensions.firstBlocking
import com.axiel7.anihyou.core.common.utils.NumberUtils.isNullOrZero
import com.axiel7.anihyou.core.common.utils.SearchUtils.fuzzyScore
import com.axiel7.anihyou.core.common.utils.SearchUtils.whiteSpaceRegex
import com.axiel7.anihyou.core.common.viewmodel.UiStateViewModel
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LocalizationCatalogRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.model.genre.GenresAndTagsForSearch
import com.axiel7.anihyou.core.model.media.CountryOfOrigin
import com.axiel7.anihyou.core.model.media.ListType
import com.axiel7.anihyou.core.model.media.MediaFormatLocalizable
import com.axiel7.anihyou.core.model.media.asMediaListStatus
import com.axiel7.anihyou.core.model.media.comparator
import com.axiel7.anihyou.core.model.media.duration
import com.axiel7.anihyou.core.model.media.isDescending
import com.axiel7.anihyou.core.model.media.isTitle
import com.axiel7.anihyou.core.model.media.plainName
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaListSort
import com.axiel7.anihyou.core.network.type.MediaListStatus
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.ScoreFormat
import com.axiel7.anihyou.core.network.type.UserTitleLanguage
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.common.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.InjectedParam

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class UserMediaListViewModel(
    @InjectedParam arguments: Route.UserMediaList,
    private val mediaListRepository: MediaListRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val listPreferencesRepository: ListPreferencesRepository,
    private val catalogRepository: LocalizationCatalogRepository,
) : UiStateViewModel<UserMediaListUiState>(), UserMediaListEvent {

    private val scoreFormat = arguments.scoreFormat?.let { ScoreFormat.safeValueOf(it) }
    private val mediaType = MediaType.safeValueOf(arguments.mediaType)
    private val lastSelectedList =
        (if (mediaType == MediaType.ANIME) listPreferencesRepository.animeListSelected
        else listPreferencesRepository.mangaListSelected).firstBlocking()

    override val initialState =
        UserMediaListUiState(
            mediaType = mediaType,
            scoreFormat = scoreFormat ?: ScoreFormat.POINT_10,
            selectedListName = lastSelectedList,
            status = lastSelectedList?.asMediaListStatus(),
            userId = arguments.userId.takeIf { it != 0 },
            isMyList = arguments.userId == 0
        )

    private val myUserId = defaultPreferencesRepository.userId
        .filterNotNull()

    private val titleLanguage = defaultPreferencesRepository.titleLanguage

    override fun setScoreFormat(value: ScoreFormat) {
        mutableUiState.update { it.copy(scoreFormat = value) }
    }

    override fun onChangeList(listName: String?) {
        viewModelScope.launch {
            mutableUiState.update {
                it.copy(
                    selectedListName = listName,
                    status = listName?.asMediaListStatus()
                )
            }

            if (mediaType == MediaType.ANIME) {
                listPreferencesRepository.setAnimeListSelected(listName)
            } else {
                listPreferencesRepository.setMangaListSelected(listName)
            }
        }
    }

    override fun setSort(value: MediaListSort) {
        viewModelScope.launch {
            var sort = value
            // when sorting by title, change according to the user preferred title lang
            if (sort == MediaListSort.MEDIA_TITLE_ROMAJI
                || sort == MediaListSort.MEDIA_TITLE_ROMAJI_DESC
            ) {
                val isDesc = sort == MediaListSort.MEDIA_TITLE_ROMAJI_DESC
                sort = when (titleLanguage.first()) {
                    UserTitleLanguage.ENGLISH,
                    UserTitleLanguage.ENGLISH_STYLISED ->
                        if (isDesc) MediaListSort.MEDIA_TITLE_ENGLISH_DESC
                        else MediaListSort.MEDIA_TITLE_ENGLISH

                    UserTitleLanguage.NATIVE,
                    UserTitleLanguage.NATIVE_STYLISED ->
                        if (isDesc) MediaListSort.MEDIA_TITLE_NATIVE_DESC
                        else MediaListSort.MEDIA_TITLE_NATIVE

                    else -> value
                }
            }

            mutableUiState.update {
                it.copy(
                    sort = sort,
                    isSearchSortModified = true
                )
            }

            if (mediaType == MediaType.ANIME) {
                listPreferencesRepository.setAnimeListSort(sort)
            } else if (mediaType == MediaType.MANGA) {
                listPreferencesRepository.setMangaListSort(sort)
            }
        }
    }

    override fun toggleSortMenu(open: Boolean) {
        mutableUiState.update { it.copy(sortMenuExpanded = open) }
    }

    override fun toggleNotesDialog(open: Boolean) {
        mutableUiState.update { it.copy(openNotesDialog = open) }
    }

    override fun refreshList() {
        mutableUiState.update {
            it.copy(
                fetchFromNetwork = true,
                isLoading = true
            )
        }
    }

    override fun onClickPlusOne(increment: Int, entry: CommonMediaListEntry) {
        viewModelScope.launch {
            mutableUiState.update { it.copy(selectedItem = entry, isLoadingPlusOne = true) }
            mediaListRepository.incrementProgress(
                entry = entry.basicMediaListEntry,
                increment = increment,
                total = entry.duration()
            ).collectLatest { result ->
                mutableUiState.update {
                    if (result is DataResult.Success && result.data != null) {
                        onUpdateListEntry(result.data!!.basicMediaListEntry)
                    }
                    result.toUiState().copy(isLoadingPlusOne = result is DataResult.Loading)
                }
            }
        }
    }

    override fun blockPlusOne() {
        mutableUiState.update { it.copy(isLoadingPlusOne = true) }
    }

    override fun selectItem(value: CommonMediaListEntry?) {
        mutableUiState.update { it.copy(selectedItem = value) }
    }

    override fun onUpdateListEntry(newListEntry: BasicMediaListEntry?) {
        val state = mutableUiState.value
        val selected = state.selectedItem ?: return
        if (selected.basicMediaListEntry == newListEntry) return
        val lists = state.lists.toMutableMap()
        val replacement = newListEntry?.let { selected.copy(basicMediaListEntry = it) }
        val changedStatus = newListEntry != null && newListEntry.status != selected.basicMediaListEntry.status
        val mediaFormat = selected.media?.format
        val selectedMediaType = selected.media?.basicMediaDetails?.type ?: state.mediaType
        val regularListNames = state.standardListNames.takeIf { it.isNotEmpty() }
            ?.map { it.uppercase() }
            ?.toSet()
            ?: mediaFormat?.let { standardListNames(selectedMediaType, it) }.orEmpty()
        val targetList = if (changedStatus) {
            newListEntry.status?.let { status ->
                mediaFormat?.let { findListForEntry(status, selectedMediaType, it) }
            }
        } else null
        val shouldOpenScoreDialog = changedStatus && newListEntry.status == MediaListStatus.COMPLETED &&
            newListEntry.score.isNullOrZero()
        lists.keys.toList().forEach { name ->
            lists[name] = lists[name].orEmpty().mapNotNull { old ->
                when {
                    old.mediaId != selected.mediaId -> old
                    replacement == null -> null
                    changedStatus && name.uppercase() in regularListNames -> null
                    else -> old.copy(basicMediaListEntry = newListEntry)
                }
            }
        }
        if (changedStatus && replacement != null && targetList != null) {
            if (lists[targetList].orEmpty().none { it.mediaId == selected.mediaId }) {
                lists[targetList] = lists[targetList].orEmpty() + replacement
            }
        }
        mutableUiState.update {
            it.copy(
                lists = lists,
                selectedItem = if (changedStatus) replacement else it.selectedItem,
                openSetScoreDialog = if (changedStatus) shouldOpenScoreDialog else it.openSetScoreDialog,
            )
        }
    }

    override fun setScore(score: Double?) {
        viewModelScope.launch {
            mutableUiState.value.run {
                if (isLoading) return@launch
                selectedItem?.let { item ->
                    mediaListRepository.updateEntry(
                        oldEntry = item.basicMediaListEntry,
                        mediaId = item.mediaId,
                        score = score,
                    ).collectLatest { result ->
                        mutableUiState.update {
                            result.toUiState()
                        }
                    }
                }
            }
            toggleScoreDialog(false)
        }
    }

    override fun toggleScoreDialog(open: Boolean) {
        mutableUiState.update { it.copy(openSetScoreDialog = open) }
    }

    override fun getRandomEntry() {
        uiState.value.run {
            mutableUiState.update { it.copy(isLoadingRandom = true) }
            val filteredList = entries.filter { entry ->
                formatMatch(entry)
                        && statusMatch(entry)
                        && countryMatch(entry)
                        && yearMatch(entry)
                        && genreMatch(entry)
                        && tagMatch(entry)
                        && episodesChaptersMatch(entry)
                        && volumesDurationMatch(entry)
            }
            if (filteredList.isNotEmpty()) {
                mutableUiState.update {
                    it.copy(
                        randomEntryId = filteredList.random().mediaId,
                        isLoadingRandom = false,
                    )
                }
            } else {
                mutableUiState.update {
                    it.copy(errorId = R.string.no_media, isLoadingRandom = false)
                }
            }
        }
    }

    override fun onRandomEntryOpened() {
        mutableUiState.update { it.copy(randomEntryId = null) }
    }

    override fun setQuery(query: String) {
        mutableUiState.update {
            it.copy(
                query = query,
                isSearchSortModified = if (query.isBlank()) false else it.isSearchSortModified,
                clearedFilters = it.query.isNotBlank() && query.isBlank()
            )
        }
    }

    override fun setMediaFormat(value: MediaFormatLocalizable?) {
        mutableUiState.update { it.copy(mediaFormat = value) }
    }

    override fun setMediaStatus(value: MediaStatus?) {
        mutableUiState.update { it.copy(mediaStatus = value) }
    }

    override fun setCountry(value: CountryOfOrigin?) {
        mutableUiState.update { it.copy(country = value) }
    }

    override fun setYear(value: Int?) {
        mutableUiState.update { it.copy(year = value) }
    }

    override fun onGenreTagStateChanged(value: GenresAndTagsForSearch) {
        mutableUiState.update {
            if (it.genresAndTagsForSearch == value) return@update it
            it.copy(genresAndTagsForSearch = value, clearedFilters = value.totalSize == 0)
        }
    }

    override fun setEpisodesChapters(value: IntRange?) {
        mutableUiState.update { it.copy(episodesChaptersRange = value) }
    }

    override fun setDurationVolumes(value: IntRange?) {
        mutableUiState.update { it.copy(durationVolumesRange = value) }
    }

    override fun clearFilters() {
        mutableUiState.update {
            it.copy(
                mediaFormat = null,
                mediaStatus = null,
                country = null,
                year = null,
                genresAndTagsForSearch = GenresAndTagsForSearch(),
                episodesChaptersRange = null,
                durationVolumesRange = null,
                clearedFilters = true,
            )
        }
    }

    override fun onErrorDisplayed() {
        mutableUiState.update { it.copy(error = null, errorId = null) }
    }

    private fun findListForEntry(
        status: MediaListStatus,
        mediaType: MediaType,
        mediaFormat: MediaFormat
    ): String? {
        mutableUiState.value.run {
            return lists.keys.find {
                val listName = it.uppercase()
                val statusName = status.plainName(mediaType).uppercase()
                val mediaFormatName = mediaFormat.rawValue.replace('_', ' ').uppercase()
                return@find listName == statusName || listName == "$statusName $mediaFormatName"
            }
        }
    }

    private fun UserMediaListUiState.formatMatch(entry: CommonMediaListEntry) =
        mediaFormat?.value?.let { entry.media?.format == it } ?: true

    private fun UserMediaListUiState.statusMatch(entry: CommonMediaListEntry) =
        mediaStatus?.let { entry.media?.status == it } ?: true

    private fun UserMediaListUiState.countryMatch(entry: CommonMediaListEntry) =
        country?.toDto()?.let { entry.media?.countryOfOrigin == it } ?: true

    private fun UserMediaListUiState.yearMatch(entry: CommonMediaListEntry) =
        year?.let { entry.media?.startDate?.year == it } ?: true

    private fun UserMediaListUiState.genreMatch(entry: CommonMediaListEntry) =
        genresAndTagsForSearch.genreMatch(entry)

    private fun UserMediaListUiState.tagMatch(entry: CommonMediaListEntry) =
        genresAndTagsForSearch.tagMatch(entry)

    private fun GenresAndTagsForSearch.genreMatch(entry: CommonMediaListEntry): Boolean {
        val genres = entry.media?.genres ?: return true
        val genreInMatch = genreIn.isEmpty() || genres.any { genreIn.contains(it) }
        val genreNotMatch = genreNot.isEmpty() || !genres.any { genreNot.contains(it) }

        return genreInMatch && genreNotMatch
    }

    private fun GenresAndTagsForSearch.tagMatch(entry: CommonMediaListEntry): Boolean {
        val tags = entry.media?.tags
            ?.filter { (it?.rank ?: 0) >= minimumTagPercentage } ?: return true
        val tagInMatch = tagIn.isEmpty() || tags.any { tagIn.contains(it?.name) }
        val tagNotMatch = tagNot.isEmpty() || !tags.any { tagNot.contains(it?.name) }

        return tagInMatch && tagNotMatch
    }

    private fun UserMediaListUiState.episodesChaptersMatch(entry: CommonMediaListEntry) =
        episodesChaptersRange?.let { range ->
            if (mediaType == MediaType.ANIME) {
                entry.media?.basicMediaDetails?.episodes?.let { it in range } ?: false
            } else {
                entry.media?.basicMediaDetails?.chapters?.let { it in range } ?: false
            }
        } ?: true

    private fun UserMediaListUiState.volumesDurationMatch(entry: CommonMediaListEntry) =
        durationVolumesRange?.let { range ->
            if (mediaType == MediaType.ANIME) {
                entry.media?.basicMediaDetails?.duration?.let { it in range } ?: false
            } else {
                entry.media?.basicMediaDetails?.volumes?.let { it in range } ?: false
            }
        } ?: true

    private fun UserMediaListUiState.applyPartition() {
        if (separateNovelsAndManga && mediaType == MediaType.MANGA) {
            val (novels, manga) = entries.partition { it.media?.format == MediaFormat.NOVEL }
            novelEntries.addAll(novels)
            mangaEntries.addAll(manga)
        }
    }

    override fun resetPrioritizeSearchMatches() {
        viewModelScope.launch {
            mutableUiState.update {
                it.copy(isSearchSortModified = false)
            }
        }
    }

    private suspend fun calculate(
        uiState: UserMediaListUiState,
        snapshot: LocalizationSnapshot,
        enabled: Boolean,
    ): List<CommonMediaListEntry> {
        return withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val queryText = uiState.query.trim().lowercase()
            val isQueryNotBlank = queryText.isNotBlank()

            val localizedIds = if (isQueryNotBlank) {
                context.ensureActive()
                val kind = if (mediaType == MediaType.ANIME) MediaKind.ANIME else MediaKind.MANGA
                localizedListCandidates(snapshot, queryText, kind).also { context.ensureActive() }
            } else emptySet()
            val baseEntries = if (uiState.selectedListName != null) {
                uiState.lists[uiState.selectedListName].orEmpty()
            } else {
                uiState.lists.values.flatten().distinctBy { it.mediaId }
            }

            val newCache = if (uiState.filterCount > 0 || isQueryNotBlank) {
                if (uiState.isFuzzySearchEnabled) {
                    val queryTokens = if (isQueryNotBlank) {
                        queryText.split(whiteSpaceRegex).filter { it.isNotEmpty() }
                    } else emptyList()

                    val scoredEntries = baseEntries.mapNotNull { entry ->
                        context.ensureActive()
                        val matchesFilters = uiState.formatMatch(entry)
                                && uiState.statusMatch(entry)
                                && uiState.countryMatch(entry)
                                && uiState.yearMatch(entry)
                                && uiState.genreMatch(entry)
                                && uiState.tagMatch(entry)
                                && uiState.episodesChaptersMatch(entry)
                                && uiState.volumesDurationMatch(entry)

                        if (!matchesFilters) return@mapNotNull null

                        if (isQueryNotBlank) {
                            val title = entry.media?.title
                            val romajiScore = title?.romaji.fuzzyScore(queryText, queryTokens)
                            val englishScore = title?.english.fuzzyScore(queryText, queryTokens)
                            val nativeScore = title?.native.fuzzyScore(queryText, queryTokens)

                            val synonymScore = entry.media?.synonyms?.maxOfOrNull { syn ->
                                syn.fuzzyScore(queryText, queryTokens)
                            } ?: 0

                            val maxScore = maxOf(romajiScore, englishScore, nativeScore, synonymScore, if (entry.mediaId in localizedIds) 1 else 0)

                            (entry to maxScore).takeIf { maxScore > 0 }
                        } else {
                            entry to 0
                        }
                    }

                    if (isQueryNotBlank) {
                        scoredEntries.sortedByDescending { it.second }
                    } else {
                        scoredEntries
                    }
                } else {
                    baseEntries.filter { entry ->
                        context.ensureActive()
                        val matchesFilters = uiState.formatMatch(entry)
                                && uiState.statusMatch(entry)
                                && uiState.countryMatch(entry)
                                && uiState.yearMatch(entry)
                                && uiState.genreMatch(entry)
                                && uiState.tagMatch(entry)
                                && uiState.episodesChaptersMatch(entry)
                                && uiState.volumesDurationMatch(entry)

                        if (!matchesFilters) return@filter false

                        if (isQueryNotBlank) {
                            val title = entry.media?.title
                            val romajiMatch = title?.romaji?.contains(queryText, ignoreCase = true) == true
                            val englishMatch = title?.english?.contains(queryText, ignoreCase = true) == true
                            val nativeMatch = title?.native?.contains(queryText, ignoreCase = true) == true
                            val synonymMatch = entry.media?.synonyms?.any { syn ->
                                syn?.contains(queryText, ignoreCase = true) == true
                            } == true

                            romajiMatch || englishMatch || nativeMatch || synonymMatch || entry.mediaId in localizedIds
                        } else {
                            true
                        }
                    }.map { it to 0 }
                }
            } else {
                baseEntries.map { it to 0 }
            }
            context.ensureActive()
            val sortComparator: Comparator<CommonMediaListEntry> = if (uiState.sort.isTitle()) {
                val title: (CommonMediaListEntry) -> String? = { entry ->
                    val raw = entry.media?.basicMediaDetails?.title?.userPreferred
                    localizedListTitle(snapshot, entry.mediaId, raw, enabled)
                }
                listTitleComparator(uiState.sort.isDescending(), title) { it.mediaId }
            } else uiState.sort.comparator()
            if (isQueryNotBlank && uiState.isFuzzySearchEnabled && !uiState.isSearchSortModified) {
                newCache.sortedWith(compareByDescending<Pair<CommonMediaListEntry, Int>> { it.second }
                    .then(Comparator { a, b -> sortComparator.compare(a.first, b.first) })).map { it.first }
            } else newCache.map { it.first }.sortedWith(sortComparator)
        }
    }

    private var cachedListSource: MutableMap<String, List<CommonMediaListEntry>>? = null
    private var cachedListInputs: MutableMap<String, List<CommonMediaListEntry>>? = null

    private fun stateForSearch(state: UserMediaListUiState): UserMediaListUiState {
        if (state.lists !== cachedListSource) {
            cachedListSource = state.lists
            cachedListInputs = immutableListInputs(state.lists).toMutableMap()
        }
        return state.copy(lists = cachedListInputs ?: mutableMapOf())
    }

    private fun launchSearchPipeline() = viewModelScope.launch {
        combine(
            mutableUiState.map(::stateForSearch)
                .distinctUntilChanged { a, b ->
                    a.lists == b.lists && a.selectedListName == b.selectedListName &&
                        a.query == b.query && a.mediaFormat == b.mediaFormat && a.mediaStatus == b.mediaStatus &&
                        a.country == b.country && a.year == b.year && a.genresAndTagsForSearch == b.genresAndTagsForSearch &&
                        a.episodesChaptersRange == b.episodesChaptersRange && a.durationVolumesRange == b.durationVolumesRange &&
                        a.isFuzzySearchEnabled == b.isFuzzySearchEnabled && a.sort == b.sort &&
                        a.isSearchSortModified == b.isSearchSortModified && a.separateNovelsAndManga == b.separateNovelsAndManga
                },
            catalogRepository.snapshot,
            defaultPreferencesRepository.chineseTitles,
        ) { state, snapshot, enabled -> Triple(state, snapshot, enabled) }
            .publishLatest(
                compute = { (state, snapshot, enabled) -> calculate(state, snapshot, enabled) },
                publish = ::publishSearchResult,
            )
    }

    private fun publishSearchResult(result: List<CommonMediaListEntry>) = mutableUiState.update { state ->
        state.entries.clear()
        state.entries.addAll(result)
        state.mangaEntries.clear()
        state.novelEntries.clear()
        state.applyPartition()
        state
    }

    init {
        launchSearchPipeline()
        viewModelScope.launch {
            catalogRepository.load()
        }

        // score format
        mutableUiState
            .distinctUntilChangedBy { it.isMyList }
            .flatMapLatest {
                if (it.isMyList) {
                    defaultPreferencesRepository.scoreFormat.filterNotNull()
                } else emptyFlow()
            }
            .onEach { format ->
                mutableUiState.update { it.copy(scoreFormat = format) }
            }
            .launchIn(viewModelScope)

        // list style
        combine(
            listPreferencesRepository.useGeneralListStyle,
            listPreferencesRepository.generalListStyle
        ) { useGeneral, generalStyle ->
            if (useGeneral) {
                mutableUiState.update { it.copy(listStyle = generalStyle) }
            } else {
                mutableUiState
                    .distinctUntilChangedBy { it.status }
                    .collectLatest { uiState ->
                        listPreferencesRepository.stylePreference(
                            listType = ListType(
                                status = uiState.status ?: MediaListStatus.CURRENT,
                                mediaType = mediaType,
                            )
                        )?.collectLatest { style ->
                            mutableUiState.update { it.copy(listStyle = style) }
                        }
                    }
            }
        }.launchIn(viewModelScope)

        // get value from settings
        defaultPreferencesRepository.showLowPriority
            .distinctUntilChanged()
            .onEach { value ->
                mutableUiState.update { it.copy(showLowPriority = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.colorLowPriority
            .onEach { color ->
                mutableUiState.update { it.copy(lowPriorityColor = Color(color)) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.colorMediumPriority
            .onEach { color ->
                mutableUiState.update { it.copy(mediumPriorityColor = Color(color)) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.colorHighPriority
            .onEach { color ->
                mutableUiState.update { it.copy(highPriorityColor = Color(color)) }
            }
            .launchIn(viewModelScope)

        if (mediaType == MediaType.MANGA) {
            defaultPreferencesRepository.separateNovelsAndManga
                .onEach { isEnabled ->
                    mutableUiState.update { uiState ->
                        uiState.copy(separateNovelsAndManga = isEnabled)
                    }
                }
                .launchIn(viewModelScope)
        }

        defaultPreferencesRepository.useFuzzySearch
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { isEnabled ->
                mutableUiState.update { it.copy(isFuzzySearchEnabled = isEnabled) }
            }
            .launchIn(viewModelScope)

        // grid items per row
        listPreferencesRepository.gridItemsPerRow
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { value ->
                mutableUiState.update { it.copy(itemsPerRow = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.translatorApp
            .distinctUntilChanged()
            .onEach { value ->
                mutableUiState.update { it.copy(translatorApp = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.scoreSteps
            .onEach { value ->
                mutableUiState.update { it.copy(scoreStep = value) }
            }
            .launchIn(viewModelScope)

        // sort preference
        when (mediaType) {
            MediaType.ANIME -> listPreferencesRepository.animeListSort
            MediaType.MANGA -> listPreferencesRepository.mangaListSort
            else -> emptyFlow()
        }
            .distinctUntilChanged()
            .onEach { sort ->
                if (uiState.value.sort != sort) {
                    mutableUiState.update { it.copy(sort = sort) }
                }
            }
            .launchIn(viewModelScope)

        // section order and custom lists
        when (mediaType) {
            MediaType.ANIME -> defaultPreferencesRepository.animeLists
            MediaType.MANGA -> defaultPreferencesRepository.mangaLists
            else -> emptyFlow()
        }
            .distinctUntilChanged()
            .onEach { listNames ->
                mutableUiState.update { it.copy(orderedListNames = listNames) }
            }
            .launchIn(viewModelScope)

        mutableUiState
            .distinctUntilChanged { _, new ->
                !new.fetchFromNetwork
            }
            .flatMapLatest { uiState ->
                val listUserId = uiState.userId ?: myUserId.first()
                val sort = if (uiState.sort.isTitle()) {
                    listOf(MediaListSort.MEDIA_ID)
                } else {
                    listOf(uiState.sort)
                }
                mediaListRepository.getMediaListCollection(
                    userId = listUserId,
                    mediaType = mediaType,
                    sort = sort,
                    fetchFromNetwork = uiState.fetchFromNetwork,
                    chunk = null,
                    perChunk = null
                )
            }
            .onEach { result ->
                if (result is PagedResult.Success) {
                    val processedLists = withContext(Dispatchers.IO) {
                        result.list.mapNotNull { list ->
                            list?.name?.let { name ->
                                var entries =
                                    list.entries?.mapNotNull { it?.commonMediaListEntry }.orEmpty()
                                Triple(name, entries, list.isCustomList == false)
                            }
                        }
                    }

                    mutableUiState.update { uiState ->
                        val lists = if (result.currentPage == 1 || result.currentPage == null) mutableMapOf() else uiState.lists.toMutableMap()
                        val standardListNames = if (result.currentPage == 1 || result.currentPage == null) {
                            mutableSetOf<String>()
                        } else {
                            uiState.standardListNames.toMutableSet()
                        }
                        var hasNewEntries = false
                        processedLists.forEach { (name, entries, isStandardList) ->
                            lists[name] = lists[name].orEmpty() + entries
                            if (isStandardList) standardListNames.add(name)
                            if ((uiState.selectedListName == null && isStandardList) || name == uiState.selectedListName) {
                                if (entries.isNotEmpty()) hasNewEntries = true
                            }
                        }

                        val loadMore = !hasNewEntries && result.hasNextPage
                        uiState.copy(
                            lists = lists,
                            standardListNames = standardListNames,
                            fetchFromNetwork = false,
                            isLoading = loadMore,
                        )
                    }
                } else {
                    mutableUiState.update { uiState ->
                        if (result is PagedResult.Error) {
                            uiState.setError(result.message)
                        }
                        uiState.setLoading(result is PagedResult.Loading)
                    }
                }
            }
            .launchIn(viewModelScope)
    }
}
