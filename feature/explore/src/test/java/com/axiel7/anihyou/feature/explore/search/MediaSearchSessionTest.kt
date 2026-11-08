package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.localization.CatalogRecord
import com.axiel7.anihyou.core.localization.CatalogSource
import com.axiel7.anihyou.core.localization.EntityKey
import com.axiel7.anihyou.core.localization.EntityKind
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.model.SearchType
import com.axiel7.anihyou.core.model.genre.GenresAndTagsForSearch
import com.axiel7.anihyou.core.network.type.MediaSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSearchSessionTest {
    private val source = CatalogSource("test", "fixture", "https://example.org", "CC0", "a".repeat(64))
    private fun catalog(version: String, ids: List<Int>) = LocalizationSnapshot.create(
        version, listOf(source), ids.map { id ->
            CatalogRecord(EntityKey(EntityKind.MEDIA, id), "\u706b\u5f71$id", emptyList(), "test", MediaKind.ANIME)
        }
    )
    private fun state() = SearchUiState(
        query = "\u706b\u5f71", searchType = SearchType.ANIME,
        mediaSort = MediaSort.SEARCH_MATCH, isLoggedIn = false, page = 1, hasNextPage = true,
    )

    @Test
    fun candidatesStayPinnedWithinOneCatalogVersion() {
        val sessions = MediaSearchSessions()
        val first = sessions.plan(state(), catalog("v1", listOf(20, 1735)))
        val next = sessions.plan(state().copy(page = 2), catalog("v1", listOf(20, 1735, 99)))
        assertSame(first, next)
        assertEquals(listOf(20, 1735), next.mediaIds)
        assertEquals("v1", next.catalogVersion)
        assertEquals(MediaSort.POPULARITY_DESC, next.sort)
        assertTrue(next.isLocalSearch)
        assertTrue(next.usesPopularityFallback)
    }

    @Test fun catalogVersionChangeReplacesPinnedSelection() {
        val sessions = MediaSearchSessions()
        val first = sessions.plan(state(), catalog("v1", listOf(20)))
        val next = sessions.plan(state(), catalog("v2", listOf(20, 1735)))
        assertFalse(first === next)
        assertEquals(listOf(20, 1735), next.mediaIds)
        assertEquals("v2", next.catalogVersion)
    }

    @Test
    fun changedFiltersAndSortReuseCandidatesAndPreserveRawFilterNames() {
        val sessions = MediaSearchSessions()
        val original = state()
        val first = sessions.plan(original, catalog("v1", listOf(20)))
        val changed = original.copy(mediaSort = MediaSort.TITLE_ROMAJI, genresAndTagsForSearch = GenresAndTagsForSearch(tagIn = setOf("Time Manipulation")))
        val plan = sessions.plan(changed, catalog("v1", listOf(20, 1735)))
        assertSame(first.mediaIds, plan.mediaIds)
        assertEquals(listOf(20), plan.mediaIds)
        assertEquals(setOf("Time Manipulation"), changed.genresAndTagsForSearch.tagIn)
        assertEquals("v1", plan.catalogVersion)
        assertEquals(MediaSort.TITLE_ROMAJI, plan.sort)
    }

    @Test
    fun replacingSelectionForSameKeyInvalidatesCachedPlan() {
        val sessions = MediaSearchSessions()
        val first = MediaSearchSessions.select(state(), catalog("v1", listOf(20)))
        sessions.commit(first)
        assertEquals(listOf(20), sessions.plan(state(), first).mediaIds)
        val replacement = MediaSearchSessions.select(state(), catalog("v2", listOf(1735)))
        sessions.commit(replacement)
        val result = sessions.plan(state(), replacement)
        assertSame(replacement.ids, result.mediaIds)
        assertEquals(listOf(1735), result.mediaIds)
        assertEquals("v2", result.catalogVersion)
    }

    @Test
    fun sameQueryDifferentMediaKindDoesNotReuseSelection() {
        val sessions = MediaSearchSessions()
        sessions.plan(state(), catalog("v1", listOf(20)))
        val manga = state().copy(searchType = SearchType.MANGA)
        val plan = sessions.plan(manga, catalog("v2", listOf(1735)))
        assertNull(plan.mediaIds)
        assertEquals("v2", plan.catalogVersion)
    }

    @Test
    fun filteredRelevanceDisclosesPopularityFallback() {
        val filtered = state().copy(genresAndTagsForSearch = GenresAndTagsForSearch(genreIn = setOf("Action")))
        val plan = MediaSearchSessions().plan(filtered, catalog("v1", listOf(20)))
        assertEquals(MediaSort.POPULARITY_DESC, plan.sort)
        assertTrue(plan.usesPopularityFallback)
    }

    @Test
    fun nativeAndUnmappedQueriesKeepUpstreamTextSearch() {
        val sessions = MediaSearchSessions()
        val native = sessions.plan(state().copy(query = "Naruto"), catalog("v1", listOf(20)))
        assertNull(native.mediaIds)
        assertEquals("Naruto", native.query)
        assertFalse(native.isLocalSearch)
        assertEquals(MediaSort.SEARCH_MATCH, native.sort)
        val missing = sessions.plan(state().copy(query = "\u672a\u77e5\u4f5c\u54c1"), catalog("v1", listOf(20)))
        assertNull(missing.mediaIds)
        assertEquals("\u672a\u77e5\u4f5c\u54c1", missing.query)
    }

    @Test
    fun declaredSortIsNotReplacedForCandidateSearch() {
        val plan = MediaSearchSessions().plan(
            state().copy(mediaSort = MediaSort.TITLE_ROMAJI), catalog("v1", listOf(20))
        )
        assertEquals(MediaSort.TITLE_ROMAJI, plan.sort)
        assertFalse(plan.usesPopularityFallback)
    }

    @Test
    fun resetStartsFreshGenerationWithoutDroppingCandidateTail() {
        val sessions = MediaSearchSessions()
        sessions.plan(state(), catalog("v1", listOf(20)))
        sessions.reset()
        val plan = sessions.plan(state(), catalog("v2", (1..501).toList()))
        assertEquals(501, plan.mediaIds!!.size)
        assertEquals(501, plan.totalCandidates)
        assertFalse(plan.isTruncated)
        assertEquals("v2", plan.catalogVersion)
    }
}
