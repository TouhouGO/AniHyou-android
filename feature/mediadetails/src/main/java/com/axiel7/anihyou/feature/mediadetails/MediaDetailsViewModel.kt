package com.axiel7.anihyou.feature.mediadetails

import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.common.viewmodel.UiStateViewModel
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.FavoriteRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.model.stats.overview.ScoreDistribution.Companion.asStat
import com.axiel7.anihyou.core.model.stats.overview.StatusDistribution.Companion.asStat
import com.axiel7.anihyou.core.network.MediaDetailsQuery
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.MediaCharacter
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.RecommendationRating
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.common.navigation.Route
import com.axiel7.anihyou.core.network.localization.BangumiMatchSource
import com.axiel7.anihyou.core.network.localization.ChineseConverter
import com.axiel7.anihyou.core.network.localization.ChineseCharacterProvider
import com.axiel7.anihyou.core.network.localization.ChineseDescriptionProvider
import com.axiel7.anihyou.core.network.localization.ChineseDescriptionFormatter
import com.axiel7.anihyou.core.network.localization.ChineseTitleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam

class MediaDetailsViewModel(
    @InjectedParam private val arguments: Route.MediaDetails,
    defaultPreferencesRepository: DefaultPreferencesRepository,
    private val mediaRepository: MediaRepository,
    private val favoriteRepository: FavoriteRepository,
    private val chineseDescriptionProvider: ChineseDescriptionProvider? = null,
    private val chineseTitleProvider: ChineseTitleProvider? = null,
    private val chineseConverter: ChineseConverter? = null,
    private val chineseCharacterProvider: ChineseCharacterProvider? = null,
) : UiStateViewModel<MediaDetailsUiState>(), MediaDetailsEvent {

    override val initialState = MediaDetailsUiState(isLoggedIn = arguments.isLoggedIn)

    override fun onUpdateListEntry(newListEntry: BasicMediaListEntry?) {
        if (mutableUiState.value.details?.mediaListEntry?.basicMediaListEntry != newListEntry) {
            mutableUiState.update { uiState ->
                uiState.copy(
                    details = uiState.details?.copy(
                        mediaListEntry = if (newListEntry != null) {
                            uiState.details.mediaListEntry?.copy(basicMediaListEntry = newListEntry)
                                ?: MediaDetailsQuery.MediaListEntry(
                                    __typename = "MediaDetailsQuery.MediaListEntry",
                                    startedAt = null,
                                    completedAt = null,
                                    id = newListEntry.id,
                                    mediaId = uiState.details.id,
                                    basicMediaListEntry = newListEntry,
                                )
                        }
                        else null
                    )
                )
            }
        }
    }

    override fun toggleFavorite() {
        mutableUiState.value.details?.let { details ->
            viewModelScope.launch {
                favoriteRepository.toggleFavorite(
                    animeId = if (details.basicMediaDetails.type == MediaType.ANIME)
                        details.id else null,
                    mangaId = if (details.basicMediaDetails.type == MediaType.MANGA)
                        details.id else null,
                ).let { result ->
                    mutableUiState.update { state ->
                        if (result is DataResult.Success && result.data != null) {
                            val newDetails = state.details
                                ?.copy(isFavourite = !state.details.isFavourite)
                                ?.also {
                                    mediaRepository.updateMediaDetailsCache(it)
                                }
                            state.copy(
                                details = newDetails
                            )
                        } else {
                            state.copy(
                                error = (result as? DataResult.Error)?.message
                            )
                        }
                    }
                }
            }
        }
    }

    private var detailsJob: kotlinx.coroutines.Job? = null
    private var charactersAndStaffJob: kotlinx.coroutines.Job? = null
    private var relationsAndRecommendationsJob: kotlinx.coroutines.Job? = null

    override fun fetchCharactersAndStaff() {
        fetchCharactersAndStaff(force = false)
    }

    private fun fetchCharactersAndStaff(force: Boolean) {
        if (!force && charactersAndStaffJob?.isActive == true) return
        charactersAndStaffJob?.cancel()
        charactersAndStaffJob = mediaRepository.getMediaCharactersAndStaff(mediaId = arguments.id)
            .onEach { result ->
                if (result is DataResult.Success) {
                    val rawStaff = result.data.staff.map { it.mediaStaff }
                    val rawCharacters = result.data.characters.map { it.mediaCharacter }
                    val bangumiSubjectId = chineseTitleProvider?.getBangumiId(arguments.id)
                    val localizedStaff = chineseCharacterProvider
                        ?.localizeMediaStaff(bangumiSubjectId, rawStaff)
                        ?: rawStaff
                    val localizedCharacters = chineseCharacterProvider
                        ?.localizeMediaCharacters(bangumiSubjectId, rawCharacters)
                        ?: rawCharacters
                    mutableUiState.update { uiState ->
                        uiState.copy(
                            staff = localizedStaff,
                            characters = localizedCharacters
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun fetchRelationsAndRecommendations() {
        relationsAndRecommendationsJob?.cancel()
        relationsAndRecommendationsJob = mediaRepository.getMediaRelationsAndRecommendations(mediaId = arguments.id)
            .onEach { result ->
                if (result is DataResult.Success) {
                    mutableUiState.update {
                        it.copy(
                            relationsAndRecommendations = result.data
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun fetchStats() {
        viewModelScope.launch {
            mediaRepository.getMediaStats(mediaId = arguments.id)
                .collectLatest { result ->
                    if (result is DataResult.Success) {
                        mutableUiState.update { uiState ->
                            uiState.copy(
                                isSuccessStats = true,
                                mediaStatusDistribution = result.data?.stats?.statusDistribution
                                    ?.mapNotNull { it?.asStat() }.orEmpty(),
                                mediaScoreDistribution = result.data?.stats?.scoreDistribution
                                    ?.mapNotNull { it?.asStat() }.orEmpty(),
                                mediaRankings = result.data?.rankings?.filterNotNull().orEmpty()
                            )
                        }
                    }
                }

            mediaRepository.getMediaFollowing(mediaId = arguments.id, page = 1)
                .collectLatest { result ->
                    if (result is PagedResult.Success) {
                        mutableUiState.update { uiState ->
                            uiState.copy(following = result.list)
                        }
                    }
                }
        }
    }

    override fun fetchThreads() {
        mediaRepository.getMediaThreadsPage(mediaId = arguments.id, page = 1)
            .onEach { result ->
                mutableUiState.update { uiState ->
                    if (result is PagedResult.Success) {
                        uiState.copy(
                            isLoadingThreads = false,
                            threads = result.list
                        )
                    } else {
                        uiState.copy(
                            isLoadingThreads = result is PagedResult.Loading,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun fetchReviews() {
        mediaRepository.getMediaReviewsPage(mediaId = arguments.id, page = 1)
            .onEach { result ->
                mutableUiState.update { uiState ->
                    if (result is PagedResult.Success) {
                        uiState.copy(
                            isLoadingReviews = false,
                            reviews = result.list
                        )
                    } else {
                        uiState.copy(
                            isLoadingThreads = result is PagedResult.Loading,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun fetchActivity() {
        mediaRepository.getMediaActivityPage(mediaId = arguments.id, page = 1)
            .onEach { result ->
                mutableUiState.update { uiState ->
                    if (result is PagedResult.Success) {
                        uiState.copy(
                            isLoadingActivity = false,
                            activity = result.list
                        )
                    } else {
                        uiState.copy(
                            isLoadingActivity = result is PagedResult.Loading,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun showVoiceActorsSheet(character: MediaCharacter) {
        mutableUiState.update { uiState ->
            uiState.copy(
                selectedCharacterVoiceActors = character.voiceActors?.mapNotNull { it?.commonVoiceActor },
                showVoiceActorsSheet = true
            )
        }
    }

    override fun hideVoiceActorSheet() {
        mutableUiState.update { it.copy(showVoiceActorsSheet = false) }
    }

    override fun onVoteClick(recommendedMediaId: Int, recommendationId: Int, rating: RecommendationRating) {
        if (!arguments.isLoggedIn) {
            mutableUiState.update { it.copy(errorId = R.string.not_logged_text) }
            return
        }

        val recommendations = mutableUiState.value.relationsAndRecommendations?.recommendations
        val targetNode = recommendations?.find { it.mediaRecommended.id == recommendationId } ?: return

        val previousUserRating = targetNode.mediaRecommended.userRating
        val newRating = if (previousUserRating == rating) RecommendationRating.NO_RATING else rating // if the new rating is the same as the old one remove the rating

        mediaRepository.saveRecommendation(
            mediaId = arguments.id, // base media id
            mediaRecommendationId = recommendedMediaId, // id of the media which gets recommended
            rating = newRating
        ).onEach { result ->
            if (result is DataResult.Success) {
                mutableUiState.update { state ->
                    val relAndRecs = state.relationsAndRecommendations ?: return@update state
                    val updatedRecs = relAndRecs.recommendations.map { node ->
                        if (node.mediaRecommended.id == recommendationId) {
                            node.copy(
                                mediaRecommended = node.mediaRecommended.copy(
                                    rating = result.data.SaveRecommendation?.rating
                                        ?: node.mediaRecommended.rating,
                                    userRating = result.data.SaveRecommendation?.userRating
                                        ?: newRating
                                )
                            )
                        } else node
                    }
                    state.copy(relationsAndRecommendations = relAndRecs.copy(recommendations = updatedRecs))
                }
            } else if (result is DataResult.Error) {
                mutableUiState.update { state ->
                    return@update state.copy(error = result.message)
                }
            }
        }.launchIn(viewModelScope)
    }

    private suspend fun fetchAnimeThemes(idMal: Int) {
        mediaRepository.getAnimeThemes(idMal = idMal)?.let {
            mutableUiState.update { state ->
                state.copy(
                    openings = it.openingThemes.orEmpty(),
                    endings = it.endingThemes.orEmpty(),
                )
            }
        }
    }

    private fun fetchChineseDescription(details: MediaDetailsQuery.Media) {
        val descriptionProvider = chineseDescriptionProvider ?: return
        if (!descriptionProvider.isEnabled) return

        viewModelScope.launch(Dispatchers.IO) {
            val bgmId = chineseTitleProvider?.getBangumiId(details.id)
            val isAnime = details.basicMediaDetails.type == MediaType.ANIME
            val nativeTitle = details.title?.native ?: details.title?.romaji

            val releaseYear = details.seasonYear ?: details.startDate?.fuzzyDate?.year
            val bgmInfo = descriptionProvider.getOrFetchInfo(
                mediaId = details.id,
                bangumiId = bgmId,
                nativeTitle = nativeTitle,
                isAnime = isAnime,
                releaseYear = releaseYear
            )

            if (!bgmInfo.summary.isNullOrBlank()) {
                val newDescription = ChineseDescriptionFormatter.format(
                    bangumiSummary = bgmInfo.summary,
                    originalDescription = details.description,
                    chineseConverter = chineseConverter
                ) ?: details.description
                mutableUiState.update { state ->
                    val curDetails = state.details ?: return@update state
                    if (curDetails.id == details.id) {
                        state.copy(details = curDetails.copy(description = newDescription))
                    } else state
                }
            }

            val nameCn = bgmInfo.nameCn
            if (bgmInfo.source == BangumiMatchSource.EXPLICIT_ID && chineseTitleProvider?.isEnabled == true && !nameCn.isNullOrBlank()) {
                chineseTitleProvider.updateTitle(details.id, nameCn, bgmInfo.bangumiId)
            }
            if (chineseTitleProvider?.isEnabled == true && !nameCn.isNullOrBlank() && bgmInfo.source != BangumiMatchSource.NONE) {
                mutableUiState.update { state ->
                    val curDetails = state.details ?: return@update state
                    if (curDetails.id == details.id) {
                        val newTitle = curDetails.title?.copy(userPreferred = nameCn)
                            ?: MediaDetailsQuery.Title(
                                __typename = "MediaTitle",
                                userPreferred = nameCn,
                                romaji = null,
                                english = null,
                                native = null
                            )
                        state.copy(details = curDetails.copy(title = newTitle))
                    } else state
                }
            }
        }
    }

    private fun loadMediaDetails() {
        detailsJob?.cancel()
        detailsJob = mediaRepository.getMediaDetails(mediaId = arguments.id)
            .onEach { result ->
                mutableUiState.updateAndGet {
                    if (result is DataResult.Success) {
                        it.copy(
                            isLoading = false,
                            details = result.data
                        )
                    } else {
                        result.toUiState()
                    }
                }.also {
                    it.details?.let { details ->
                        details.idMal?.let { idMal ->
                            if (details.basicMediaDetails.type == MediaType.ANIME)
                                fetchAnimeThemes(idMal)
                        }
                        fetchChineseDescription(details)
                        if (chineseCharacterProvider?.isEnabled == true &&
                            it.characters == null && it.staff == null
                        ) {
                            fetchCharactersAndStaff()
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    init {
        defaultPreferencesRepository.coloredMedia
            .onEach { value ->
                mutableUiState.update { it.copy(coloredMedia = value) }
            }
            .launchIn(viewModelScope)

        loadMediaDetails()

        defaultPreferencesRepository.localizationConfig
            .distinctUntilChangedBy { it.configVersion }
            .drop(1)
            .onEach {
                loadMediaDetails()
                if (!mutableUiState.value.characters.isNullOrEmpty() || !mutableUiState.value.staff.isNullOrEmpty()) {
                    fetchCharactersAndStaff(force = true)
                }
                if (mutableUiState.value.relationsAndRecommendations != null) {
                    fetchRelationsAndRecommendations()
                }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.translatorApp
            .onEach { value ->
                mutableUiState.update { it.copy(translatorApp = value) }
            }
            .launchIn(viewModelScope)

        mutableUiState
            .mapNotNull { it.details?.basicMediaDetails?.type }
            .distinctUntilChanged()
            .onEach { mediaType ->
                defaultPreferencesRepository.customLinks(mediaType)
                    .filterNotNull()
                    .collectLatest { value ->
                        mutableUiState.update { it.copy(customLinks = value) }
                    }
            }
            .launchIn(viewModelScope)
    }
}
