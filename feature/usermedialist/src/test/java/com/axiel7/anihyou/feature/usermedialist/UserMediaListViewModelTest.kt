package com.axiel7.anihyou.feature.usermedialist

import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshots.Snapshot
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LocalizationCatalogRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.localization.CatalogRecord
import com.axiel7.anihyou.core.localization.CatalogSource
import com.axiel7.anihyou.core.localization.EntityKey
import com.axiel7.anihyou.core.localization.EntityKind
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.localization.SearchAlias
import com.axiel7.anihyou.core.model.ItemsPerRow
import com.axiel7.anihyou.core.model.ListStyle
import com.axiel7.anihyou.core.model.TranslatorApp
import com.axiel7.anihyou.core.model.media.exampleCommonMediaListEntry
import com.axiel7.anihyou.core.model.media.MediaFormatLocalizable
import com.axiel7.anihyou.core.model.media.plainName
import com.axiel7.anihyou.core.network.UserListCollectionQuery
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaListSort
import com.axiel7.anihyou.core.network.type.MediaListStatus
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.ScoreFormat
import com.axiel7.anihyou.core.network.type.UserTitleLanguage
import com.axiel7.anihyou.core.ui.common.navigation.Route
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserMediaListViewModelTest {
    private val source = CatalogSource("source", "1", "https://example.org", "test", "0".repeat(64))

    @Test
    fun nativeSearchPublishesWhileCatalogLoadIsStillBlocked() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val loadGate = CompletableDeferred<Unit>()
        var loadStarted = false
        var loadFinished = false
        val catalog = mockCatalog(LocalizationSnapshot.EMPTY) {
            loadStarted = true
            loadGate.await()
            loadFinished = true
        }
        val viewModel = createViewModel(
            networkLists = { listOf(list("Watching", entry(1, "Naruto"))) },
            catalog = catalog,
        )
        try {
            viewModel.setQuery("Naruto")
            advanceUntilIdle()
            awaitCondition { viewModel.uiState.value.entries.map { it.mediaId } == listOf(1) }
            assertTrue(loadStarted)
            assertFalse(loadFinished)
        } finally {
            loadGate.complete(Unit)
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun displayOffKeepsFuzzyFilteredChineseAndAliasSearchBeyond500() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val snapshot = localizedSnapshot(
            titles = (1..510).map { id -> id to "共同作品$id" },
            aliases = listOf(SearchAlias(510, listOf("来源别称"), source.id, MediaKind.ANIME)),
        )
        val viewModel = createViewModel(
            networkLists = {
                listOf(
                    list(
                        "Watching",
                        entry(510, "Original 510"),
                        entry(511, "共同作品 excluded", format = MediaFormat.MOVIE),
                    ),
                )
            },
            catalog = mockCatalog(snapshot),
            chineseTitleFlow = MutableStateFlow(false),
            fuzzySearch = true,
        )
        try {
            advanceUntilIdle()
            viewModel.setMediaFormat(MediaFormatLocalizable.TV)
            viewModel.setMediaStatus(MediaStatus.RELEASING)
            viewModel.setQuery("共同作品")
            advanceUntilIdle()
            awaitCondition { viewModel.uiState.value.entries.map { it.mediaId } == listOf(510) }

            viewModel.setQuery("来源别称")
            advanceUntilIdle()
            awaitCondition { viewModel.uiState.value.entries.map { it.mediaId } == listOf(510) }
        } finally {
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun titleSortUsesCatalogWhenUserPreferredTitleIsNull() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = createViewModel(
            networkLists = {
                listOf(
                    list(
                        "Watching",
                        entry(1, null),
                        entry(2, "Z"),
                    ),
                )
            },
            catalog = mockCatalog(localizedSnapshot(1 to "阿")),
        )
        try {
            advanceUntilIdle()
            awaitCondition { viewModel.uiState.value.entries.map { it.mediaId } == listOf(2, 1) }
        } finally {
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun progressUpdateKeepsCustomMembershipAndUpdatesStandardList() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val currentName = MediaListStatus.CURRENT.plainListName()
        val initial = entry(1, "Naruto", status = MediaListStatus.CURRENT)
        val viewModel = createViewModel(
            selectedList = currentName,
            networkLists = {
                listOf(
                    list(currentName, initial),
                    list("Favorites", initial, isCustom = true),
                )
            },
            catalog = mockCatalog(LocalizationSnapshot.EMPTY),
        )
        try {
            advanceUntilIdle()
            awaitCondition { viewModel.uiState.value.lists["Favorites"]?.isNotEmpty() == true }
            viewModel.onChangeList("Favorites")
            advanceUntilIdle()
            viewModel.selectItem(initial)

            val updated = initial.basicMediaListEntry.copy(progress = 12)
            viewModel.onUpdateListEntry(updated)

            val state = viewModel.uiState.value
            assertEquals(12, state.lists["Favorites"]?.single()?.basicMediaListEntry?.progress)
            assertEquals(12, state.lists[currentName]?.single()?.basicMediaListEntry?.progress)
        } finally {
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun statusUpdateMovesStandardMembershipAndKeepsCustomMembership() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val currentName = MediaListStatus.CURRENT.plainListName()
        val completedName = MediaListStatus.COMPLETED.plainListName()
        val initial = entry(1, "Naruto", status = MediaListStatus.CURRENT)
        val viewModel = createViewModel(
            networkLists = {
                listOf(
                    list(currentName, initial),
                    list(completedName),
                    list("Favorites", initial, isCustom = true),
                )
            },
            catalog = mockCatalog(LocalizationSnapshot.EMPTY),
        )
        try {
            advanceUntilIdle()
            awaitCondition { viewModel.uiState.value.lists["Favorites"]?.isNotEmpty() == true }
            viewModel.onChangeList("Favorites")
            advanceUntilIdle()
            viewModel.selectItem(initial)

            val completed = initial.basicMediaListEntry.copy(status = MediaListStatus.COMPLETED, score = null)
            viewModel.onUpdateListEntry(completed)

            val state = viewModel.uiState.value
            assertTrue(state.lists[currentName].orEmpty().none { it.mediaId == 1 })
            assertEquals(MediaListStatus.COMPLETED, state.lists[completedName]?.single()?.basicMediaListEntry?.status)
            assertEquals(MediaListStatus.COMPLETED, state.lists["Favorites"]?.single()?.basicMediaListEntry?.status)
            assertTrue(state.openSetScoreDialog)
        } finally {
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun catalogAndPreferenceChangesReorderLocallyWithoutNetworkFetch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val chineseTitles = MutableStateFlow(false)
        val snapshot = MutableStateFlow(LocalizationSnapshot.EMPTY)
        val networkCalls = AtomicInteger()
        val first = entry(1, "Apple")
        val second = entry(2, "Banana")
        val viewModel = createViewModel(
            networkLists = {
                networkCalls.incrementAndGet()
                listOf(list("Watching", first, second))
            },
            catalog = mockCatalog(snapshot),
            chineseTitleFlow = chineseTitles,
            sort = MediaListSort.MEDIA_TITLE_ROMAJI,
        )
        try {
            advanceUntilIdle()
            awaitCondition { viewModel.uiState.value.entries.map { it.mediaId } == listOf(1, 2) }

            snapshot.value = localizedSnapshot(1 to "香蕉", 2 to "苹果")
            advanceUntilIdle()
            awaitCondition { viewModel.uiState.value.entries.map { it.mediaId } == listOf(1, 2) }

            chineseTitles.value = true
            advanceUntilIdle()
            awaitCondition { viewModel.uiState.value.entries.map { it.mediaId } == listOf(2, 1) }
            assertEquals(1, networkCalls.get())
        } finally {
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleInFlightComputationDoesNotPublish() = runTest {
        val published = mutableListOf<String>()
        flow {
            emit("old")
            emit("new")
        }.publishLatest(
            compute = { value ->
                if (value == "old") withContext(NonCancellable) { delay(1_000) }
                value
            },
            publish = published::add,
        )
        assertEquals(listOf("new"), published)
    }

    private fun createViewModel(
        networkLists: () -> List<UserListCollectionQuery.List>,
        catalog: LocalizationCatalogRepository,
        chineseTitleFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
        selectedList: String? = MediaListStatus.CURRENT.plainListName(),
        sort: MediaListSort = MediaListSort.MEDIA_TITLE_ROMAJI,
        fuzzySearch: Boolean = false,
    ): UserMediaListViewModel {
        val defaultPreferences = mockk<DefaultPreferencesRepository>(relaxed = true) {
            every { userId } returns flowOf(1)
            every { titleLanguage } returns flowOf(UserTitleLanguage.ROMAJI)
            every { scoreFormat } returns flowOf(ScoreFormat.POINT_10)
            every { showLowPriority } returns flowOf(false)
            every { colorLowPriority } returns flowOf(0xFF7CB342.toInt())
            every { colorMediumPriority } returns flowOf(0xFFF4D03F.toInt())
            every { colorHighPriority } returns flowOf(0xFFD84315.toInt())
            every { useFuzzySearch } returns flowOf(fuzzySearch)
            every { translatorApp } returns flowOf(TranslatorApp.DEFAULT)
            every { scoreSteps } returns flowOf(1.0)
            every { animeLists } returns flowOf(emptyList())
            every { chineseTitles } returns chineseTitleFlow
        }
        val listPreferences = mockk<ListPreferencesRepository>(relaxed = true) {
            every { animeListSelected } returns flowOf(selectedList)
            every { animeListSort } returns flowOf(sort)
            every { useGeneralListStyle } returns flowOf(true)
            every { generalListStyle } returns flowOf(ListStyle.STANDARD)
            every { gridItemsPerRow } returns flowOf(ItemsPerRow.DEFAULT)
            coEvery { setAnimeListSelected(any()) } just Runs
            coEvery { setAnimeListSort(any()) } just Runs
        }
        val mediaListRepository = mockk<MediaListRepository>(relaxed = true) {
            every {
                getMediaListCollection(any(), any(), any(), any(), any(), any())
            } returns flowOf(PagedResult.Success(networkLists(), currentPage = 1, hasNextPage = false))
        }
        return UserMediaListViewModel(
            arguments = Route.UserMediaList(mediaType = MediaType.ANIME.rawValue),
            mediaListRepository = mediaListRepository,
            defaultPreferencesRepository = defaultPreferences,
            listPreferencesRepository = listPreferences,
            catalogRepository = catalog,
        )
    }

    private fun mockCatalog(
        snapshotValue: LocalizationSnapshot,
        onLoad: suspend () -> Unit = {},
    ): LocalizationCatalogRepository {
        val snapshots = MutableStateFlow(snapshotValue)
        return mockk<LocalizationCatalogRepository> {
            every { snapshot } returns snapshots
            coEvery { load() } coAnswers { onLoad() }
        }
    }

    private fun mockCatalog(
        snapshots: MutableStateFlow<LocalizationSnapshot>,
    ): LocalizationCatalogRepository = mockk<LocalizationCatalogRepository> {
        every { snapshot } returns snapshots
        coEvery { load() } just Runs
    }

    private fun localizedSnapshot(
        vararg titles: Pair<Int, String>,
        aliases: List<SearchAlias> = emptyList(),
    ): LocalizationSnapshot =
        LocalizationSnapshot.create(
            version = "test",
            sources = listOf(source),
            records = titles.map { (id, title) ->
                CatalogRecord(EntityKey(EntityKind.MEDIA, id), title, emptyList(), source.id, MediaKind.ANIME)
            },
            searchAliases = aliases,
        )

    private fun localizedSnapshot(
        titles: List<Pair<Int, String>>,
        aliases: List<SearchAlias> = emptyList(),
    ) = localizedSnapshot(*titles.toTypedArray(), aliases = aliases)

    private fun entry(
        id: Int,
        title: String?,
        status: MediaListStatus = MediaListStatus.CURRENT,
        format: MediaFormat = MediaFormat.TV,
    ): CommonMediaListEntry {
        val base = exampleCommonMediaListEntry
        val baseMedia = checkNotNull(base.media)
        val basicTitle = checkNotNull(baseMedia.basicMediaDetails.title)
        val commonTitle = checkNotNull(baseMedia.title)
        return base.copy(
            id = id,
            mediaId = id,
            basicMediaListEntry = base.basicMediaListEntry.copy(
                id = id,
                mediaId = id,
                status = status,
                score = 0.0,
                progress = 0,
            ),
            media = baseMedia.copy(
                id = id,
                basicMediaDetails = baseMedia.basicMediaDetails.copy(
                    id = id,
                    title = basicTitle.copy(userPreferred = title),
                    type = MediaType.ANIME,
                    format = format,
                ),
                title = commonTitle.copy(romaji = title, english = title, native = title),
                format = format,
            ),
        )
    }

    private fun list(
        name: String,
        vararg entries: CommonMediaListEntry,
        isCustom: Boolean = false,
    ) = UserListCollectionQuery.List(
        __typename = "MediaListCollection",
        name = name,
        isCustomList = isCustom,
        entries = entries.map { entry ->
            UserListCollectionQuery.Entry(
                __typename = "MediaList",
                id = entry.id,
                mediaId = entry.mediaId,
                commonMediaListEntry = entry,
            )
        },
    )

    private fun MediaListStatus.plainListName(): String =
        plainName(MediaType.ANIME)

    private suspend fun awaitCondition(condition: () -> Boolean) = withContext(Dispatchers.IO) {
        withTimeout(5_000) {
            while (!Snapshot.withMutableSnapshot(condition)) delay(10)
        }
    }
}
