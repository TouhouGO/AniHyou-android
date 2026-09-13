package com.axiel7.anihyou.feature.profile

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.ActivityRepository
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.FavoriteRepository
import com.axiel7.anihyou.core.domain.repository.LikeRepository
import com.axiel7.anihyou.core.domain.repository.UserRepository
import com.axiel7.anihyou.core.network.UserActivityQuery
import com.axiel7.anihyou.core.network.UserFavoritesAnimeQuery
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
import com.axiel7.anihyou.core.ui.common.navigation.Route
import com.axiel7.anihyou.feature.profile.favorites.UserFavoritesViewModel
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
class ProfileLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val defaultPreferencesRepository: DefaultPreferencesRepository = mockk(relaxed = true)
    private val userRepository: UserRepository = mockk(relaxed = true)
    private val activityRepository: ActivityRepository = mockk(relaxed = true)
    private val likeRepository: LikeRepository = mockk(relaxed = true)
    private val favoriteRepository: FavoriteRepository = mockk(relaxed = true)
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
    fun testProfileViewModelRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyUserInfo = mockk<com.axiel7.anihyou.core.network.fragment.UserInfo>(relaxed = true) {
            every { id } returns 12345
        }
        every {
            userRepository.getUserInfo(any(), any(), any())
        } returns flowOf(com.axiel7.anihyou.core.base.DataResult.Success(dummyUserInfo))

        val dummyActivity = mockk<UserActivityQuery.Activity>(relaxed = true)
        every {
            userRepository.getUserActivity(userId = any(), fetchFromNetwork = any(), page = any())
        } returns flowOf(PagedResult.Success(listOf(dummyActivity), currentPage = 1, hasNextPage = false))

        val viewModel = ProfileViewModel(
            arguments = Route.UserDetails(id = 12345, userName = null),
            userRepository = userRepository,
            likeRepository = likeRepository,
            activityRepository = activityRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        advanceUntilIdle()

        // Bump localization
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(atLeast = 1) {
            userRepository.getUserActivity(userId = 12345, fetchFromNetwork = true, page = 1)
        }
    }

    @Test
    fun testUserFavoritesViewModelRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyAnime = mockk<UserFavoritesAnimeQuery.Node>(relaxed = true)
        every {
            favoriteRepository.getFavoriteAnime(userId = any(), page = any(), fetchFromNetwork = any())
        } returns flowOf(PagedResult.Success(listOf(dummyAnime), currentPage = 1, hasNextPage = false))

        val viewModel = UserFavoritesViewModel(
            userId = 12345,
            isMyProfile = false,
            favoriteRepository = favoriteRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        advanceUntilIdle()

        verify(atLeast = 1) {
            favoriteRepository.getFavoriteAnime(userId = 12345, page = 1, fetchFromNetwork = false)
        }

        // Bump localization
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(atLeast = 1) {
            favoriteRepository.getFavoriteAnime(userId = 12345, page = 1, fetchFromNetwork = true)
        }
    }
}
