package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.model.SearchType
import com.axiel7.anihyou.core.model.genre.GenresAndTagsForSearch
import com.axiel7.anihyou.core.model.media.CountryOfOrigin
import com.axiel7.anihyou.core.model.media.MediaFormatLocalizable
import com.axiel7.anihyou.core.model.media.MediaSourceLocalizable
import com.axiel7.anihyou.core.model.media.MediaStatusLocalizable
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaSeason
import com.axiel7.anihyou.core.network.type.MediaSource
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSearchRequestTest {
    @Test fun everyFilterIsProjectedOneToOne() {
        val state = SearchUiState(
            query = "火影",
            searchType = SearchType.ANIME,
            mediaSort = com.axiel7.anihyou.core.network.type.MediaSort.SEARCH_MATCH,
            isLoggedIn = false,
            genresAndTagsForSearch = GenresAndTagsForSearch(
                genreIn = setOf("Action"), genreNot = setOf("Horror"),
                tagIn = setOf("Ninja"), tagNot = setOf("School"), minimumTagPercentage = 70,
            ),
            selectedMediaFormats = persistentListOf(MediaFormatLocalizable.TV),
            selectedMediaStatuses = persistentListOf(MediaStatusLocalizable.RELEASING),
            startYear = 2002,
            endYear = 2026,
            season = MediaSeason.SPRING,
            episodesChaptersRange = 2..500,
            durationVolumesRange = 23..30,
            onMyList = true,
            isDoujin = false,
            isAdult = false,
            country = CountryOfOrigin.JAPAN,
            selectedSources = persistentListOf(MediaSourceLocalizable.MANGA),
        )

        val request = requireNotNull(MediaSearchRequest.from(state))

        assertEquals(MediaType.ANIME, request.mediaType)
        assertEquals(listOf("Action"), request.genreIn)
        assertEquals(listOf("Horror"), request.genreNotIn)
        assertEquals(listOf("Ninja"), request.tagIn)
        assertEquals(listOf("School"), request.tagNotIn)
        assertEquals(70, request.minimumTagPercentage)
        assertEquals(listOf(MediaFormat.TV), request.formatIn)
        assertEquals(listOf(MediaStatus.RELEASING), request.statusIn)
        assertEquals(500, request.episodesLesser)
        assertEquals(1, request.episodesGreater)
        assertEquals(30, request.durationLesser)
        assertEquals(22, request.durationGreater)
        assertNull(request.chaptersLesser)
        assertNull(request.chaptersGreater)
        assertNull(request.volumesLesser)
        assertNull(request.volumesGreater)
        assertEquals(2002, request.startYear)
        assertEquals(2026, request.endYear)
        assertEquals(MediaSeason.SPRING, request.season)
        assertEquals(true, request.onList)
        assertEquals(true, request.isLicensed)
        assertEquals(false, request.isAdult)
        assertEquals(CountryOfOrigin.JAPAN, request.country)
        assertEquals(listOf(MediaSource.MANGA), request.sourceIn)
    }

    @Test fun mangaRangesMapToChaptersAndVolumes() {
        val request = requireNotNull(MediaSearchRequest.from(SearchUiState(
            searchType = SearchType.MANGA,
            mediaSort = com.axiel7.anihyou.core.network.type.MediaSort.SEARCH_MATCH,
            isLoggedIn = false,
            episodesChaptersRange = 10..20,
            durationVolumesRange = 2..4,
        )))

        assertEquals(MediaType.MANGA, request.mediaType)
        assertEquals(20, request.chaptersLesser)
        assertEquals(9, request.chaptersGreater)
        assertEquals(4, request.volumesLesser)
        assertEquals(1, request.volumesGreater)
        assertNull(request.episodesLesser)
        assertNull(request.durationLesser)
    }
}
