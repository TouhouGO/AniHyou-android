package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.type.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BangumiSubjectMatcherTest {

    @Test
    fun testTitleNormalization() {
        assertEquals("clannad", BangumiSubjectMatcher.normalizeTitle("CLANNAD"))
        assertEquals("clannadafterstory", BangumiSubjectMatcher.normalizeTitle("CLANNAD 〜AFTER STORY〜"))
        assertEquals("clannadafterstory", BangumiSubjectMatcher.normalizeTitle("CLANNAD: After Story"))
        assertEquals("进击的巨人", BangumiSubjectMatcher.normalizeTitle("进击的巨人！"))
        assertEquals("testtitle", BangumiSubjectMatcher.normalizeTitle("  Test ・ Title !?  "))
    }

    @Test
    fun testNullOrBlankTitleReturnsNull() {
        assertNull(
            BangumiSubjectMatcher.findUnique(
                queryTitle = "",
                mediaType = MediaType.ANIME,
                releaseYear = 2023,
                candidates = listOf(
                    BangumiSubjectCandidate(id = 1, type = 2, name = "Test", nameCn = "测试", airDate = "2023-01-01")
                )
            )
        )
        assertNull(
            BangumiSubjectMatcher.findUnique(
                queryTitle = "   ",
                mediaType = MediaType.ANIME,
                releaseYear = 2023,
                candidates = listOf(
                    BangumiSubjectCandidate(id = 1, type = 2, name = "Test", nameCn = "测试", airDate = "2023-01-01")
                )
            )
        )
        assertNull(
            BangumiSubjectMatcher.findUnique(
                queryTitle = "!!!",
                mediaType = MediaType.ANIME,
                releaseYear = 2023,
                candidates = listOf(
                    BangumiSubjectCandidate(id = 1, type = 2, name = "Test", nameCn = "测试", airDate = "2023-01-01")
                )
            )
        )
    }

    @Test
    fun testNullReleaseYearRejectsMatch() {
        val candidates = listOf(
            BangumiSubjectCandidate(id = 101, type = 2, name = "Sousou no Frieren", nameCn = "葬送的芙莉莲", airDate = "2023-09-29")
        )
        // Without release year, search matching must be rejected to prevent wrong season/sequel matches
        val result = BangumiSubjectMatcher.findUnique(
            queryTitle = "Sousou no Frieren",
            mediaType = MediaType.ANIME,
            releaseYear = null,
            candidates = candidates
        )
        assertNull(result)
    }

    @Test
    fun testWrongCandidateTypeIgnored() {
        val candidates = listOf(
            // Type 1 is Book/Manga, but we are querying Anime
            BangumiSubjectCandidate(id = 201, type = 1, name = "Sousou no Frieren", nameCn = "葬送的芙莉莲", airDate = "2020-04-28")
        )
        val result = BangumiSubjectMatcher.findUnique(
            queryTitle = "Sousou no Frieren",
            mediaType = MediaType.ANIME,
            releaseYear = 2020,
            candidates = candidates
        )
        assertNull(result)
    }

    @Test
    fun testWrongCandidateYearIgnored() {
        val candidates = listOf(
            // Air year 2024 does not match query year 2023
            BangumiSubjectCandidate(id = 301, type = 2, name = "Sousou no Frieren", nameCn = "葬送的芙莉莲", airDate = "2024-01-05")
        )
        val result = BangumiSubjectMatcher.findUnique(
            queryTitle = "Sousou no Frieren",
            mediaType = MediaType.ANIME,
            releaseYear = 2023,
            candidates = candidates
        )
        assertNull(result)
    }

    @Test
    fun testMissingOrMalformedAirDateIgnored() {
        val candidates = listOf(
            BangumiSubjectCandidate(id = 401, type = 2, name = "Sousou no Frieren", nameCn = "葬送的芙莉莲", airDate = null),
            BangumiSubjectCandidate(id = 402, type = 2, name = "Sousou no Frieren", nameCn = "葬送的芙莉莲", airDate = "TBA"),
            BangumiSubjectCandidate(id = 403, type = 2, name = "Sousou no Frieren", nameCn = "葬送的芙莉莲", airDate = "")
        )
        val result = BangumiSubjectMatcher.findUnique(
            queryTitle = "Sousou no Frieren",
            mediaType = MediaType.ANIME,
            releaseYear = 2023,
            candidates = candidates
        )
        assertNull(result)
    }

    @Test
    fun testMatchesExactNameOrNameCn() {
        val candidates = listOf(
            BangumiSubjectCandidate(id = 501, type = 2, name = "Sousou no Frieren", nameCn = "葬送的芙莉莲", airDate = "2023-09-29")
        )

        // Matching English/Romaji name
        val matchByRomaji = BangumiSubjectMatcher.findUnique(
            queryTitle = "Sousou no Frieren",
            mediaType = MediaType.ANIME,
            releaseYear = 2023,
            candidates = candidates
        )
        assertEquals(501, matchByRomaji)

        // Matching Chinese name_cn
        val matchByCn = BangumiSubjectMatcher.findUnique(
            queryTitle = "葬送的芙莉莲",
            mediaType = MediaType.ANIME,
            releaseYear = 2023,
            candidates = candidates
        )
        assertEquals(501, matchByCn)
    }

    @Test
    fun testDeduplicatesIdenticalBangumiId() {
        // If the API returns multiple candidate entries that map to the exact same Bangumi ID
        val candidates = listOf(
            BangumiSubjectCandidate(id = 601, type = 2, name = "Sousou no Frieren", nameCn = "葬送的芙莉莲", airDate = "2023-09-29"),
            BangumiSubjectCandidate(id = 601, type = 2, name = "Sousou no Frieren: Special", nameCn = "葬送的芙莉莲", airDate = "2023-10-01")
        )

        val result = BangumiSubjectMatcher.findUnique(
            queryTitle = "葬送的芙莉莲",
            mediaType = MediaType.ANIME,
            releaseYear = 2023,
            candidates = candidates
        )
        assertEquals(601, result)
    }

    @Test
    fun testAmbiguousMultipleDifferentMatchingIdsReturnsNull() {
        // If two different subjects match (e.g. TV version vs Movie recap with same title and year)
        val candidates = listOf(
            BangumiSubjectCandidate(id = 701, type = 2, name = "Test Title", nameCn = "测试标题", airDate = "2023-04-01"),
            BangumiSubjectCandidate(id = 702, type = 2, name = "Test Title", nameCn = "测试标题", airDate = "2023-09-01")
        )

        val result = BangumiSubjectMatcher.findUnique(
            queryTitle = "Test Title",
            mediaType = MediaType.ANIME,
            releaseYear = 2023,
            candidates = candidates
        )
        assertNull(result)
    }

    @Test
    fun testMangaMatching() {
        val candidates = listOf(
            BangumiSubjectCandidate(id = 801, type = 1, name = "Chainsaw Man", nameCn = "电锯人", airDate = "2018-12-03"),
            BangumiSubjectCandidate(id = 802, type = 2, name = "Chainsaw Man", nameCn = "电锯人", airDate = "2022-10-11")
        )

        val mangaResult = BangumiSubjectMatcher.findUnique(
            queryTitle = "Chainsaw Man",
            mediaType = MediaType.MANGA,
            releaseYear = 2018,
            candidates = candidates
        )
        assertEquals(801, mangaResult)

        val animeResult = BangumiSubjectMatcher.findUnique(
            queryTitle = "Chainsaw Man",
            mediaType = MediaType.ANIME,
            releaseYear = 2022,
            candidates = candidates
        )
        assertEquals(802, animeResult)
    }
}
