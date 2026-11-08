package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.LocalizationCatalogRepository
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import io.mockk.coEvery
import io.mockk.Runs
import io.mockk.just
import kotlinx.coroutines.flow.MutableStateFlow
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.SavedFiltersRepository
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.model.SearchType
import com.axiel7.anihyou.core.network.SearchMediaQuery
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.ui.common.navigation.Route
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.viewModelScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRequestCancellationTest {
    @Test
    fun leavingMediaSearchCancelsRequestAndReturningStartsAnother() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val search = mockk<SearchRepository>()
        val preferences = mockk<DefaultPreferencesRepository>()
        val saved = mockk<SavedFiltersRepository>()
        every { preferences.titleLanguage } returns flowOf(null)
        every { search.searchCharacter(any(), any(), any()) } returns emptyFlow()
        var started = 0
        var canceled = 0
        every {
            search.searchMedia(
                mediaType = any(), query = any(), sort = any(), genreIn = any(), genreNotIn = any(),
                tagIn = any(), tagNotIn = any(), minimumTagPercentage = any(), formatIn = any(),
                statusIn = any(), episodesLesser = any(), episodesGreater = any(), durationLesser = any(),
                durationGreater = any(), chaptersLesser = any(), chaptersGreater = any(),
                volumesLesser = any(), volumesGreater = any(), weightedScoreLesser = any(),
                weightedScoreGreater = any(), startYear = any(), endYear = any(), season = any(),
                onList = any(), isLicensed = any(), isAdult = any(), country = any(), sourceIn = any(),
                page = any(), perPage = any(), mediaIds = any(),
            )
        } answers {
            flow<PagedResult<SearchMediaQuery.Medium>> {
                started++
                try { awaitCancellation() } finally { canceled++ }
            }
        }
        val catalog = mockk<LocalizationCatalogRepository>()
        coEvery { catalog.load() } just Runs
        every { catalog.snapshot } returns MutableStateFlow(LocalizationSnapshot.EMPTY)
        val viewModel = SearchViewModel(Route.Search(mediaType = MediaType.ANIME), false, search, saved, preferences, catalog)
        try {
            viewModel.setQuery("Naruto")
            advanceUntilIdle()
            assertEquals(1, started)
            viewModel.setSearchType(SearchType.CHARACTER)
            advanceUntilIdle()
            assertEquals(1, canceled)
            viewModel.setSearchType(SearchType.ANIME)
            advanceUntilIdle()
            assertEquals(2, started)
            viewModel.setQuery("")
            advanceUntilIdle()
            assertEquals(2, canceled)
            assertTrue(viewModel.uiState.value.media.isEmpty())
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
