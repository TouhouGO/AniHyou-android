package com.axiel7.anihyou.feature.usermedialist

import com.axiel7.anihyou.core.localization.EntityKey
import com.axiel7.anihyou.core.localization.EntityKind
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.model.media.plainName
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaListStatus
import com.axiel7.anihyou.core.network.type.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map

// Dictionary names use normalized exact/prefix/substring matching, not fuzzy translation.
internal fun localizedListCandidates(snapshot: LocalizationSnapshot, query: String, kind: MediaKind): Set<Int> =
    snapshot.searchAllMedia(query, kind).ids.toHashSet()

internal fun localizedListTitle(snapshot: LocalizationSnapshot, id: Int, raw: String?, enabled: Boolean): String? {
    if (!enabled || id <= 0) return raw
    val mapped = snapshot.displayName(EntityKey(EntityKind.MEDIA, id), raw.orEmpty(), true)
    return mapped.takeIf { raw != null || it.isNotEmpty() }
}

internal fun <T> immutableListInputs(lists: Map<String, List<T>>): Map<String, List<T>> =
    lists.mapValues { (_, entries) -> entries.toList() }

internal fun standardListNames(mediaType: MediaType, format: MediaFormat): Set<String> {
    val formatName = format.rawValue.replace('_', ' ').uppercase()
    return listOf(
        MediaListStatus.CURRENT,
        MediaListStatus.PLANNING,
        MediaListStatus.COMPLETED,
        MediaListStatus.DROPPED,
        MediaListStatus.PAUSED,
        MediaListStatus.REPEATING,
    ).flatMap { status ->
        val statusName = status.plainName(mediaType).uppercase()
        listOf(statusName, "$statusName $formatName")
    }.toSet()
}

internal fun <T> listTitleComparator(descending: Boolean, title: (T) -> String?, id: (T) -> Int): Comparator<T> {
    val primary = if (descending) compareByDescending<T> { title(it) } else compareBy<T> { title(it) }
    return primary.thenBy { id(it) }
}

internal class SearchGenerationGuard {
    @Volatile
    private var current = 0L
    fun next(): Long = ++current
    fun isCurrent(generation: Long): Boolean = generation == current
}

internal suspend fun <T, R> Flow<T>.publishLatest(
    compute: suspend (T) -> R,
    publish: (R) -> Unit,
) {
    val generation = SearchGenerationGuard()
    map { generation.next() to it }
        .collectLatest { (requestGeneration, input) ->
            val result = compute(input)
            if (generation.isCurrent(requestGeneration)) publish(result)
        }
}
