package com.axiel7.anihyou.core.localization

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexedSearchEquivalenceTest {
    @Test fun indexedResultsAgreeWithExhaustiveOracle() {
        val source = CatalogSource("s", "r", "https://example.org", "CC0", "a".repeat(64))
        val random = Random(74012)
        val pieces = listOf("星", "之", "火", "影", "進", "击", "A", "Ｂ", "-", " ", "𠀀", "é")
        fun name() = (0 until random.nextInt(1, 9)).joinToString("") { pieces.random(random) }.ifBlank { "星" }
        val records = (1..700).shuffled(random).map { id ->
            val aliases = listOf(name(), name())
            CatalogRecord(EntityKey(EntityKind.MEDIA, id), name(), aliases + aliases.first(), "s", if (id % 3 == 0) MediaKind.MANGA else MediaKind.ANIME)
        } + listOf(
            CatalogRecord(EntityKey(EntityKind.MEDIA, 701), "星", listOf("星之旅", "星之旅", "Star"), "s", MediaKind.ANIME),
            CatalogRecord(EntityKey(EntityKind.MEDIA, 702), "星之旅", listOf("星"), "s", MediaKind.MANGA),
        )
        val snapshot = LocalizationSnapshot.create("v", listOf(source), records)
        // This oracle traverses every name independently of the postings index.
        fun normalized(value: String): String = SearchTextNormalizer.normalize(value)
        fun oracle(query: String, kind: MediaKind): List<Int> {
            val term = normalized(query)
            if (term.isEmpty()) return emptyList()
            return records.filter { it.mediaKind == kind }.mapNotNull { record ->
                val rank = (listOf(record.displayName) + record.aliases).map { normalized(it) }.minOf { name ->
                    when { name == term -> 0; name.startsWith(term) -> 1; name.contains(term) -> 2; else -> 3 }
                }
                if (rank == 3) null else rank to record.key.id
            }.sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }).map { it.second }
        }
        val queries = listOf("星", "星之", "星-之", "𠀀", "𠀀星", "不存在龘", "--", "Ｂ", "Star") +
            List(100) { name() } + records.take(40).flatMap { listOf(it.displayName, it.aliases.first()) }
        for (kind in MediaKind.values()) for (query in queries) {
            val expected = oracle(query, kind)
            val result = snapshot.searchMedia(query, kind, 13)
            assertEquals("$kind $query", expected.take(13), result.ids)
            assertEquals("$kind $query", expected.size, result.totalMatches)
            assertEquals("$kind $query", expected.size > 13, result.isTruncated)
            val repeated = snapshot.searchMedia(query, kind, 13)
            assertEquals(result.ids, repeated.ids)
            assertEquals(result.totalMatches, repeated.totalMatches)
            val complete = snapshot.searchAllMedia(query, kind)
            assertEquals("all $kind $query", expected, complete.ids)
            assertEquals(expected.size, complete.totalMatches)
            assertEquals(false, complete.isTruncated)
        }
    }
}
