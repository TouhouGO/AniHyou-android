package com.axiel7.anihyou.feature.usermedialist

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.model.ItemsPerRow
import com.axiel7.anihyou.core.network.UserListCollectionQuery
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
import com.axiel7.anihyou.core.network.type.MediaListSort
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
class UserMediaListLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mediaListRepository = mockk<MediaListRepository>(relaxed = true)
    private val defaultPreferencesRepository = mockk<DefaultPreferencesRepository>(relaxed = true)
    private val listPreferencesRepository = mockk<ListPreferencesRepository>(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
        every { defaultPreferencesRepository.useFuzzySearch } returns flowOf(false)
        every { defaultPreferencesRepository.showLowPriority } returns flowOf(false)
        every { defaultPreferencesRepository.colorLowPriority } returns flowOf(0)
        every { defaultPreferencesRepository.colorMediumPriority } returns flowOf(0)
        every { defaultPreferencesRepository.colorHighPriority } returns flowOf(0)
        every { defaultPreferencesRepository.animeLists } returns flowOf(listOf("Watching"))
        every { defaultPreferencesRepository.mangaLists } returns flowOf(listOf("Reading"))
        every { defaultPreferencesRepository.scoreFormat } returns flowOf(null)
        every { defaultPreferencesRepository.titleLanguage } returns flowOf(null)
        every { defaultPreferencesRepository.userId } returns flowOf(12345)
        every { listPreferencesRepository.useGeneralListStyle } returns flowOf(true)
        every { listPreferencesRepository.generalListStyle } returns flowOf(mockk(relaxed = true))
        every { listPreferencesRepository.gridItemsPerRow } returns flowOf(ItemsPerRow.THREE)
        every { listPreferencesRepository.animeListSort } returns flowOf(MediaListSort.UPDATED_TIME_DESC)
        every { listPreferencesRepository.mangaListSort } returns flowOf(MediaListSort.UPDATED_TIME_DESC)
        every { listPreferencesRepository.animeListSelected } returns flowOf("Watching")
        every { listPreferencesRepository.mangaListSelected } returns flowOf("Reading")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testUserMediaListRefreshesFromNetworkOnLocalizationBump() = runTest(testDispatcher) {
        val dummyList = mockk<UserListCollectionQuery.List>(relaxed = true) {
            every { name } returns "Watching"
            every { isCustomList } returns false
            every { entries } returns emptyList()
        }
        val pagedCollection = PagedResult.Success(
            list = listOf(dummyList),
            currentPage = 1,
            hasNextPage = false
        )

        every {
            mediaListRepository.getMediaListCollection(
                userId = any(),
                mediaType = MediaType.ANIME,
                sort = any(),
                fetchFromNetwork = any(),
                chunk = any(),
                perChunk = any()
            )
        } returns flowOf(pagedCollection)

        val viewModel = UserMediaListViewModel(
            arguments = Route.UserMediaList(mediaType = MediaType.ANIME.rawValue, userId = 12345),
            defaultPreferencesRepository = defaultPreferencesRepository,
            listPreferencesRepository = listPreferencesRepository,
            mediaListRepository = mediaListRepository
        )

        advanceUntilIdle()

        verify(exactly = 1) {
            mediaListRepository.getMediaListCollection(
                userId = 12345,
                mediaType = MediaType.ANIME,
                sort = any(),
                fetchFromNetwork = false,
                chunk = any(),
                perChunk = any()
            )
        }

        // Trigger localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        // Should have triggered a fetch with fetchFromNetwork = true
        verify(exactly = 1) {
            mediaListRepository.getMediaListCollection(
                userId = 12345,
                mediaType = MediaType.ANIME,
                sort = any(),
                fetchFromNetwork = true,
                chunk = any(),
                perChunk = any()
            )
        }
    }
}
