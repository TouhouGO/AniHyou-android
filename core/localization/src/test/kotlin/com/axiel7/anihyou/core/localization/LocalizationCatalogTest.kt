package com.axiel7.anihyou.core.localization

import org.junit.Assert.*
import org.junit.Test

class LocalizationCatalogTest {
    private val source = CatalogSource("source", "revision", "https://example.org", "CC0", "a".repeat(64))
    private fun record(id: Int, name: String, aliases: List<String> = emptyList(), kind: MediaKind = MediaKind.ANIME) =
        CatalogRecord(EntityKey(EntityKind.MEDIA, id), name, aliases, "source", kind)
    private fun snapshot(vararg records: CatalogRecord) = LocalizationSnapshot.create("v1", listOf(source), records.toList())

    @Test fun identityNamespacesAndFallbackAreIndependent() {
        val catalog = snapshot(record(7, "动画"), CatalogRecord(EntityKey(EntityKind.CHARACTER, 7), "角色", emptyList(), "source"))
        assertEquals("动画", catalog.displayName(EntityKey(EntityKind.MEDIA, 7), "raw", true))
        assertEquals("角色", catalog.displayName(EntityKey(EntityKind.CHARACTER, 7), "raw", true))
        assertEquals("raw", catalog.displayName(EntityKey(EntityKind.STAFF, 7), "raw", true))
        assertEquals(" raw ", catalog.displayName(EntityKey(EntityKind.MEDIA, 7), " raw ", false))
    }

    @Test fun snapshotDefensivelyCopiesInputsAndLookupsAreStable() {
        val aliases = mutableListOf("别名")
        val records = mutableListOf(record(1, "原名", aliases))
        val sources = mutableListOf(source)
        val catalog = LocalizationSnapshot.create("v1", sources, records)
        aliases[0] = "改变"; records.clear(); sources.clear()
        repeat(2) {
            assertEquals("原名", catalog.displayName(EntityKey(EntityKind.MEDIA, 1), "raw", true))
            assertEquals(listOf(1), catalog.searchMedia("别名", MediaKind.ANIME).ids)
            assertTrue(catalog.searchMedia("改变", MediaKind.ANIME).ids.isEmpty())
        }
    }

    @Test fun searchRanksExactPrefixAndSubstringWithStableIdTies() {
        val catalog = snapshot(record(8, "新星之旅"), record(3, "星"), record(9, "星的续篇"), record(2, "星", listOf("星")), record(5, "星", kind = MediaKind.MANGA))
        assertEquals(listOf(2, 3, 9, 8), catalog.searchMedia("星", MediaKind.ANIME).ids)
        assertEquals(listOf(5), catalog.searchMedia("星", MediaKind.MANGA).ids)
        assertEquals(listOf(2, 3, 9, 8), catalog.searchMedia("星", MediaKind.ANIME).ids)
    }

    @Test fun normalizationHandlesPunctuationAndBlankQueries() {
        val catalog = snapshot(record(1, "Re:Zero - 从零开始"))
        assertEquals(listOf(1), catalog.searchMedia("re zero 从零", MediaKind.ANIME).ids)
        assertTrue(catalog.searchMedia("  -- ", MediaKind.ANIME).ids.isEmpty())
        assertTrue(catalog.searchMedia("", MediaKind.ANIME).ids.isEmpty())
    }

    @Test fun overflowReportsFullDistinctCountAndVersion() {
        val catalog = snapshot(*(1..4).map { record(it, "作品$it", listOf("作品")) }.toTypedArray())
        val result = catalog.searchMedia("作品", MediaKind.ANIME, 2)
        assertEquals(listOf(1, 2), result.ids)
        assertEquals(4, result.totalMatches)
        assertTrue(result.isTruncated)
        assertEquals("v1", result.catalogVersion)
    }

