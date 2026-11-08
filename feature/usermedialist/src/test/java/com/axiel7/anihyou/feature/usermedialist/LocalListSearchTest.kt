package com.axiel7.anihyou.feature.usermedialist

import com.axiel7.anihyou.core.localization.*
import org.junit.Assert.*
import org.junit.Test

class LocalListSearchTest {
    private val source = CatalogSource("source", "1", "https://example.org", "test", "0".repeat(64))
    private val catalog = LocalizationSnapshot.create(
        "test", listOf(source),
        (1..510).map { id ->
            CatalogRecord(
                EntityKey(EntityKind.MEDIA, id),
                "共同作品$id",
                listOf("别名$id"),
                "source",
                MediaKind.ANIME,
            )
        },
        searchAliases = listOf(SearchAlias(510, listOf("来源别称"), "source", MediaKind.ANIME)),
    )

    @Test fun candidatesIncludeTailAliasesAndRespectMediaKind() {
        assertTrue(510 in localizedListCandidates(catalog, "共同作品", MediaKind.ANIME))
        assertEquals(setOf(510), localizedListCandidates(catalog, "来源别称", MediaKind.ANIME))
        assertEquals(setOf(510), localizedListCandidates(catalog, "别名５１０", MediaKind.ANIME))
        assertTrue(localizedListCandidates(catalog, "共同作品", MediaKind.MANGA).isEmpty())
    }

    @Test fun displayedTitleAndDirectionUseStableIdTieBreak() {
        data class Item(val id: Int, val raw: String)
        val items = listOf(Item(510, "Aaa"), Item(999, "Alpha"), Item(998, "Alpha"))
        fun sorted(enabled: Boolean, descending: Boolean) = items.sortedWith(
            listTitleComparator(descending, { localizedListTitle(catalog, it.id, it.raw, enabled) }, { it.id })
        ).map { it.id }
        assertEquals(listOf(998, 999, 510), sorted(true, false))
        assertEquals(listOf(510, 998, 999), sorted(true, true))
        assertEquals(listOf(510, 998, 999), sorted(false, false))
        assertEquals(listOf(998, 999, 510), sorted(false, true))
        assertEquals("fallback", localizedListTitle(catalog, 999, "fallback", true))
    }

    @Test fun nullRawTitleUsesCatalogOnlyWhenDisplayingIsEnabled() {
        assertEquals("共同作品510", localizedListTitle(catalog, 510, null, true))
        assertEquals(null, localizedListTitle(catalog, 510, null, false))
        assertEquals(null, localizedListTitle(catalog, 999, null, true))
    }

    @Test fun workerReceivesAStableCopyOfMutableListInputs() {
        val source = mutableListOf(1, 2)
        val snapshot = immutableListInputs(mapOf("list" to source))
        source.add(3)
        assertEquals(listOf(1, 2), snapshot["list"])
    }

    @Test fun generationGuardRejectsResultsFromAnOlderQuery() {
        val guard = SearchGenerationGuard()
        val first = guard.next()
        val second = guard.next()
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }
}
