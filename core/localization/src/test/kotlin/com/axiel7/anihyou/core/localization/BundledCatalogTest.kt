package com.axiel7.anihyou.core.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledCatalogTest {
    @Test
    fun shippedCatalogLoadsAndRetainsDistinctSequelCandidates() {
        val text = requireNotNull(javaClass.getResourceAsStream("/localization/catalog.json"))
            .bufferedReader().use { it.readText() }
        val snapshot = LocalizationCatalog.decode(text)
        val candidates = snapshot.searchMedia("\u706b\u5f71\u5fcd\u8005", MediaKind.ANIME)
        assertTrue(candidates.ids.contains(20))
        assertTrue(candidates.ids.contains(1735))
        assertEquals("Naruto", snapshot.displayName(EntityKey(EntityKind.MEDIA, 20), "Naruto", false))
        // A shared source ID is excluded instead of selecting an arbitrary edition.
        assertEquals("One Piece", snapshot.displayName(EntityKey(EntityKind.MEDIA, 21), "One Piece", true))
        assertTrue(snapshot.searchMedia("\u706b\u5f71\u5fcd\u8005", MediaKind.MANGA).ids.none { it == 20 || it == 1735 })
    }

    @Test
    fun shippedCatalogResolvesOnlyTheCorrectPeopleNamespaceAndPreservesTagKeys() {
        val snapshot = LocalizationCatalog.decode(
            requireNotNull(javaClass.getResourceAsStream("/localization/catalog.json"))
                .bufferedReader().use { it.readText() }
        )
        assertEquals("\u5bab\u5d0e\u9a8f", snapshot.displayName(EntityKey(EntityKind.STAFF, 96870), "Hayao Miyazaki", true))
        assertEquals("raw media", snapshot.displayName(EntityKey(EntityKind.MEDIA, 96870), "raw media", true))
        assertEquals("\u7eeb\u6ce2\u96f6", snapshot.displayName(EntityKey(EntityKind.CHARACTER, 86), "Rei", true))
        assertEquals("Rei", snapshot.displayName(EntityKey(EntityKind.CHARACTER, 86), "Rei", false))
        assertEquals("Action", snapshot.tagName("Action", false))
        assertEquals("Unknown Future Tag", snapshot.tagName("Unknown Future Tag", true))
        assertTrue(snapshot.sources.any { it.id == "wikidata-p11227" && it.license == "CC0-1.0" })
    }

    @Test
    fun bundledLinksUseExplicitAnimePairsAndExcludeSharedEditions() {
        val snapshot = LocalizationCatalog.decode(
            requireNotNull(javaClass.getResourceAsStream("/localization/catalog.json"))
                .bufferedReader().use { it.readText() }
        )
        assertEquals(3425, snapshot.mediaLink(MediaIdentity(MediaKind.ANIME, 20))?.subjectId)
        assertEquals(2782, snapshot.mediaLink(MediaIdentity(MediaKind.ANIME, 1735))?.subjectId)
        assertEquals(null, snapshot.mediaLink(MediaIdentity(MediaKind.MANGA, 20)))
        assertEquals(null, snapshot.mediaLink(MediaIdentity(MediaKind.ANIME, 21)))
    }
}
