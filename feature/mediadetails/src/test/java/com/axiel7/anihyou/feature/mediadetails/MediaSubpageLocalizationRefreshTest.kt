package com.axiel7.anihyou.feature.mediadetails

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.ActivityRepository
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LikeRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.network.MediaActivityQuery
import com.axiel7.anihyou.core.network.MediaCharactersQuery
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
import com.axiel7.anihyou.core.ui.common.navigation.Route
import com.axiel7.anihyou.feature.mediadetails.activity.MediaActivityViewModel
import com.axiel7.anihyou.feature.mediadetails.characters.MediaCharactersViewModel
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
class MediaSubpageLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val defaultPreferencesRepository: DefaultPreferencesRepository = mockk(relaxed = true)
    private val mediaRepository: MediaRepository = mockk(relaxed = true)
    private val likeRepository: LikeRepository = mockk(relaxed = true)
    private val activityRepository: ActivityRepository = mockk(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
        every { defaultPreferencesRepository.userId } returns flowOf(12345)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testMediaActivityRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyActivity = mockk<com.axiel7.anihyou.core.network.fragment.ListActivityFragment>(relaxed = true)
        every {
            mediaRepository.getMediaActivityPage(mediaId = any(), userId = any(), page = any())
        } returns flowOf(PagedResult.Success(listOf(dummyActivity), currentPage = 1, hasNextPage = false))

        val viewModel = MediaActivityViewModel(
            arguments = Route.MediaActivity(mediaId = 555),
            mediaRepository = mediaRepository,
            likeRepository = likeRepository,
            activityRepository = activityRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        advanceUntilIdle()

        verify(atLeast = 1) {
            mediaRepository.getMediaActivityPage(mediaId = 555, userId = any(), page = 1)
        }

        // Localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(atLeast = 2) {
            mediaRepository.getMediaActivityPage(mediaId = 555, userId = any(), page = 1)
        }
    }

    @Test
    fun testMediaCharactersRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyCharacter = mockk<com.axiel7.anihyou.core.network.fragment.MediaCharacter>(relaxed = true)
        every {
            mediaRepository.getMediaCharactersPage(mediaId = any(), page = any())
        } returns flowOf(PagedResult.Success(listOf(dummyCharacter), currentPage = 1, hasNextPage = false))

        val viewModel = MediaCharactersViewModel(
            arguments = Route.MediaCharacters(mediaId = 555),
            mediaRepository = mediaRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        advanceUntilIdle()

        verify(atLeast = 1) {
            mediaRepository.getMediaCharactersPage(mediaId = 555, page = 1)
        }

        // Localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(atLeast = 2) {
            mediaRepository.getMediaCharactersPage(mediaId = 555, page = 1)
        }
    }
}
