package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.network.SearchMediaQuery
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.ui.common.navigation.Route
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val searchRepository = mockk<SearchRepository>(relaxed = true)
    private val defaultPreferencesRepository = mockk<DefaultPreferencesRepository>(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
        every { defaultPreferencesRepository.titleLanguage } returns flowOf(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testLocalizationBumpTriggersSearchRefreshAtPage1() = runTest(testDispatcher) {
        val dummyResult = PagedResult.Success(
            list = listOf(mockk<SearchMediaQuery.Medium>(relaxed = true)),
            hasNextPage = true,
            currentPage = 1
        )
        every {
            searchRepository.searchMedia(
                mediaType = any(),
                query = any(),
                sort = any(),
                genreIn = any(),
                genreNotIn = any(),
                tagIn = any(),
                tagNotIn = any(),
                minimumTagPercentage = any(),
                formatIn = any(),
                statusIn = any(),
                episodesLesser = any(),
                episodesGreater = any(),
                durationLesser = any(),
                durationGreater = any(),
                chaptersLesser = any(),
                chaptersGreater = any(),
                volumesLesser = any(),
                volumesGreater = any(),
                startYear = any(),
                endYear = any(),
                season = any(),
                onList = any(),
                isLicensed = any(),
                isAdult = any(),
                country = any(),
                sourceIn = any(),
                page = any()
            )
        } returns flowOf(dummyResult)

        val viewModel = SearchViewModel(
            arguments = Route.Search(mediaType = MediaType.ANIME.rawValue),
            isLoggedIn = false,
            searchRepository = searchRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        viewModel.setQuery("test")
        advanceUntilIdle()

        verify(exactly = 1) {
            searchRepository.searchMedia(
                mediaType = MediaType.ANIME,
                query = "test",
                page = 1,
                sort = any(),
                genreIn = any(),
                genreNotIn = any(),
                tagIn = any(),
                tagNotIn = any(),
                minimumTagPercentage = any(),
                formatIn = any(),
                statusIn = any(),
                episodesLesser = any(),
                episodesGreater = any(),
                durationLesser = any(),
                durationGreater = any(),
                chaptersLesser = any(),
                chaptersGreater = any(),
                volumesLesser = any(),
                volumesGreater = any(),
                startYear = any(),
                endYear = any(),
                season = any(),
                onList = any(),
                isLicensed = any(),
                isAdult = any(),
                country = any(),
                sourceIn = any()
            )
        }

        // Emit new generation
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        // Should trigger a second search call at page 1
        verify(exactly = 2) {
            searchRepository.searchMedia(
                mediaType = MediaType.ANIME,
                query = "test",
                page = 1,
                sort = any(),
                genreIn = any(),
                genreNotIn = any(),
                tagIn = any(),
                tagNotIn = any(),
                minimumTagPercentage = any(),
                formatIn = any(),
                statusIn = any(),
                episodesLesser = any(),
                episodesGreater = any(),
                durationLesser = any(),
                durationGreater = any(),
                chaptersLesser = any(),
                chaptersGreater = any(),
                volumesLesser = any(),
                volumesGreater = any(),
                startYear = any(),
                endYear = any(),
                season = any(),
                onList = any(),
                isLicensed = any(),
                isAdult = any(),
                country = any(),
                sourceIn = any()
            )
        }
    }
}
