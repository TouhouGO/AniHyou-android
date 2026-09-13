package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.network.localization.LocalizationConfigSnapshot
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
class CalendarLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val defaultPreferencesRepository: DefaultPreferencesRepository = mockk(relaxed = true)
    private val mediaRepository: MediaRepository = mockk(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
        every { defaultPreferencesRepository.calendarOnMyList } returns flowOf(false)
        every { defaultPreferencesRepository.displayAdult } returns flowOf(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCalendarRefreshesFromNetworkOnLocalizationBump() = runTest(testDispatcher) {
        val dummyMedia = mockk<ExploreMedia>(relaxed = true)
        every {
            mediaRepository.getAiringAnimesPage(
                airingAtGreater = any(),
                airingAtLesser = any(),
                onMyList = any(),
                isAdult = any(),
                page = any(),
                perPage = any(),
                fetchFromNetwork = any()
            )
        } returns flowOf(PagedResult.Success(listOf(dummyMedia), currentPage = 1, hasNextPage = false))

        val viewModel = CalendarViewModel(
            mediaRepository = mediaRepository,
            defaultPreferencesRepository = defaultPreferencesRepository
        )

        advanceUntilIdle()

        verify(atLeast = 1) {
            mediaRepository.getAiringAnimesPage(
                airingAtGreater = any(),
                airingAtLesser = any(),
                onMyList = any(),
                isAdult = any(),
                page = 1,
                perPage = any(),
                fetchFromNetwork = false
            )
        }

        // Localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(atLeast = 1) {
            mediaRepository.getAiringAnimesPage(
                airingAtGreater = any(),
                airingAtLesser = any(),
                onMyList = any(),
                isAdult = any(),
                page = 1,
                perPage = any(),
                fetchFromNetwork = true
            )
        }
    }
}
