package com.axiel7.anihyou.feature.home

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.ActivityRepository
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LikeRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.domain.repository.UserRepository
import com.axiel7.anihyou.core.network.ActivityFeedQuery
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.feature.home.activity.ActivityFeedViewModel
import com.axiel7.anihyou.feature.home.current.CurrentViewModel
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
class HomeLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val defaultPreferencesRepository: DefaultPreferencesRepository = mockk(relaxed = true)
    private val mediaListRepository: MediaListRepository = mockk(relaxed = true)
    private val activityRepository: ActivityRepository = mockk(relaxed = true)
    private val userRepository: UserRepository = mockk(relaxed = true)
    private val likeRepository: LikeRepository = mockk(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
        every { defaultPreferencesRepository.userId } returns flowOf(12345)
        every { defaultPreferencesRepository.scoreFormat } returns flowOf(null)
        every { defaultPreferencesRepository.showLowPriority } returns flowOf(false)
        every { defaultPreferencesRepository.colorLowPriority } returns flowOf(0)
        every { defaultPreferencesRepository.colorMediumPriority } returns flowOf(0)
        every { defaultPreferencesRepository.colorHighPriority } returns flowOf(0)
        every { defaultPreferencesRepository.scoreSteps } returns flowOf(0.0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCurrentViewModelRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyEntry = mockk<CommonMediaListEntry>(relaxed = true)
        every {
            mediaListRepository.getUserMediaList(
                userId = any(),
                mediaType = MediaType.ANIME,
                statusIn = any(),
                sort = any(),
                scoreFormat = any(),
                fetchFromNetwork = any(),
                page = any(),
                perPage = any()
            )
        } returns flowOf(PagedResult.Success(listOf(dummyEntry), currentPage = 1, hasNextPage = false))

        every {
            mediaListRepository.getUserMediaList(
                userId = any(),
                mediaType = MediaType.MANGA,
                statusIn = any(),
                sort = any(),
                scoreFormat = any(),
                fetchFromNetwork = any(),
                page = any(),
                perPage = any()
            )
        } returns flowOf(PagedResult.Success(listOf(dummyEntry), currentPage = 1, hasNextPage = false))

        every {
            mediaListRepository.getMySeasonalAnime(
                season = any(),
                fetchFromNetwork = any(),
                page = any()
            )
        } returns flowOf(PagedResult.Success(listOf(dummyEntry), currentPage = 1, hasNextPage = false))

        val viewModel = CurrentViewModel(
            mediaListRepository = mediaListRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        advanceUntilIdle()

        verify(atLeast = 1) {
            mediaListRepository.getUserMediaList(
                userId = 12345,
                mediaType = MediaType.ANIME,
                statusIn = any(),
                sort = any(),
                scoreFormat = any(),
                fetchFromNetwork = false,
                page = any(),
                perPage = any()
            )
        }

        // Localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(atLeast = 1) {
            mediaListRepository.getUserMediaList(
                userId = 12345,
                mediaType = MediaType.ANIME,
                statusIn = any(),
                sort = any(),
                scoreFormat = any(),
                fetchFromNetwork = true,
                page = any(),
                perPage = any()
            )
        }
    }

    @Test
    fun testActivityFeedViewModelRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyActivity = mockk<ActivityFeedQuery.Activity>(relaxed = true)
        every {
            activityRepository.getActivityFeed(
                isFollowing = any(),
                typeIn = any(),
                userIdIn = any(),
                fetchFromNetwork = any(),
                page = any()
            )
        } returns flowOf(PagedResult.Success(listOf(dummyActivity), currentPage = 1, hasNextPage = false))

        val viewModel = ActivityFeedViewModel(
            activityRepository = activityRepository,
            userRepository = userRepository,
            likeRepository = likeRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        advanceUntilIdle()

        verify(atLeast = 1) {
            activityRepository.getActivityFeed(
                isFollowing = any(),
                typeIn = any(),
                userIdIn = any(),
                fetchFromNetwork = true,
                page = 1
            )
        }

        // Clear invocations count by checking subsequent bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        // Should have been called twice with fetchFromNetwork = true
        verify(exactly = 2) {
            activityRepository.getActivityFeed(
                isFollowing = any(),
                typeIn = any(),
                userIdIn = any(),
                fetchFromNetwork = true,
                page = 1
            )
        }
    }
}
