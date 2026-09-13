package com.axiel7.anihyou.feature.characterdetails

import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.CharacterRepository
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.FavoriteRepository
import com.axiel7.anihyou.core.network.CharacterDetailsQuery
import com.axiel7.anihyou.core.network.CharacterMediaQuery
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
class CharacterLocalizationRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private val characterRepository = mockk<CharacterRepository>(relaxed = true)
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
    fun testCharacterDetailsAndMediaRefreshOnLocalizationBump() = runTest(testDispatcher) {
        val dummyCharacter = mockk<CharacterDetailsQuery.Character>(relaxed = true)
        val dummyMedia = listOf(mockk<CharacterMediaQuery.Edge>(relaxed = true))
        val pagedMedia = PagedResult.Success(list = dummyMedia, currentPage = 1, hasNextPage = true)

        every { characterRepository.getCharacterDetails(101) } returns flowOf(DataResult.Success(dummyCharacter))
        every { characterRepository.getCharacterMediaPage(characterId = 101, page = 1) } returns flowOf(pagedMedia)

        val viewModel = CharacterDetailsViewModel(
            arguments = Route.CharacterDetails(id = 101),
            defaultPreferencesRepository = defaultPreferencesRepository,
            characterRepository = characterRepository,
            favoriteRepository = favoriteRepository
        )

        // Let initial character details load finish
        advanceUntilIdle()

        // Load media page 1
        viewModel.onLoadMore()
        advanceUntilIdle()

        verify(exactly = 1) { characterRepository.getCharacterDetails(101) }
        verify(exactly = 1) { characterRepository.getCharacterMediaPage(101, 1) }
        assertEquals(1, viewModel.uiState.value.media.size)

        // Emit localization generation bump
        localizationConfigFlow.value = LocalizationConfigSnapshot(configVersion = 1L)
        advanceUntilIdle()

        verify(exactly = 2) { characterRepository.getCharacterDetails(101) }
        verify(exactly = 2) { characterRepository.getCharacterMediaPage(101, 1) }
        // Old data replaced safely without leaving empty list
        assertEquals(1, viewModel.uiState.value.media.size)
    }
}
