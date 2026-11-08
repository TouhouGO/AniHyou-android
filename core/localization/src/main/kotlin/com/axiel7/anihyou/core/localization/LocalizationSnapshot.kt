package com.axiel7.anihyou.core.localization

import java.util.Collections

class LocalizationSnapshot private constructor(
    val version: String,
    sources: List<CatalogSource>,
    records: List<CatalogRecord>,
    tags: List<CatalogTag>,
    links: List<BangumiLink>,
    searchAliases: List<SearchAlias>,
) {
    val sources: List<CatalogSource> = Collections.unmodifiableList(sources.toList())
    private val tagsByName = tags.associateBy { it.rawName }
    private val byKey = records.associateBy { it.key }
    private val linksByMedia = links.associateBy { it.media }
    private val mediaBySubject = links.associate { (it.media.kind to it.subjectId) to it.media }
    private val mediaByKind = MediaKind.entries.associateWith { kind ->
        val evidence = mutableMapOf<Int, MutableSet<MediaKind>>()
        records.filter { it.key.kind == EntityKind.MEDIA }.forEach { evidence.getOrPut(it.key.id) { mutableSetOf() }.add(it.mediaKind!!) }
        links.forEach { evidence.getOrPut(it.media.id) { mutableSetOf() }.add(it.media.kind) }
        searchAliases.filter { it.mediaKind != null }.forEach { evidence.getOrPut(it.aniListId) { mutableSetOf() }.add(it.mediaKind!!) }
        val names = mutableMapOf<Int, MutableList<String>>()
        records.filter { it.key.kind == EntityKind.MEDIA && it.mediaKind == kind }.forEach {
            names.getOrPut(it.key.id) { mutableListOf() }.addAll(listOf(it.displayName) + it.aliases)
        }
        searchAliases.filter { evidence[it.aniListId]?.contains(kind) != false }.forEach {
            names.getOrPut(it.aniListId) { mutableListOf() }.addAll(it.names)
        }
        MediaSearchIndex(names)
    }
    val recordCount: Int get() = byKey.size

    fun displayName(key: EntityKey, original: String, enabled: Boolean): String =
        if (enabled) byKey[key]?.displayName ?: original else original

    fun tagName(rawName: String, enabled: Boolean): String =
        if (enabled) tagsByName[rawName]?.displayName ?: rawName else rawName

    fun mediaLink(identity: MediaIdentity): BangumiLink? = linksByMedia[identity]

    fun mediaForBangumi(kind: MediaKind, subjectId: Int): MediaIdentity? = mediaBySubject[kind to subjectId]

    fun searchMedia(query: String, mediaKind: MediaKind, limit: Int = 500): MediaCandidates {
        require(limit in 1..500) { "Limit must be between 1 and 500" }
        return findMedia(query, mediaKind, limit)
    }

    fun searchAllMedia(query: String, mediaKind: MediaKind): MediaCandidates =
        findMedia(query, mediaKind, Int.MAX_VALUE)

    private fun findMedia(query: String, mediaKind: MediaKind, limit: Int): MediaCandidates {
        val term = SearchTextNormalizer.normalize(query)
        if (term.isEmpty()) return MediaCandidates(emptyList(), 0, false, version)
        return mediaByKind[mediaKind]?.search(term, limit, version)
            ?: MediaCandidates(emptyList(), 0, false, version)
    }

    companion object {
        val EMPTY: LocalizationSnapshot = LocalizationSnapshot("", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        fun create(
            version: String,
            sources: List<CatalogSource>,
            records: List<CatalogRecord>,
            tags: List<CatalogTag> = emptyList(),
            links: List<BangumiLink> = emptyList(),
            searchAliases: List<SearchAlias> = emptyList(),
        ): LocalizationSnapshot {
            require(version.isNotBlank()) { "Catalog version must not be blank" }
            val sourceIds = sources.map { it.id }
            require(sourceIds.toSet().size == sourceIds.size) { "Duplicate source ID" }
            require(records.map { it.key }.toSet().size == records.size) { "Duplicate entity identity" }
            require(records.all { it.sourceId in sourceIds }) { "Unknown record source" }
            require(tags.map { it.rawName }.toSet().size == tags.size) { "Duplicate tag raw name" }
            require(tags.all { it.sourceId in sourceIds }) { "Unknown tag source" }
            require(links.map { it.media }.toSet().size == links.size) { "Duplicate link media identity" }
            require(links.map { it.media.kind to it.subjectId }.toSet().size == links.size) { "Duplicate link target" }
            require(links.all { it.sourceId in sourceIds }) { "Unknown link source" }
            require(searchAliases.map { it.sourceId to it.aniListId }.toSet().size == searchAliases.size) { "Duplicate search alias" }
            require(searchAliases.all { it.sourceId in sourceIds }) { "Unknown alias source" }
            val evidence = mutableMapOf<Int, MutableSet<MediaKind>>()
            records.filter { it.key.kind == EntityKind.MEDIA }.forEach { evidence.getOrPut(it.key.id) { mutableSetOf() }.add(it.mediaKind!!) }
            links.forEach { evidence.getOrPut(it.media.id) { mutableSetOf() }.add(it.media.kind) }
            searchAliases.filter { it.mediaKind != null }.forEach { evidence.getOrPut(it.aniListId) { mutableSetOf() }.add(it.mediaKind!!) }
            require(searchAliases.all { (evidence[it.aniListId]?.size ?: 0) <= 1 }) { "Conflicting alias media kind" }
            return LocalizationSnapshot(version, sources, records.toList(), tags.toList(), links.toList(), searchAliases.toList())
        }
    }
}

private data class IndexedMedia(val id: Int, val names: List<String>)

// Each posting contains a record at most once, in ID order. A code point is only
// a prefilter: checking complete names below preserves substring semantics.
private class MediaSearchIndex(records: Map<Int, List<String>>) {
    private val entries = records.toSortedMap().map { (id, names) ->
        IndexedMedia(id, names.map(SearchTextNormalizer::normalize).filter { it.isNotEmpty() }.distinct())
    }
    private val postings: Map<Int, IntArray> = buildMap {
        val builders = mutableMapOf<Int, MutableList<Int>>()
        this@MediaSearchIndex.entries.forEachIndexed { ordinal, entry ->
            val characters = mutableSetOf<Int>()
            entry.names.forEach { name -> name.codePoints().forEach { characters.add(it) } }
            characters.forEach { point -> builders.getOrPut(point) { mutableListOf() }.add(ordinal) }
        }
        builders.forEach { (point, ordinals) -> put(point, ordinals.toIntArray()) }
    }

    fun search(term: String, limit: Int, version: String): MediaCandidates {
        var shortest: IntArray? = null
        val points = term.codePoints().iterator()
        while (points.hasNext()) {
            val posting = postings[points.nextInt()] ?: return MediaCandidates(emptyList(), 0, false, version)
            if (shortest == null || posting.size < shortest.size) shortest = posting
        }
        val buckets = Array(3) { ArrayList<Int>() }
        var total = 0
        for (ordinal in shortest ?: IntArray(0)) {
            val entry = entries[ordinal]
            var rank = 3
            for (name in entry.names) {
                val current = when {
                    name == term -> 0
                    name.startsWith(term) -> 1
                    name.contains(term) -> 2
                    else -> 3
                }
                if (current < rank) rank = current
                if (rank == 0) break
            }
            if (rank < 3) {
                total++
                if (buckets[rank].size < limit) buckets[rank].add(entry.id)
            }
        }
        val ids = ArrayList<Int>(minOf(limit, total))
        for (bucket in buckets) for (id in bucket) {
            if (ids.size == limit) break
            ids.add(id)
        }
        return MediaCandidates(ids, total, total > limit, version)
    }
}

