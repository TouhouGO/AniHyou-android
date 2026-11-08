package com.axiel7.anihyou.core.localization

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRecallCorpusTest {
    @Test
    fun aliasesBroadNamesAndSequelsKeepSourceBackedCandidates() {
        val catalog = LocalizationCatalog.decode(
            requireNotNull(javaClass.getResourceAsStream("/localization/catalog.json"))
                .bufferedReader().use { it.readText() }
        )
        val corpus = listOf(
            "\u706b\u5f71\u5fcd\u8005" to setOf(20, 1735),
            "\u540d\u4fa6\u63a2\u67ef\u5357" to setOf(235, 779),
            "\u9032\u64ca\u7684\u5de8\u4eba" to setOf(16498, 18397),
            "\u6211\u63a8\u7684\u5b69\u5b50" to setOf(150672),
            "\u5b64\u72ec\u6447\u6eda" to setOf(130003),
            "\u7535\u952f\u4eba" to setOf(127230, 171627),
            "\u94fe\u952f\u4eba" to setOf(127230, 171627),
            "\u91cd\u542f" to setOf(97660),
            "\u54b2\u826f" to setOf(97660),
        )
        for ((query, required) in corpus) {
            val actual = catalog.searchMedia(query, MediaKind.ANIME)
            assertTrue("Source-backed candidates missing for $query", actual.ids.containsAll(required))
        }
        for (query in listOf("青春猪头少年", "青春豬頭少年")) {
            assertTrue("Missing 101291 for $query", catalog.searchMedia(query, MediaKind.ANIME).ids.contains(101291))
        }
    }
}
