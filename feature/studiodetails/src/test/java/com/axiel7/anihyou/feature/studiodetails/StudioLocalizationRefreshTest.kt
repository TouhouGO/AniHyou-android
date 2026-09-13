package com.axiel7.anihyou.feature.studiodetails

import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.FavoriteRepository
import com.axiel7.anihyou.core.domain.repository.StudioRepository
import com.axiel7.anihyou.core.network.StudioDetailsQuery
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
class StudioLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val defaultPreferencesRepository: DefaultPreferencesRepository = mockk(relaxed = true)
    private val studioRepository: StudioRepository = mockk(relaxed = true)
    private val favoriteRepository: FavoriteRepository = mockk(relaxed = true)
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
    fun testStudioDetailsRefreshesOnLocalizationBump() = runTest(testDispatcher) {
        val dummyStudio = mockk<StudioDetailsQuery.Studio>(relaxed = true)
        every {
            studioRepository.getStudioDetails(any(), any())
        } returns flowOf(DataResult.Success(dummyStudio))

        val viewModel = StudioDetailsViewModel(
            arguments = Route.StudioDetails(id = 888),
            studioRepository = studioRepository,
            favoriteRepository = favoriteRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        advanceUntilIdle()

        verify(atLeast = 1) {
            studioRepository.getStudioDetails(888, any())
        }

        // Localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(atLeast = 2) {
            studioRepository.getStudioDetails(888, any())
        }
    }
}