    @Test fun defaultBudgetTruncatesAndRejectsOversizedLimit() {
        val catalog = snapshot(*(1..501).map { record(it, "作品$it", listOf("作品")) }.toTypedArray())
        val result = catalog.searchMedia("作品", MediaKind.ANIME)
        assertEquals((1..500).toList(), result.ids)
        assertEquals(501, result.totalMatches)
        assertTrue(result.isTruncated)
        try {
            catalog.searchMedia("作品", MediaKind.ANIME, 501)
            fail("oversized candidate budget must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun numericFieldsMustBeJsonNumbers() {
        val valid = """{"schemaVersion":1,"version":"v1","sources":[{"id":"s","revision":"r","url":"https://example.org","license":"CC0","sha256":"${"a".repeat(64)}"}],"records":[{"key":{"kind":"MEDIA","id":1},"displayName":"中文","aliases":[],"sourceId":"s","mediaKind":"ANIME"}]}"""
        for (invalid in listOf(
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            valid.replace("\"id\":1", "\"id\":\"1\"")
        )) {
            try { LocalizationCatalog.decode(invalid); fail("quoted number must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun strictDecodeAndValidationRejectMalformedOrConflictingData() {
        val valid = """{"schemaVersion":1,"version":"v1","sources":[{"id":"s","revision":"r","url":"https://example.org","license":"CC0","sha256":"${"a".repeat(64)}"}],"records":[{"key":{"kind":"MEDIA","id":1,"platform":"ANILIST"},"displayName":"中文","aliases":["Chinese"],"sourceId":"s","mediaKind":"ANIME"}]}"""
        val decoded = LocalizationCatalog.decode(valid)
        assertEquals("中文", decoded.displayName(EntityKey(EntityKind.MEDIA, 1), "raw", true))
        assertEquals(1, decoded.recordCount)
        listOf(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"), valid.replace("\"id\":1", "\"id\":0"), valid.replace("\"sourceId\":\"s\"", "\"sourceId\":\"missing\""), valid.replace("\"version\":\"v1\"", "\"version\":\"v1\",\"unknown\":1"), "{}").forEach { text ->
            try { LocalizationCatalog.decode(text); fail("expected invalid catalog") } catch (_: IllegalArgumentException) { }
        }
        try { snapshot(record(1, "A"), record(1, "B")); fail("duplicate identity") } catch (_: IllegalArgumentException) { }
    }
    @Test fun tagsAndSourcesAreRetainedAndValidated() {
        val sources = mutableListOf(source)
        val tags = mutableListOf(CatalogTag("Action", "动作", "source"), CatalogTag("Adventure", "动作", "source"))
        val catalog = LocalizationSnapshot.create("v1", sources, emptyList(), tags)
        sources.clear(); tags.clear()
        assertEquals(source, catalog.sources.single())
        assertEquals("动作", catalog.tagName("Action", true))
        assertEquals("动作", catalog.tagName("Adventure", true))
        assertEquals("action", catalog.tagName("action", true))
        assertEquals("Action", catalog.tagName("Action", false))
        try { (catalog.sources as MutableList).clear(); fail("sources must be immutable") } catch (_: UnsupportedOperationException) { }
        for (bad in listOf(listOf(CatalogTag("A", "甲", "missing")), listOf(CatalogTag("A", "甲", "source"), CatalogTag("A", "乙", "source")))) {
            try { LocalizationSnapshot.create("v1", listOf(source), emptyList(), bad); fail("invalid tags") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun decodeOptionalTagsAndStrictTypes() {
        val base = """{"schemaVersion":1,"version":"v1","sources":[{"id":"source","revision":"r","url":"https://example.org","license":"CC0","sha256":"${"a".repeat(64)}"}],"records":[]}"""
        assertEquals("A", LocalizationCatalog.decode(base).tagName("A", true))
        val withTags = base.dropLast(1) + """, "tags":[{"rawName":"A","displayName":"甲","sourceId":"source"}]}"""
        val decoded = LocalizationCatalog.decode(withTags)
        assertEquals("甲", decoded.tagName("A", true))
        assertEquals("source", decoded.sources.single().id)
        for (bad in listOf(withTags.replace(""""rawName":"A"""", """"rawName":4"""), withTags.replace(""""sourceId":"source"""", """"sourceId":"missing""""), withTags.replace(""""displayName":"甲"""", """"displayName":" """"))) {
            try { LocalizationCatalog.decode(bad); fail("invalid tag") } catch (_: IllegalArgumentException) { }
        }
    }

}
