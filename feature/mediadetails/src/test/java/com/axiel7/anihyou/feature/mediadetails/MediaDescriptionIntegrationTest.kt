package com.axiel7.anihyou.feature.mediadetails

import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.domain.localization.ChineseDescriptionRepository
import com.axiel7.anihyou.core.domain.localization.DescriptionState
import com.axiel7.anihyou.core.domain.repository.*
import com.axiel7.anihyou.core.localization.*
import com.axiel7.anihyou.core.model.TranslatorApp
import com.axiel7.anihyou.core.network.MediaDetailsQuery
import com.axiel7.anihyou.core.network.localization.*
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.ui.common.navigation.Route
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDescriptionIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun oneInitialFetchAndDisabledLateResultLeavesRawDtoUntouched() = runTest(dispatcher) {
        val enabled = MutableStateFlow(true)
        val preferences = mockk<DefaultPreferencesRepository>()
        every { preferences.chineseDescriptions } returns enabled
        every { preferences.coloredMedia } returns flowOf(true)
        every { preferences.translatorApp } returns flowOf(TranslatorApp.DEFAULT)
        every { preferences.isNotificationsEnabled } returns flowOf(false)
        val details = mockk<MediaDetailsQuery.Media>(relaxed = true)
        every { details.id } returns 1
        every { details.idMal } returns null
        every { details.description } returns "<b>original</b>"
        every { details.basicMediaDetails.type } returns MediaType.ANIME
        val media = mockk<MediaRepository>()
        every { media.getMediaDetails(1) } returns flowOf(DataResult.Success(details))
        val links = mockk<CustomLinksRepository>()
        coEvery { links.getAllCustomLinks(MediaType.ANIME) } returns flowOf(emptyList())
        val source = CatalogSource("s", "r", "url", "license", "a".repeat(64))
        val link = BangumiLink(MediaIdentity(MediaKind.ANIME, 1), 2, "s")
        val catalog = mockk<LocalizationCatalogRepository>()
        every { catalog.snapshot } returns MutableStateFlow(LocalizationSnapshot.create("v", listOf(source), emptyList(), links = listOf(link)))
        coEvery { catalog.load() } returns Unit
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val descriptions = ChineseDescriptionRepository(DescriptionTransport {
            calls++
            withContext(NonCancellable) { release.await() }
            DescriptionFetchResult.Available("<b>literal</b> &amp;\ntext", 2)
        })
        val vm = MediaDetailsViewModel(Route.MediaDetails(1), preferences, links, media,
            mockk<FavoriteRepository>(), mockk<AnimeNotificationsRepository>(), descriptions, catalog)
        runCurrent()
        assertEquals(1, calls)
        assertSame(details, vm.uiState.value.details)
        enabled.value = false
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(DescriptionState.Disabled, vm.uiState.value.chineseDescription)
        assertSame(details, vm.uiState.value.details)
        assertEquals("<b>original</b>", vm.uiState.value.details?.description)
        assertEquals(1, calls)
    }
}
