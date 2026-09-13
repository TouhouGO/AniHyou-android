package com.axiel7.anihyou.feature.explore

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.model.ListStyle
import com.axiel7.anihyou.core.model.media.AnimeSeason
import com.axiel7.anihyou.core.model.media.ChartType
import com.axiel7.anihyou.core.network.MediaChartQuery
import com.axiel7.anihyou.core.network.MediaRecommendationsQuery
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
import com.axiel7.anihyou.core.network.type.MediaSeason
import com.axiel7.anihyou.core.network.type.MediaSort
import com.axiel7.anihyou.core.network.type.RecommendationSort
import com.axiel7.anihyou.core.ui.common.navigation.Route
import com.axiel7.anihyou.feature.explore.charts.MediaChartViewModel
import com.axiel7.anihyou.feature.explore.recommendations.RecommendationsViewModel
import com.axiel7.anihyou.feature.explore.season.SeasonAnimeViewModel
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
class RemainingExploreLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val defaultPreferencesRepository: DefaultPreferencesRepository = mockk(relaxed = true)
    private val listPreferencesRepository: ListPreferencesRepository = mockk(relaxed = true)
    private val mediaRepository: MediaRepository = mockk(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
        every { defaultPreferencesRepository.displayAdult } returns flowOf(false)
        every { defaultPreferencesRepository.blurAdult } returns flowOf(false)
        every { listPreferencesRepository.seasonalListStyle } returns flowOf(ListStyle.COMPACT)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSeasonAnimeRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyMedia = mockk<ExploreMedia>(relaxed = true)
        every {
            mediaRepository.getSeasonalAnimePage(any(), any(), any(), any())
        } returns flowOf(PagedResult.Success(listOf(dummyMedia), currentPage = 1, hasNextPage = false))

        val viewModel = SeasonAnimeViewModel(
            arguments = Route.SeasonAnime(season = MediaSeason.FALL.name, year = 2026),
            defaultPreferencesRepository = defaultPreferencesRepository,
            mediaRepository = mediaRepository,
            listPreferencesRepository = listPreferencesRepository,
        )

        advanceUntilIdle()
        verify(exactly = 1) {
            mediaRepository.getSeasonalAnimePage(any(), any(), any(), page = 1)
        }

        // Localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        // Should reload page 1
        verify(exactly = 2) {
            mediaRepository.getSeasonalAnimePage(any(), any(), any(), page = 1)
        }
    }

    @Test
    fun testMediaChartRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyChartItem = mockk<MediaChartQuery.Medium>(relaxed = true)
        every {
            mediaRepository.getMediaChartPage(any(), any(), any(), any())
        } returns flowOf(PagedResult.Success(listOf(dummyChartItem), currentPage = 1, hasNextPage = false))

        val viewModel = MediaChartViewModel(
            arguments = Route.MediaChartList(type = ChartType.TOP_ANIME.name),
            defaultPreferencesRepository = defaultPreferencesRepository,
            mediaRepository = mediaRepository,
        )

        advanceUntilIdle()
        verify(exactly = 1) {
            mediaRepository.getMediaChartPage(ChartType.TOP_ANIME, any(), page = 1, perPage = any())
        }

        // Localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(exactly = 2) {
            mediaRepository.getMediaChartPage(ChartType.TOP_ANIME, any(), page = 1, perPage = any())
        }
    }

    @Test
    fun testRecommendationsRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyRec = mockk<MediaRecommendationsQuery.Recommendation>(relaxed = true)
        every {
            mediaRepository.mediaRecommendations(any(), any(), any(), any(), any(), any())
        } returns flowOf(PagedResult.Success(listOf(dummyRec), currentPage = 1, hasNextPage = false))

        val viewModel = RecommendationsViewModel(
            isLoggedIn = true,
            defaultPreferencesRepository = defaultPreferencesRepository,
            mediaRepository = mediaRepository,
        )

        advanceUntilIdle()
        verify(exactly = 1) {
            mediaRepository.mediaRecommendations(any(), any(), any(), page = 1, perPage = any(), fetchFromNetwork = false)
        }

        // Localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(exactly = 1) {
            mediaRepository.mediaRecommendations(any(), any(), any(), page = 1, perPage = any(), fetchFromNetwork = true)
        }
    }
}
