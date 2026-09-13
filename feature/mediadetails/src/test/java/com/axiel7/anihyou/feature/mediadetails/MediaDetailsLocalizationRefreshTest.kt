package com.axiel7.anihyou.feature.mediadetails

import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.FavoriteRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.model.media.MediaCharactersAndStaff
import com.axiel7.anihyou.core.model.media.MediaRelationsAndRecommendations
import com.axiel7.anihyou.core.network.MediaDetailsQuery
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
import com.axiel7.anihyou.core.ui.common.navigation.Route
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDetailsLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mediaRepository = mockk<MediaRepository>(relaxed = true)
    private val defaultPreferencesRepository = mockk<DefaultPreferencesRepository>(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
        every { defaultPreferencesRepository.coloredMedia } returns flowOf(true)
        every { defaultPreferencesRepository.translatorApp } returns flowOf(mockk(relaxed = true))
        every { defaultPreferencesRepository.customLinks(any()) } returns flowOf(emptySet())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testMediaDetailsAndLoadedSlicesRefreshOnLocalizationBump() = runTest(testDispatcher) {
        val dummyMediaDetails = mockk<MediaDetailsQuery.Media>(relaxed = true)
        val dummyChar = mockk<com.axiel7.anihyou.core.network.MediaCharactersAndStaffQuery.Edge>(relaxed = true)
        val dummyStaff = mockk<com.axiel7.anihyou.core.network.MediaCharactersAndStaffQuery.Edge1>(relaxed = true)
        val dummyCharactersAndStaff = MediaCharactersAndStaff(
            characters = listOf(dummyChar),
            staff = listOf(dummyStaff)
        )
        val dummyRelationsAndRecs = MediaRelationsAndRecommendations(
            relations = emptyList(),
            recommendations = emptyList()
        )

        every { mediaRepository.getMediaDetails(303) } returns flowOf(DataResult.Success(dummyMediaDetails))
        every { mediaRepository.getMediaCharactersAndStaff(303) } returns flowOf(DataResult.Success(dummyCharactersAndStaff))
        every { mediaRepository.getMediaRelationsAndRecommendations(303) } returns flowOf(DataResult.Success(dummyRelationsAndRecs))

        val favoriteRepository = mockk<FavoriteRepository>(relaxed = true)

        val viewModel = MediaDetailsViewModel(
            arguments = Route.MediaDetails(id = 303, isLoggedIn = false),
            defaultPreferencesRepository = defaultPreferencesRepository,
            mediaRepository = mediaRepository,
            favoriteRepository = favoriteRepository
        )

        advanceUntilIdle()
        verify(exactly = 1) { mediaRepository.getMediaDetails(303) }

        // Also fetch characters/staff and relations
        viewModel.fetchCharactersAndStaff()
        viewModel.fetchRelationsAndRecommendations()
        advanceUntilIdle()

        verify(exactly = 1) { mediaRepository.getMediaCharactersAndStaff(303) }
        verify(exactly = 1) { mediaRepository.getMediaRelationsAndRecommendations(303) }

        // Trigger bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        // Details and loaded slices should have been refreshed exactly once more
        verify(exactly = 2) { mediaRepository.getMediaDetails(303) }
        verify(exactly = 2) { mediaRepository.getMediaCharactersAndStaff(303) }
        verify(exactly = 2) { mediaRepository.getMediaRelationsAndRecommendations(303) }
    }

    @Test
    fun testRepeatedCharacterFetchReusesActiveRequest() = runTest(testDispatcher) {
        every { mediaRepository.getMediaDetails(404) } returns flow { awaitCancellation() }
        every { mediaRepository.getMediaCharactersAndStaff(404) } returns flow { awaitCancellation() }

        val viewModel = MediaDetailsViewModel(
            arguments = Route.MediaDetails(id = 404, isLoggedIn = false),
            defaultPreferencesRepository = defaultPreferencesRepository,
            mediaRepository = mediaRepository,
            favoriteRepository = mockk(relaxed = true)
        )

        viewModel.fetchCharactersAndStaff()
        viewModel.fetchCharactersAndStaff()

        verify(exactly = 1) { mediaRepository.getMediaCharactersAndStaff(404) }
    }

    @Test
    fun testExplicitBangumiIdUpdatesTitleProviderDictionary() = runTest(testDispatcher) {
        val dummyMediaDetails = mockk<MediaDetailsQuery.Media>(relaxed = true)
        every { dummyMediaDetails.id } returns 100
        every { dummyMediaDetails.basicMediaDetails.type } returns com.axiel7.anihyou.core.network.type.MediaType.ANIME
        every { mediaRepository.getMediaDetails(100) } returns flowOf(DataResult.Success(dummyMediaDetails))

        val titleProvider = mockk<com.axiel7.anihyou.core.network.localization.ChineseTitleProvider>(relaxed = true)
        val descProvider = mockk<com.axiel7.anihyou.core.network.localization.ChineseDescriptionProvider>(relaxed = true)

        every { titleProvider.isEnabled } returns true
        every { titleProvider.getBangumiId(100) } returns 555
        every { descProvider.isEnabled } returns true
        every { descProvider.getOrFetchInfo(any(), any(), any(), any<Boolean>(), any()) } returns
            com.axiel7.anihyou.core.network.localization.ChineseDescriptionProvider.BangumiMediaInfo(
                nameCn = "测试标题",
                summary = "测试简介",
                bangumiId = 555,
                source = com.axiel7.anihyou.core.network.localization.BangumiMatchSource.EXPLICIT_ID
            )

        val viewModel = MediaDetailsViewModel(
            arguments = Route.MediaDetails(id = 100, isLoggedIn = false),
            defaultPreferencesRepository = defaultPreferencesRepository,
            mediaRepository = mediaRepository,
            favoriteRepository = mockk(relaxed = true),
            chineseDescriptionProvider = descProvider,
            chineseTitleProvider = titleProvider
        )

        advanceUntilIdle()

        // With EXPLICIT_ID, updateTitle MUST be called
        verify(exactly = 1) { titleProvider.updateTitle(100, "测试标题", 555) }
    }

    @Test
    fun testUniqueSearchMatchDoesNotUpdateTitleProviderDictionary() = runTest(testDispatcher) {
        val dummyMediaDetails = mockk<MediaDetailsQuery.Media>(relaxed = true)
        every { dummyMediaDetails.id } returns 200
        every { dummyMediaDetails.basicMediaDetails.type } returns com.axiel7.anihyou.core.network.type.MediaType.ANIME
        every { mediaRepository.getMediaDetails(200) } returns flowOf(DataResult.Success(dummyMediaDetails))

        val titleProvider = mockk<com.axiel7.anihyou.core.network.localization.ChineseTitleProvider>(relaxed = true)
        val descProvider = mockk<com.axiel7.anihyou.core.network.localization.ChineseDescriptionProvider>(relaxed = true)

        every { titleProvider.isEnabled } returns true
        every { titleProvider.getBangumiId(200) } returns null
        every { descProvider.isEnabled } returns true
        every { descProvider.getOrFetchInfo(any(), any(), any(), any<Boolean>(), any()) } returns
            com.axiel7.anihyou.core.network.localization.ChineseDescriptionProvider.BangumiMediaInfo(
                nameCn = "搜索标题",
                summary = "搜索简介",
                bangumiId = 777,
                source = com.axiel7.anihyou.core.network.localization.BangumiMatchSource.UNIQUE_SEARCH_MATCH
            )

        val viewModel = MediaDetailsViewModel(
            arguments = Route.MediaDetails(id = 200, isLoggedIn = false),
            defaultPreferencesRepository = defaultPreferencesRepository,
            mediaRepository = mediaRepository,
            favoriteRepository = mockk(relaxed = true),
            chineseDescriptionProvider = descProvider,
            chineseTitleProvider = titleProvider
        )

        advanceUntilIdle()

        // With UNIQUE_SEARCH_MATCH, updateTitle MUST NOT be called (locks global dictionary writes to explicit IDs)
        verify(exactly = 0) { titleProvider.updateTitle(any(), any(), any()) }
    }

}
