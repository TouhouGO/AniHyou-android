package com.axiel7.anihyou.core.localization

import org.junit.Assert.*
import org.junit.Test

class SearchAliasesTest {
    private val source = CatalogSource("s", "r", "https://example.org", "MIT", "a".repeat(64))
    private fun record(id: Int, kind: MediaKind) = CatalogRecord(EntityKey(EntityKind.MEDIA, id), "display", emptyList(), "s", kind)

    @Test fun aliasesMergeAndUnknownTypeNarrowsToEvidence() {
        val snapshot = LocalizationSnapshot.create("v", listOf(source), listOf(record(1, MediaKind.ANIME)),
            links = listOf(BangumiLink(MediaIdentity(MediaKind.MANGA, 2), 3, "s")),
            searchAliases = listOf(SearchAlias(1, listOf("别名", "别名"), "s"), SearchAlias(2, listOf("别名"), "s"), SearchAlias(3, listOf("别名"), "s")))
        assertEquals(listOf(1, 3), snapshot.searchMedia("别名", MediaKind.ANIME).ids)
        assertEquals(listOf(2, 3), snapshot.searchMedia("别名", MediaKind.MANGA).ids)
        assertEquals("display", snapshot.displayName(EntityKey(EntityKind.MEDIA, 1), "raw", true))
    }

    @Test fun immutableAndInvalidAliasesAreRejected() {
        val names = mutableListOf("first")
        val alias = SearchAlias(1, names, "s", MediaKind.ANIME)
        names.add("second")
        assertEquals(listOf("first"), alias.names)
        assertThrows(UnsupportedOperationException::class.java) { (alias.names as MutableList).add("third") }
        assertThrows(IllegalArgumentException::class.java) { LocalizationSnapshot.create("v", listOf(source), listOf(record(1, MediaKind.MANGA)), searchAliases = listOf(alias)) }
        assertThrows(IllegalArgumentException::class.java) { LocalizationSnapshot.create("v", listOf(source), emptyList(), searchAliases = listOf(alias, alias)) }
        assertThrows(IllegalArgumentException::class.java) { LocalizationSnapshot.create("v", listOf(source), emptyList(), searchAliases = listOf(SearchAlias(1, listOf("x"), "missing"))) }
        assertThrows(IllegalArgumentException::class.java) { SearchAlias(1, listOf(" "), "s") }
    }

    @Test fun quotedIdsAreRejectedAndAbsentAliasesWork() {
        val base = """{"schemaVersion":1,"version":"v","sources":[{"id":"s","revision":"r","url":"https://example.org","license":"MIT","sha256":"${"a".repeat(64)}"}],"records":[]}"""
        LocalizationCatalog.decode(base)
        val invalid = base.dropLast(1) + """, "searchAliases":[{"aniListId":"1","names":["name"],"sourceId":"s"}]}"""
        assertThrows(IllegalArgumentException::class.java) { LocalizationCatalog.decode(invalid) }
    }
}
