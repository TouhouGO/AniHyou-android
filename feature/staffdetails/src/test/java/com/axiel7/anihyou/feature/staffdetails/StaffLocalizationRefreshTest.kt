package com.axiel7.anihyou.feature.staffdetails

import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.FavoriteRepository
import com.axiel7.anihyou.core.domain.repository.StaffRepository
import com.axiel7.anihyou.core.network.StaffDetailsQuery
import com.axiel7.anihyou.core.network.StaffMediaQuery
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StaffLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val favoriteRepository = mockk<FavoriteRepository>(relaxed = true)
    private val defaultPreferencesRepository = mockk<DefaultPreferencesRepository>(relaxed = true)
    private val localizationConfigFlow = MutableStateFlow(LocalizationConfigSnapshot(configVersion = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { defaultPreferencesRepository.localizationConfig } returns localizationConfigFlow
        every { defaultPreferencesRepository.translatorApp } returns flowOf(mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testStaffDetailsAndMediaRefreshOnLocalizationBump() = runTest(testDispatcher) {
        val dummyStaff = mockk<StaffDetailsQuery.Staff>(relaxed = true)
        val dummyEdge = mockk<StaffMediaQuery.Edge>(relaxed = true)
        val dummyGrouped = com.axiel7.anihyou.core.model.staff.StaffMediaGrouped(dummyEdge, listOf("Director"))
        val dummyMedia = listOf(Pair(1, dummyGrouped))
        val pagedMedia = PagedResult.Success(list = dummyMedia, currentPage = 1, hasNextPage = true)

        every { staffRepository.getStaffDetails(202) } returns flowOf(DataResult.Success(dummyStaff))
        every { staffRepository.getStaffMediaPage(staffId = 202, onList = null, page = 1) } returns flowOf(pagedMedia)

        val viewModel = StaffDetailsViewModel(
            arguments = Route.StaffDetails(id = 202),
            defaultPreferencesRepository = defaultPreferencesRepository,
            staffRepository = staffRepository,
            favoriteRepository = favoriteRepository
        )

        viewModel.loadNextPageMedia()
        advanceUntilIdle()

        verify(exactly = 1) { staffRepository.getStaffDetails(202) }
        verify(exactly = 1) { staffRepository.getStaffMediaPage(staffId = 202, onList = null, page = 1) }
        assertEquals(1, viewModel.uiState.value.media.size)

        // Localization bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(exactly = 2) { staffRepository.getStaffDetails(202) }
        verify(exactly = 2) { staffRepository.getStaffMediaPage(staffId = 202, onList = null, page = 1) }
        assertEquals(1, viewModel.uiState.value.media.size)
    }
}
