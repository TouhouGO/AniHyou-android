package com.axiel7.anihyou.core.localization

import org.junit.Assert.*
import org.junit.Test

class CatalogLinksTest {
    private val source = CatalogSource("s", "r", "https://example.org", "CC0", "a".repeat(64))
    private val base = """{"schemaVersion":1,"version":"v1","sources":[{"id":"s","revision":"r","url":"https://example.org","license":"CC0","sha256":"${"a".repeat(64)}"}],"records":[],"tags":[{"rawName":"A","displayName":"甲","sourceId":"s"}]}"""
    private val anime = MediaIdentity(MediaKind.ANIME, 1)
    private val manga = MediaIdentity(MediaKind.MANGA, 1)

    private fun withLinks(links: String) = base.dropLast(1) + ",\"links\":$links}"
    private fun link(kind: String = "ANIME", aniId: String = "1", subjectId: String = "2", sourceId: String = "s") =
        """{"mediaKind":"$kind","aniListId":$aniId,"bangumiSubjectId":$subjectId,"sourceId":"$sourceId"}"""

    @Test fun oldCatalogAndTagsRemainSupported() {
        val snapshot = LocalizationCatalog.decode(base)
        assertEquals("甲", snapshot.tagName("A", true))
        assertNull(snapshot.mediaLink(anime))
    }

    @Test fun typedLinksAreIndependentOfTitlesAndDefensivelyCopied() {
        val links = mutableListOf(BangumiLink(anime, 2, "s"), BangumiLink(manga, 2, "s"))
        val snapshot = LocalizationSnapshot.create("v1", listOf(source), emptyList(), links = links)
        links.clear()
        repeat(2) {
            assertEquals(BangumiLink(anime, 2, "s"), snapshot.mediaLink(anime))
            assertEquals(BangumiLink(manga, 2, "s"), snapshot.mediaLink(manga))
            assertEquals(anime, snapshot.mediaForBangumi(MediaKind.ANIME, 2))
            assertEquals(manga, snapshot.mediaForBangumi(MediaKind.MANGA, 2))
            assertNull(snapshot.mediaForBangumi(MediaKind.ANIME, 99))
            assertNull(snapshot.mediaLink(MediaIdentity(MediaKind.ANIME, 2)))
        }
        val decoded = LocalizationCatalog.decode(withLinks("[${link()},${link(kind = "MANGA")} ]"))
        assertEquals(BangumiLink(anime, 2, "s"), decoded.mediaLink(anime))
        assertEquals(BangumiLink(manga, 2, "s"), decoded.mediaLink(manga))
    }

    @Test fun malformedLinksAndConflictsAreRejected() {
        val invalid = listOf(
            "null", "{}", "[null]", "[${link(aniId = "\"1\"")}]",
            "[${link(subjectId = "\"2\"")}]", "[${link(aniId = "0")}]",
            "[${link(subjectId = "0")}]", "[${link(kind = "OTHER")}]",
            "[${link(sourceId = "missing")}]", "[${link()},${link(subjectId = "3")}]",
            "[${link()},${link(aniId = "3")}]",
            "[${link().replace("\"sourceId\":\"s\"", "\"sourceId\":4")}]",
            "[${link().replace("\"mediaKind\":\"ANIME\"", "\"mediaKind\":\"ANIME\",\"mediaKind\":\"MANGA\"")}]"
        )
        invalid.forEach {
            try {
                LocalizationCatalog.decode(withLinks(it))
                fail("invalid links: $it")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
