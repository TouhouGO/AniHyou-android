package com.axiel7.anihyou.feature.explore.search

import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.model.SavedFilter
import com.axiel7.anihyou.core.domain.localization.ChineseSearchRepository
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LocalizationCatalogRepository
import com.axiel7.anihyou.core.domain.repository.SavedFiltersRepository
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.localization.CatalogRecord
import com.axiel7.anihyou.core.localization.CatalogSource
import com.axiel7.anihyou.core.localization.BangumiLink
import com.axiel7.anihyou.core.localization.EntityKey
import com.axiel7.anihyou.core.localization.EntityKind
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaIdentity
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.model.SearchType
import com.axiel7.anihyou.core.model.genre.GenresAndTagsForSearch
import com.axiel7.anihyou.core.network.SearchMediaQuery
import com.axiel7.anihyou.core.network.localization.SubjectSearchPage
import com.axiel7.anihyou.core.network.localization.SubjectSearchFailure
import com.axiel7.anihyou.core.network.localization.SubjectSearchHit
import com.axiel7.anihyou.core.network.localization.SubjectSearchResult
import com.axiel7.anihyou.core.network.type.MediaSort
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.UserTitleLanguage
import com.axiel7.anihyou.core.ui.common.navigation.Route
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import io.mockk.coVerify
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchStateRegressionTest {
    private val search = mockk<SearchRepository>()
    private val preferences = mockk<DefaultPreferencesRepository>()
    private val languages = MutableStateFlow<UserTitleLanguage?>(UserTitleLanguage.ROMAJI)
    private val catalog = mockk<LocalizationCatalogRepository>()
    private val snapshot = LocalizationSnapshot.create(
        "v1",
        listOf(CatalogSource("test", "fixture", "https://example.org", "CC0", "a".repeat(64))),
        (1..10).map { CatalogRecord(EntityKey(EntityKind.MEDIA, it * 10), "火影$it", emptyList(), "test", MediaKind.ANIME) },
    )
    private val catalogSnapshot = MutableStateFlow(snapshot)
    private fun setup(worker: CoroutineDispatcher = Dispatchers.Main, online: ChineseSearchRepository? = null, handler: (Int, MediaSort) -> Flow<PagedResult<SearchMediaQuery.Medium>>): SearchViewModel {
        return setupDetailed(worker, online) { page, sort, _ -> handler(page, sort) }
    }

    private fun setupDetailed(
        worker: CoroutineDispatcher = Dispatchers.Main,
        online: ChineseSearchRepository? = null,
        handler: (Int, MediaSort, List<Int>?) -> Flow<PagedResult<SearchMediaQuery.Medium>>,
    ): SearchViewModel {
        every { preferences.titleLanguage } returns languages
        every { catalog.snapshot } returns catalogSnapshot
        coEvery { catalog.load() } just Runs
        every {
            search.searchMedia(
                mediaType = any(), query = any(), sort = any(), genreIn = any(), genreNotIn = any(),
                tagIn = any(), tagNotIn = any(), minimumTagPercentage = any(), formatIn = any(),
                statusIn = any(), episodesLesser = any(), episodesGreater = any(), durationLesser = any(),
                durationGreater = any(), chaptersLesser = any(), chaptersGreater = any(),
                volumesLesser = any(), volumesGreater = any(), weightedScoreLesser = any(),
                weightedScoreGreater = any(), startYear = any(), endYear = any(), season = any(),
                onList = any(), isLicensed = any(), isAdult = any(), country = any(), sourceIn = any(),
                page = any(), perPage = any(), mediaIds = any(),
            )
        } answers {
            handler(arg(28), arg<List<MediaSort>>(2).first(), arg(30))
        }
        return SearchViewModel(
            Route.Search(mediaType = MediaType.ANIME), false, search,
            mockk<SavedFiltersRepository>(), preferences, catalog, worker, online,
        )
    }
    @Test fun slowSupplementDoesNotBlockLocalPagesAndTypeChangeCancelsIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<SubjectSearchResult>()
        var remoteCancelled = false
        val online = ChineseSearchRepository({ _, _, _, _ ->
            try { pending.await() } finally { remoteCancelled = true }
        })
        var primaryCalls = 0
        val vm = setup(online = online) { page, _ ->
            primaryCalls++
            val item = mockk<SearchMediaQuery.Medium> { every { id } returns if (page == 1) 10 else 20 }
            flowOf(PagedResult.Success(listOf(item), page, page == 1))
        }
        try {
            vm.setQuery("火影")
            runCurrent()
            assertFalse(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.supplementLoading)
            assertEquals(listOf(10), vm.uiState.value.media.map { it.id })
            vm.onLoadMore()
            runCurrent()
            assertEquals(listOf(10, 20), vm.uiState.value.media.map { it.id })
            assertTrue(vm.uiState.value.supplementLoading)
            assertFalse(remoteCancelled)
            vm.setSearchType(SearchType.USER)
            every { search.searchUser(any(), any(), any()) } returns flowOf(PagedResult.Success(emptyList(), 1, false))
            runCurrent()
            assertTrue(remoteCancelled)
            assertFalse(vm.uiState.value.supplementStarted)
            assertTrue(primaryCalls >= 2)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun nativeSearchDoesNotWaitForCatalog() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var requests = 0
        val vm = setup { page, _ -> requests++; flowOf(PagedResult.Success(emptyList(), page, false)) }
        coEvery { catalog.load() } coAnswers { awaitCancellation() }
        try {
            vm.setQuery("Naruto")
            runCurrent()
            assertEquals(1, requests)
            assertFalse(vm.uiState.value.isLoading)
            coVerify(exactly = 0) { catalog.load() }
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun chinesePlanningUsesWorkerAndCanceledWorkCannotCommit() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val workerScheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(workerScheduler, "search-worker")
        val calls = mutableListOf<Int>()
        val vm = setup(worker) { page, _ -> calls += page; flowOf(PagedResult.Success(emptyList(), page, false)) }
        try {
            vm.setQuery("火影")
            runCurrent()
            assertTrue(calls.isEmpty())
            vm.setQuery("Naruto")
            runCurrent()
            workerScheduler.advanceUntilIdle()
            runCurrent()
            assertEquals(listOf(1), calls)
            assertFalse(vm.uiState.value.isLocalTitleSearch)
            vm.setQuery("火影")
            runCurrent()
            assertEquals(1, calls.size)
            workerScheduler.advanceUntilIdle()
            runCurrent()
            assertEquals(2, calls.size)
            assertTrue(vm.uiState.value.isLocalTitleSearch)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun computedWorkerResultCannotCommitAfterQueryChangesBeforeMainResumes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val workerScheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(workerScheduler, "search-worker")
        var calls = 0
        val vm = setup(worker) { page, _ -> calls++; flowOf(PagedResult.Success(emptyList(), page, false)) }
        try {
            vm.setQuery("火影")
            runCurrent()
            workerScheduler.runCurrent()
            assertEquals(0, calls)
            vm.setQuery("Naruto")
            runCurrent()
            assertEquals(1, calls)
            assertFalse(vm.uiState.value.isLocalTitleSearch)
            assertEquals("Naruto", vm.uiState.value.query)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun repeatedCurrentSortAndFiltersPreserveSettledState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var requests = 0
        val vm = setup { page, _ ->
            requests++
            flowOf(PagedResult.Success(emptyList(), page, true))
        }
        val saved = SavedFilter(
            id = 1, name = "same", searchType = SearchType.ANIME,
            mediaSort = MediaSort.SEARCH_MATCH, genreIn = null, genreNot = null,
            tagIn = null, tagNot = null, minimumTagPercentage = 18,
            mediaFormats = null, mediaStatuses = null, startYear = null, endYear = null,
            season = null, episodesChaptersRange = null, durationVolumesRange = null,
            onMyList = null, isDoujin = null, isAdult = false, country = null, sources = null,
        )
        try {
            vm.setQuery("Naruto")
            runCurrent()
            assertEquals(1, requests)
            val actions = listOf<() -> Unit>(
                { vm.setMediaSort(MediaSort.SEARCH_MATCH) },
                { vm.setMediaFormats(emptyList()) },
                { vm.setMediaStatuses(emptyList()) },
                { vm.setSources(emptyList()) },
                { vm.setStartYear(null) },
                { vm.setEndYear(null) },
                { vm.setSeason(null) },
                { vm.setEpisodesChapters(null) },
                { vm.setDurationVolumes(null) },
                { vm.setOnMyList(null) },
                { vm.setIsDoujin(null) },
                { vm.setIsAdult(false) },
                { vm.setCountry(null) },
                { vm.applySavedFilter(saved) },
            )
            actions.forEach { action ->
                action()
                runCurrent()
                assertFalse(vm.uiState.value.isLoading)
                assertEquals(1, requests)
            }
            vm.clearFilters()
            runCurrent()
            assertEquals(2, requests)
            vm.clearFilters()
            runCurrent()
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(2, requests)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @Test fun rangeChangesRestartExhaustedLocalSearchAtFirstPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pages = mutableListOf<Int>()
        val vm = setup { page, _ ->
            pages += page
            val item = mockk<SearchMediaQuery.Medium> { every { id } returns 20 }
            flowOf(PagedResult.Success(if (page == 1) listOf(item) else emptyList(), page, page == 1))
        }
        try {
            vm.setQuery("火影")
            runCurrent()
            assertTrue(vm.uiState.value.isLocalTitleSearch)
            vm.onLoadMore()
            runCurrent()
            assertFalse(vm.uiState.value.hasNextPage)
            assertEquals(2, vm.uiState.value.page)

            vm.setEpisodesChapters(2..5)
            runCurrent()
            assertEquals(listOf(1, 2, 1), pages)
            assertEquals(1, vm.uiState.value.page)
            assertTrue(vm.uiState.value.isLocalTitleSearch)

            vm.onLoadMore()
            runCurrent()
            assertFalse(vm.uiState.value.hasNextPage)
            vm.setDurationVolumes(10..20)
            runCurrent()
            assertEquals(listOf(1, 2, 1, 2, 1), pages)
            assertEquals(1, vm.uiState.value.page)
            assertTrue(vm.uiState.value.isLocalTitleSearch)
            assertFalse(vm.uiState.value.isLoading)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @Test fun failedFirstLocalPageRetriesWithoutSkippingOrKeepingOldGenerationRows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<Int>()
        var attempt = 0
        val vm = setup { page, _ ->
            calls += page
            attempt++
            val item = mockk<SearchMediaQuery.Medium> { every { id } returns if (attempt == 1) 10 else 20 }
            if (attempt == 2) flowOf(PagedResult.Error("temporary"))
            else flowOf(PagedResult.Success(listOf(item), page, false))
        }
        try {
            vm.setQuery("火影")
            runCurrent()
            assertEquals(listOf(10), vm.uiState.value.media.map { it.id })
            vm.setStartYear(2020)
            runCurrent()
            assertTrue(vm.uiState.value.localPageFailed)
            vm.onLoadMore()
            runCurrent()
            assertEquals(2, calls.size)
            vm.retryMediaPage()
            runCurrent()
            assertEquals(listOf(1, 1, 1), calls)
            assertEquals(listOf(20), vm.uiState.value.media.map { it.id })
            assertFalse(vm.uiState.value.localPageFailed)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun clearingRangesRestartsAtFirstPageAndReplacesOldRows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var requests = 0
        val pages = mutableListOf<Int>()
        val vm = setup { page, _ ->
            requests++
            pages += page
            val itemId = requests * 10
            val item = mockk<SearchMediaQuery.Medium> { every { id } returns itemId }
            flowOf(PagedResult.Success(listOf(item), page, true))
        }
        try {
            vm.setQuery("火影")
            runCurrent()
            vm.setEpisodesChapters(2..5)
            runCurrent()
            vm.setDurationVolumes(10..20)
            runCurrent()
            vm.onLoadMore()
            runCurrent()
            assertEquals(listOf(30, 40), vm.uiState.value.media.map { it.id })

            vm.clearFilters()
            runCurrent()
            assertEquals(null, vm.uiState.value.episodesChaptersRange)
            assertEquals(null, vm.uiState.value.durationVolumesRange)
            assertEquals(1, vm.uiState.value.page)
            assertEquals(listOf(1, 1, 1, 2, 1), pages)
            assertEquals(listOf(50), vm.uiState.value.media.map { it.id })
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @Test fun filterErrorDoesNotRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var requests = 0
        val vm = setup { _, _ ->
            flow {
                requests++
                if (requests <= 5) {
                    emit(PagedResult.Loading)
                    emit(PagedResult.Error("failed"))
                }
                awaitCancellation()
            }
        }
        try {
            vm.onGenreTagStateChanged(GenresAndTagsForSearch(genreIn = setOf("Action")))
            repeat(20) { runCurrent() }
            assertEquals(1, requests)
            assertEquals("failed", vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @Test fun titleLanguageRestartCancelsInFlightAndRestartsPagination() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<Pair<Int, MediaSort>>()
        val pending = MutableSharedFlow<PagedResult<SearchMediaQuery.Medium>>()
        val english = mockk<SearchMediaQuery.Medium> { every { id } returns 11 }
        val native = mockk<SearchMediaQuery.Medium> { every { id } returns 22 }
        val vm = setup { page, sort ->
            calls += page to sort
            if (sort == MediaSort.TITLE_ROMAJI && page == 1) pending
            else flowOf(PagedResult.Success(listOf(if (sort == MediaSort.TITLE_NATIVE) native else english), page, true))
        }
        try {
            vm.setMediaSort(MediaSort.TITLE_ROMAJI)
            vm.setQuery("Naruto")
            runCurrent()
            languages.value = UserTitleLanguage.ENGLISH
            repeat(5) { runCurrent() }
            assertEquals(listOf(1 to MediaSort.TITLE_ROMAJI, 1 to MediaSort.TITLE_ENGLISH), calls)
            assertFalse(vm.uiState.value.isLoading)
            vm.onLoadMore()
            runCurrent()
            assertEquals(2 to MediaSort.TITLE_ENGLISH, calls.last())
            languages.value = UserTitleLanguage.NATIVE
            runCurrent()
            assertEquals(1 to MediaSort.TITLE_NATIVE, calls.last())
            assertEquals(1, vm.uiState.value.page)
            assertEquals(listOf(22), vm.uiState.value.media.map { it.id })
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @Test fun clearingLocalQueryResetsNotices() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = setup { page, _ -> flowOf(PagedResult.Success(emptyList(), page, false)) }
        try {
            vm.setQuery("火影")
            runCurrent()
            assertTrue(vm.uiState.value.isLocalTitleSearch)
            vm.setQuery("")
            runCurrent()
            assertFalse(vm.uiState.value.isLocalTitleSearch)
            assertEquals(0, vm.uiState.value.localCandidateCount)
            assertFalse(vm.uiState.value.usesLocalPopularitySort)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @Test fun blankNonMediaDoesNotLoad() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = setup { page, _ -> flowOf(PagedResult.Success(emptyList(), page, false)) }
        try {
            for (type in listOf(SearchType.CHARACTER, SearchType.STAFF, SearchType.STUDIO, SearchType.USER)) {
                vm.setSearchType(type)
                runCurrent()
                assertFalse(vm.uiState.value.isLoading)
                assertFalse(vm.uiState.value.hasNextPage)
            }
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @Test fun filteredLocalRelevanceDisclosesPopularity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<MediaSort>()
        val vm = setup { page, sort -> calls += sort; flowOf(PagedResult.Success(emptyList(), page, false)) }
        try {
            vm.onGenreTagStateChanged(GenresAndTagsForSearch(genreIn = setOf("Action")))
            vm.setQuery("火影")
            runCurrent()
            assertEquals(MediaSort.POPULARITY_DESC, calls.last())
            assertTrue(vm.uiState.value.isLocalTitleSearch)
            assertTrue(vm.uiState.value.usesLocalPopularitySort)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun titleLanguageRestartImmediatelyClearsAllSupplementState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val linked = LocalizationSnapshot.create(
            "v1",
            listOf(CatalogSource("test", "fixture", "https://example.org", "CC0", "a".repeat(64))),
            listOf(CatalogRecord(EntityKey(EntityKind.MEDIA, 10), "火影", emptyList(), "test", MediaKind.ANIME)),
            links = listOf(BangumiLink(MediaIdentity(MediaKind.ANIME, 20), 42, "test")),
        )
        catalogSnapshot.value = linked
        var discoveries = 0
        val online = ChineseSearchRepository({ _, _, _, _ ->
            if (discoveries++ == 0) {
                SubjectSearchResult.Success(SubjectSearchPage(0, 25, 1, listOf(SubjectSearchHit(42, "mapped", "火影"))))
            } else SubjectSearchResult.Failed(SubjectSearchFailure.NETWORK)
        })
        val vm = setupDetailed(online = online) { page, sort, ids ->
            if (sort == MediaSort.TITLE_ENGLISH) flow { awaitCancellation() }
            else {
                val itemId = if (ids?.size == 1) ids.single() else 10
                val item = mockk<SearchMediaQuery.Medium> { every { id } returns itemId }
                flowOf(PagedResult.Success(listOf(item), page, false))
            }
        }
        try {
            vm.setMediaSort(MediaSort.TITLE_ROMAJI)
            vm.setQuery("火影")
            runCurrent()
            val initialGeneration = vm.uiState.value.searchGeneration
            assertTrue(vm.uiState.value.supplementStarted)
            assertEquals(listOf(20), vm.uiState.value.supplementalMedia.map { it.id })

            languages.value = UserTitleLanguage.ENGLISH
            runCurrent()

            assertTrue(vm.uiState.value.isLoading)
            assertEquals(initialGeneration + 1, vm.uiState.value.searchGeneration)
            assertTrue(vm.uiState.value.supplementalMedia.isEmpty())
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun generationResetClearsEverySupplementFieldAtomically() {
        val stale = SearchUiState(
            searchType = SearchType.ANIME,
            mediaSort = MediaSort.SEARCH_MATCH,
            isLoggedIn = false,
            page = 4,
            hasNextPage = false,
            isLoading = false,
            error = "old",
            supplementStarted = true,
            supplementLoading = true,
            supplementHasMore = true,
            supplementFailed = true,
            supplementRetryAfterMillis = 5_000,
            supplementDepthLimited = true,
            supplementNamelessCount = 3,
            supplementUsesPopularityFallback = true,
        )

        val reset = stale.restartForGeneration(9)

        assertEquals(9, reset.searchGeneration)
        assertEquals(1, reset.page)
        assertTrue(reset.hasNextPage)
        assertTrue(reset.isLoading)
        assertEquals(null, reset.error)
        assertTrue(reset.supplementalMedia.isEmpty())
        assertFalse(reset.supplementStarted)
        assertFalse(reset.supplementLoading)
        assertFalse(reset.supplementHasMore)
        assertFalse(reset.supplementFailed)
        assertEquals(null, reset.supplementRetryAfterMillis)
        assertFalse(reset.supplementDepthLimited)
        assertEquals(0, reset.supplementNamelessCount)
        assertFalse(reset.supplementUsesPopularityFallback)
    }

    @Test fun noLocalIdsStillDisclosesMappedSupplementPopularityFallback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        catalogSnapshot.value = LocalizationSnapshot.create(
            "v1",
            listOf(CatalogSource("test", "fixture", "https://example.org", "CC0", "a".repeat(64))),
            emptyList(),
            links = listOf(BangumiLink(MediaIdentity(MediaKind.ANIME, 20), 42, "test")),
        )
        val online = ChineseSearchRepository({ _, _, _, _ ->
            SubjectSearchResult.Success(SubjectSearchPage(0, 25, 1, listOf(SubjectSearchHit(42, "mapped", "无本地"))))
        })
        val sortAndIds = mutableListOf<Pair<MediaSort, List<Int>?>>()
        val vm = setupDetailed(online = online) { page, sort, ids ->
            sortAndIds += sort to ids
            val item = mockk<SearchMediaQuery.Medium> { every { id } returns 20 }
            flowOf(PagedResult.Success(listOf(item), page, false))
        }
        try {
            vm.setQuery("无本地")
            runCurrent()

            assertEquals(0, vm.uiState.value.localCandidateCount)
            assertFalse(vm.uiState.value.isLocalTitleSearch)
            assertTrue(vm.uiState.value.supplementStarted)
            assertTrue(vm.uiState.value.supplementUsesPopularityFallback)
            assertTrue(sortAndIds.any { (sort, ids) -> sort == MediaSort.POPULARITY_DESC && ids == listOf(20) })
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun mappedResultShowsFallbackWhileOriginalSupplementIsStillLoading() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        catalogSnapshot.value = LocalizationSnapshot.create(
            "v1",
            listOf(CatalogSource("test", "fixture", "https://example.org", "CC0", "a".repeat(64))),
            listOf(CatalogRecord(EntityKey(EntityKind.MEDIA, 10), "火影", emptyList(), "test", MediaKind.ANIME)),
            links = listOf(BangumiLink(MediaIdentity(MediaKind.ANIME, 20), 42, "test")),
        )
        val online = ChineseSearchRepository({ _, _, _, _ ->
            SubjectSearchResult.Success(SubjectSearchPage(0, 25, 1, listOf(SubjectSearchHit(42, "mapped", "火影"))))
        })
        val vm = setupDetailed(online = online) { page, _, ids ->
            if (ids == null) flow { awaitCancellation() }
            else {
                val item = mockk<SearchMediaQuery.Medium> { every { id } returns ids.first() }
                flowOf(PagedResult.Success(listOf(item), page, false))
            }
        }
        try {
            vm.setQuery("火影")
            repeat(10) { runCurrent() }

            assertEquals(listOf(10), vm.uiState.value.media.map { it.id })
            assertEquals(listOf(20), vm.uiState.value.supplementalMedia.map { it.id })
            assertTrue(vm.uiState.value.supplementUsesPopularityFallback)
            assertTrue(vm.uiState.value.supplementLoading)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun catalogActivationRestartsPinnedSessionAtFirstPageWithNewSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = CatalogSource("test", "fixture", "https://example.org", "CC0", "a".repeat(64))
        fun catalog(version: String, ids: List<Int>) = LocalizationSnapshot.create(
            version, listOf(source),
            ids.map { CatalogRecord(EntityKey(EntityKind.MEDIA, it), "火影$it", emptyList(), "test", MediaKind.ANIME) },
        )
        catalogSnapshot.value = catalog("v1", listOf(20))
        val requests = mutableListOf<Pair<Int, List<Int>?>>()
        val vm = setupDetailed { page, _, ids ->
            requests += page to ids
            val item = mockk<SearchMediaQuery.Medium> { every { id } returns (ids?.first() ?: 20) }
            flowOf(PagedResult.Success(listOf(item), page, false))
        }
        try {
            vm.setQuery("火影")
            runCurrent()
            val initialGeneration = vm.uiState.value.searchGeneration
            assertEquals(1, initialGeneration)
            assertEquals(listOf(1 to listOf(20)), requests)

            catalogSnapshot.value = catalog("v2", listOf(20, 1735))
            runCurrent()

            assertEquals(initialGeneration + 1, vm.uiState.value.searchGeneration)
            assertEquals(1, vm.uiState.value.page)
            assertEquals(listOf(1 to listOf(20), 1 to listOf(20, 1735)), requests)
            assertEquals(listOf(20), vm.uiState.value.media.map { it.id })
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun failedInitialCatalogLoadSettlesWithoutRestartLoop() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        catalogSnapshot.value = LocalizationSnapshot.EMPTY
        var requests = 0
        val vm = setup { page, _ ->
            requests++
            flowOf(PagedResult.Success(emptyList(), page, false))
        }
        try {
            vm.setQuery("火影")
            runCurrent()
            repeat(5) { runCurrent() }

            assertEquals(1, requests)
            assertEquals(1, vm.uiState.value.searchGeneration)
            assertFalse(vm.uiState.value.isLoading)
            assertFalse(vm.uiState.value.localPageFailed)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
