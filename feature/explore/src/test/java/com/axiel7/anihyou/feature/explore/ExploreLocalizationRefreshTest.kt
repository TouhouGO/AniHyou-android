package com.axiel7.anihyou.feature.explore

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
import com.axiel7.anihyou.feature.explore.anime.AnimeExploreViewModel
import com.axiel7.anihyou.feature.explore.manga.MangaExploreViewModel
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
class ExploreLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mediaRepository = mockk<MediaRepository>(relaxed = true)
    private val defaultPreferencesRepository = mockk<DefaultPreferencesRepository>(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
        every { defaultPreferencesRepository.airingOnMyList } returns flowOf(false)
        every { defaultPreferencesRepository.displayAdult } returns flowOf(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testAnimeExploreRefreshesListsOnLocalizationBump() = runTest(testDispatcher) {
        val dummyResult = PagedResult.Success(
            list = listOf(mockk<ExploreMedia>(relaxed = true)),
            hasNextPage = true,
            currentPage = 1
        )
        every { mediaRepository.getAiringAnimesPage(airingAtGreater = any(), isAdult = any(), page = 1) } returns flowOf(dummyResult)
        every { mediaRepository.getSeasonalAnimePage(animeSeason = any(), isAdult = any(), page = 1) } returns flowOf(dummyResult)
        every { mediaRepository.getMediaSortedPage(mediaType = any(), sort = any(), isAdult = any(), page = 1) } returns flowOf(dummyResult)

        val viewModel = AnimeExploreViewModel(
            defaultPreferencesRepository = defaultPreferencesRepository,
            mediaRepository = mediaRepository
        )

        viewModel.fetchAiringAnime()
        viewModel.fetchThisSeasonAnime()
        advanceUntilIdle()

        verify(exactly = 1) { mediaRepository.getAiringAnimesPage(airingAtGreater = any(), isAdult = any(), page = 1) }
        verify(exactly = 1) { mediaRepository.getSeasonalAnimePage(animeSeason = any(), isAdult = any(), page = 1) }

        // Trigger bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(exactly = 2) { mediaRepository.getAiringAnimesPage(airingAtGreater = any(), isAdult = any(), page = 1) }
        verify(exactly = 2) { mediaRepository.getSeasonalAnimePage(animeSeason = any(), isAdult = any(), page = 1) }
    }

    @Test
    fun testMangaExploreRefreshesListsOnLocalizationBump() = runTest(testDispatcher) {
        val dummyResult = PagedResult.Success(
            list = listOf(mockk<ExploreMedia>(relaxed = true)),
            hasNextPage = true,
            currentPage = 1
        )
        every { mediaRepository.getMediaSortedPage(mediaType = any(), sort = any(), isAdult = any(), page = 1) } returns flowOf(dummyResult)

        val viewModel = MangaExploreViewModel(
            defaultPreferencesRepository = defaultPreferencesRepository,
            mediaRepository = mediaRepository
        )

        viewModel.fetchTrendingManga()
        advanceUntilIdle()

        verify(atLeast = 1) { mediaRepository.getMediaSortedPage(mediaType = any(), sort = any(), isAdult = any(), page = 1) }

        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(atLeast = 2) { mediaRepository.getMediaSortedPage(mediaType = any(), sort = any(), isAdult = any(), page = 1) }
    }
}
