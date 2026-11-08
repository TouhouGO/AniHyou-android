package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.localization.SearchTextNormalizer
import com.axiel7.anihyou.core.model.media.CountryOfOrigin
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaSeason
import com.axiel7.anihyou.core.network.type.MediaSort
import com.axiel7.anihyou.core.network.type.MediaSource
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType

internal data class MediaSearchKey(
    val query: String,
    val type: MediaType?,
    val sort: MediaSort,
    val selectedSort: MediaSort,
    val genres: Set<String>,
    val excludedGenres: Set<String>,
    val tags: Set<String>,
    val excludedTags: Set<String>,
    val minimumTagPercentage: Int?,
    val formats: List<MediaFormat>,
    val statuses: List<MediaStatus>,
    val startYear: Int?,
    val endYear: Int?,
    val season: MediaSeason?,
    val episodeRange: IntRange?,
    val durationRange: IntRange?,
    val onList: Boolean?,
    val isDoujin: Boolean?,
    val isAdult: Boolean?,
    val country: CountryOfOrigin?,
    val sources: List<MediaSource>,
) {
    companion object {
        fun from(state: SearchUiState) = MediaSearchKey(
            state.query, state.mediaType, state.mediaSortForSearch, state.mediaSort,
            state.genresAndTagsForSearch.genreIn.toSet(),
            state.genresAndTagsForSearch.genreNot.toSet(),
            state.genresAndTagsForSearch.tagIn.toSet(),
            state.genresAndTagsForSearch.tagNot.toSet(),
            state.genresAndTagsForSearch.minimumTagPercentage,
            state.selectedMediaFormats.map { it.value }, state.selectedMediaStatuses.map { it.value },
            state.startYear, state.endYear, state.season, state.episodesChaptersRange,
            state.durationVolumesRange, state.onMyList, state.isDoujin, state.isAdult,
            state.country, state.selectedSources.map { it.value },
        )
    }
}

internal data class MediaSearchPlan(
    val query: String,
    val mediaIds: List<Int>?,
    val sort: MediaSort,
    val catalogVersion: String?,
    val totalCandidates: Int,
    val isTruncated: Boolean,
    val usesPopularityFallback: Boolean,
) {
    val isLocalSearch: Boolean get() = mediaIds != null
}

internal data class CandidateKey(val query: String, val type: MediaType?, val catalogVersion: String)

internal data class CandidateSelection(
    val key: CandidateKey,
    val ids: List<Int>?,
    val catalogVersion: String?,
    val totalCandidates: Int,
    val isTruncated: Boolean,
)

/** Accessed on the UI dispatcher; expensive selection is computed independently. */
internal class MediaSearchSessions {
    private var candidate: CandidateSelection? = null
    private var key: MediaSearchKey? = null
    private var current: MediaSearchPlan? = null

    fun reset() {
        candidate = null
        key = null
        current = null
    }

    fun cached(state: SearchUiState, snapshot: LocalizationSnapshot): CandidateSelection? =
        candidate?.takeIf { it.key == candidateKey(state, snapshot) }

    fun commit(selection: CandidateSelection) {
        if (candidate !== selection) {
            key = null
            current = null
        }
        candidate = selection
    }

    fun plan(state: SearchUiState, snapshot: LocalizationSnapshot): MediaSearchPlan {
        val selection = cached(state, snapshot) ?: select(state, snapshot)
        return plan(state, selection)
    }

    fun plan(state: SearchUiState, selection: CandidateSelection): MediaSearchPlan {
        commit(selection)
        val nextKey = MediaSearchKey.from(state)
        current?.takeIf { nextKey == key && candidate === selection }?.let { return it }
        val ids = selection.ids
        val fallbackSort = ids != null && state.mediaSort == MediaSort.SEARCH_MATCH
        return MediaSearchPlan(
            query = if (ids != null) "" else state.query,
            mediaIds = ids,
            sort = if (ids != null && state.mediaSortForSearch == MediaSort.SEARCH_MATCH) MediaSort.POPULARITY_DESC else state.mediaSortForSearch,
            catalogVersion = selection.catalogVersion,
            totalCandidates = selection.totalCandidates,
            isTruncated = selection.isTruncated,
            usesPopularityFallback = fallbackSort,
        ).also { key = nextKey; current = it }
    }

    companion object {
        fun requiresCatalog(state: SearchUiState): Boolean =
            state.mediaType in listOf(MediaType.ANIME, MediaType.MANGA) &&
                SearchTextNormalizer.containsHan(state.query)

        fun select(state: SearchUiState, snapshot: LocalizationSnapshot): CandidateSelection {
            val kind = when (state.mediaType) {
                MediaType.ANIME -> MediaKind.ANIME
                MediaType.MANGA -> MediaKind.MANGA
                else -> null
            }
            val candidates = if (requiresCatalog(state) && kind != null) snapshot.searchAllMedia(state.query, kind) else null
            return CandidateSelection(
                candidateKey(state, snapshot),
                candidates?.ids?.takeIf { it.isNotEmpty() },
                candidates?.catalogVersion,
                candidates?.totalMatches ?: 0,
                candidates?.isTruncated == true,
            )
        }

        private fun candidateKey(state: SearchUiState, snapshot: LocalizationSnapshot): CandidateKey =
            CandidateKey(
                query = state.query,
                type = state.mediaType,
                catalogVersion = if (requiresCatalog(state)) snapshot.version else "",
            )
    }
}
