package com.axiel7.anihyou.core.localization

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream

class SearchNormalizationTest {
    @Test fun bundledMappingsAreCoveredByStableHanRanges() {
        DataInputStream(requireNotNull(javaClass.getResourceAsStream("/localization/opencc/ts-characters.bin"))).use { input ->
            repeat(input.readInt()) {
                val source = input.readInt()
                val target = input.readInt()
                assertTrue("source U+${source.toString(16)}", isStableIdeograph(source))
                assertTrue("target U+${target.toString(16)}", isStableIdeograph(target))
            }
        }
    }

    @Test fun foldsCharactersAndKeepsCodePoints() {
        assertEquals("青春猪头少年", SearchTextNormalizer.normalize("青春豬頭少年"))
        assertEquals("abc123猪头", SearchTextNormalizer.normalize("ＡＢＣ-１２３！豬頭"))
        assertEquals("干干发发", SearchTextNormalizer.normalize("乾干髮發"))
        assertEquals("𫝈", SearchTextNormalizer.normalize("㑮"))
        assertTrue(SearchTextNormalizer.containsHan("𠀀"))
        assertTrue(SearchTextNormalizer.containsHan("㑮"))
        assertTrue(SearchTextNormalizer.containsHan("〡"))
        assertTrue(SearchTextNormalizer.containsHan("〸"))
        assertTrue(SearchTextNormalizer.containsHan(String(Character.toChars(0x16FE2))))
        assertTrue(SearchTextNormalizer.containsHan(String(Character.toChars(0x16FE3))))
        assertTrue(SearchTextNormalizer.containsHan(String(Character.toChars(0x16FF0))))
        assertTrue(SearchTextNormalizer.containsHan(String(Character.toChars(0x16FF1))))
        assertEquals("ab", SearchTextNormalizer.normalize("a〡𖿢b"))
        assertFalse(SearchTextNormalizer.containsHan("abc 123 😀"))
    }

    @Test fun findsBothDirectionsWithoutChangingDisplay() {
        val source = CatalogSource("s", "r", "https://example.org", "CC0", "a".repeat(64))
        val traditional = CatalogRecord(EntityKey(EntityKind.MEDIA, 1), "豬頭", emptyList(), "s", MediaKind.ANIME)
        val simplified = CatalogRecord(EntityKey(EntityKind.MEDIA, 2), "龙门", emptyList(), "s", MediaKind.ANIME)
        val snapshot = LocalizationSnapshot.create("v", listOf(source), listOf(traditional, simplified))
        assertEquals(listOf(1), snapshot.searchMedia("猪头", MediaKind.ANIME).ids)
        assertEquals(listOf(2), snapshot.searchMedia("龍門", MediaKind.ANIME).ids)
        assertEquals("豬頭", snapshot.displayName(traditional.key, "original", true))
        assertEquals("龙门", snapshot.displayName(simplified.key, "original", true))
    }

    @Test fun supplementaryTargetWorksWhenPlatformDoesNotRecognizeIt() {
        val target = String(Character.toChars(0x30B38))
        val oldPlatform: (Int) -> Boolean = { it != 0x30B38 && Character.isLetterOrDigit(it) }
        assertEquals(target, SearchTextNormalizer.normalize("繐", oldPlatform))
        assertEquals(target, SearchTextNormalizer.normalize(target, oldPlatform))
        assertEquals(target, SearchTextNormalizer.normalize(SearchTextNormalizer.normalize("繐", oldPlatform), oldPlatform))
        assertTrue(SearchTextNormalizer.containsHan(target))
        assertFalse(isHanCodePoint('A'.code))
        assertFalse(isHanCodePoint(0x1F600))

        val source = CatalogSource("s", "r", "https://example.org", "CC0", "a".repeat(64))
        val a = CatalogRecord(EntityKey(EntityKind.MEDIA, 1), "繐", emptyList(), "s", MediaKind.ANIME)
        val b = CatalogRecord(EntityKey(EntityKind.MEDIA, 2), target, emptyList(), "s", MediaKind.ANIME)
        val snapshot = LocalizationSnapshot.create("v", listOf(source), listOf(a, b))
        assertEquals(listOf(1, 2), snapshot.searchMedia(target, MediaKind.ANIME).ids)
        assertEquals(listOf(1, 2), snapshot.searchMedia("繐", MediaKind.ANIME).ids)
        assertEquals("繐", snapshot.displayName(a.key, "original", true))
        assertEquals(target, snapshot.displayName(b.key, "original", true))
    }
}
