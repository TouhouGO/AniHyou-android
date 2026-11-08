package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.model.media.CountryOfOrigin
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaSeason
import com.axiel7.anihyou.core.network.type.MediaSource
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType

/** Pure, complete projection of media-search filters across every request path. */
internal data class MediaSearchRequest(
    val mediaType: MediaType,
    val genreIn: List<String>,
    val genreNotIn: List<String>,
    val tagIn: List<String>,
    val tagNotIn: List<String>,
    val minimumTagPercentage: Int?,
    val formatIn: List<MediaFormat>,
    val statusIn: List<MediaStatus>,
    val episodesLesser: Int?,
    val episodesGreater: Int?,
    val durationLesser: Int?,
    val durationGreater: Int?,
    val chaptersLesser: Int?,
    val chaptersGreater: Int?,
    val volumesLesser: Int?,
    val volumesGreater: Int?,
    val startYear: Int?,
    val endYear: Int?,
    val season: MediaSeason?,
    val onList: Boolean?,
    val isLicensed: Boolean?,
    val isAdult: Boolean?,
    val country: CountryOfOrigin?,
    val sourceIn: List<MediaSource>,
) {
    companion object {
        fun from(state: SearchUiState): MediaSearchRequest? {
            val mediaType = state.mediaType ?: return null
            val range = state.episodesChaptersRange
            val duration = state.durationVolumesRange
            return MediaSearchRequest(
                mediaType = mediaType,
                genreIn = state.genresAndTagsForSearch.genreIn.toList(),
                genreNotIn = state.genresAndTagsForSearch.genreNot.toList(),
                tagIn = state.genresAndTagsForSearch.tagIn.toList(),
                tagNotIn = state.genresAndTagsForSearch.tagNot.toList(),
                minimumTagPercentage = state.genresAndTagsForSearch.minimumTagPercentage,
                formatIn = state.selectedMediaFormats.map { it.value },
                statusIn = state.selectedMediaStatuses.map { it.value },
                episodesLesser = range?.endInclusive.takeIf { state.isAnime },
                episodesGreater = range?.start?.minus(1).takeIf { state.isAnime },
                durationLesser = duration?.endInclusive.takeIf { state.isAnime },
                durationGreater = duration?.start?.minus(1).takeIf { state.isAnime },
                chaptersLesser = range?.endInclusive.takeIf { state.isManga },
                chaptersGreater = range?.start?.minus(1).takeIf { state.isManga },
                volumesLesser = duration?.endInclusive.takeIf { state.isManga },
                volumesGreater = duration?.start?.minus(1).takeIf { state.isManga },
                startYear = state.startYear,
                endYear = state.endYear,
                season = state.season,
                onList = state.onMyList,
                isLicensed = state.isDoujin?.not(),
                isAdult = state.isAdult,
                country = state.country,
                sourceIn = state.selectedSources.map { it.value },
            )
        }
    }
}
