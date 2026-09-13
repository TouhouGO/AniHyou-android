package com.axiel7.anihyou.feature.activitydetails

import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.domain.repository.ActivityRepository
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LikeRepository
import com.axiel7.anihyou.core.network.ActivityDetailsQuery
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
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
class ActivityDetailsLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val defaultPreferencesRepository: DefaultPreferencesRepository = mockk(relaxed = true)
    private val activityRepository: ActivityRepository = mockk(relaxed = true)
    private val likeRepository: LikeRepository = mockk(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testActivityDetailsRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyData = mockk<ActivityDetailsQuery.Activity>(relaxed = true)
        every {
            activityRepository.getActivityDetails(activityId = any(), fetchFromNetwork = any())
        } returns flowOf(DataResult.Success(dummyData))

        val viewModel = ActivityDetailsViewModel(
            arguments = Route.ActivityDetails(id = 999),
            activityRepository = activityRepository,
            likeRepository = likeRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        advanceUntilIdle()

        verify(atLeast = 1) {
            activityRepository.getActivityDetails(activityId = 999, fetchFromNetwork = false)
        }

        // Bump localization
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(atLeast = 1) {
            activityRepository.getActivityDetails(activityId = 999, fetchFromNetwork = true)
        }
    }
}
