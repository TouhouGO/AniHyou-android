package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.localization.LocalizationCatalog
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.model.SearchType
import com.axiel7.anihyou.core.model.genre.GenresAndTagsForSearch
import com.axiel7.anihyou.core.model.media.CountryOfOrigin
import com.axiel7.anihyou.core.model.media.MediaFormatLocalizable
import com.axiel7.anihyou.core.model.media.MediaSourceLocalizable
import com.axiel7.anihyou.core.model.media.MediaStatusLocalizable
import com.axiel7.anihyou.core.network.type.MediaSeason
import com.axiel7.anihyou.core.network.type.MediaSort
import java.io.File
import java.security.MessageDigest
import kotlin.system.measureNanoTime
import kotlinx.collections.immutable.persistentListOf

/** Host-JVM probe for search selection and filter/sort planning, never an Android latency test. */
fun main(args: Array<String>) {
    require(args.size <= 1) { "Expected at most one output path" }
    val catalogBytes = requireNotNull(
        LocalizationSnapshot::class.java.getResourceAsStream("/localization/catalog.json")
    ).use { it.readBytes() }
    val catalog = LocalizationCatalog.decode(catalogBytes.toString(Charsets.UTF_8))
    val catalogSha256 = sha256(catalogBytes)
    val directCoreDigest = MessageDigest.getInstance("SHA-256")
    var consumed = 0L
    val queryResults = mutableListOf<Map<String, Any?>>()
    val allWarmTimings = ArrayList<Long>(QUERY_CORPUS.size * SAMPLES_PER_QUERY)
    val allChangedTimings = ArrayList<Long>(QUERY_CORPUS.size * SAMPLES_PER_QUERY)
    val allFreshSelectionTimings = ArrayList<Long>(QUERY_CORPUS.size * SAMPLES_PER_QUERY)
    val allFreshPlanTimings = ArrayList<Long>(QUERY_CORPUS.size * SAMPLES_PER_QUERY)

    for (entry in QUERY_CORPUS) {
        val baseState = entry.state()
        val pageState = baseState.copy(page = 2)
        val changedStates = entry.changedStates()
        val filteredState = changedStates.last()
        val selected = MediaSearchSessions.select(baseState, catalog)
        val selectedCount = selected.ids?.size ?: 0
        require(selectedCount == selected.totalCandidates) { "Candidate selection narrowed results" }
        require(!selected.isTruncated) { "Candidate selection unexpectedly truncated" }

        // Reproduce the existing core probe digest and compare session output without timing it.
        val directCoreResult = catalog.searchAllMedia(entry.query, entry.kind)
        directCoreDigest.update(
            ("${entry.kind}:${entry.query}:${directCoreResult.ids}:${directCoreResult.totalMatches}\n")
                .toByteArray()
        )
        consumed += directCoreResult.ids.sumOf(Int::toLong) + directCoreResult.totalMatches
        val sessionUsesCatalog = MediaSearchSessions.requiresCatalog(baseState)
        if (sessionUsesCatalog) {
            require(selected.ids == directCoreResult.ids) { "Session and direct catalog IDs differ" }
            require(selected.totalCandidates == directCoreResult.totalMatches) {
                "Session and direct counts differ"
            }
        } else {
            require(selected.ids == null && selected.totalCandidates == 0) {
                "Native query used local candidates"
            }
        }

        val warmSession = MediaSearchSessions()
        warmSession.plan(baseState, catalog)
        repeat(WARMUP_ITERATIONS) {
            consumed += consume(warmSession.plan(pageState, catalog))
        }
        val warmResult = timedPlans(SAMPLES_PER_QUERY) {
            warmSession.plan(pageState, catalog)
        }
        consumed += warmResult.consumed
        allWarmTimings += warmResult.timings
        require((warmResult.value.mediaIds?.size ?: 0) == selectedCount) { "Warm plan lost candidates" }

        val changedSession = MediaSearchSessions()
        changedSession.plan(baseState, selected)
        repeat(WARMUP_ITERATIONS) {
            changedStates.forEach { changed ->
                changedSession.plan(baseState, selected)
                val request = requireNotNull(MediaSearchRequest.from(changed))
                val plan = changedSession.plan(changed, selected)
                consumed += consume(request) + consume(plan)
                require(plan.mediaIds === selected.ids) { "Changed filters discarded candidates" }
            }
        }
        val changedTimings = ArrayList<Long>(SAMPLES_PER_QUERY)
        val changedRepresentatives = linkedMapOf<String, MediaSearchPlan>()
        repeat(SAMPLES_PER_QUERY) { sample ->
            val changed = changedStates[sample % changedStates.size]
            changedSession.plan(baseState, selected)
            lateinit var request: MediaSearchRequest
            lateinit var plan: MediaSearchPlan
            val nanos = measureNanoTime {
                request = requireNotNull(MediaSearchRequest.from(changed))
                plan = changedSession.plan(changed, selected)
            }
            consumed += consume(request) + consume(plan)
            require(plan.mediaIds === selected.ids) { "Changed filters discarded candidates" }
            changedTimings += nanos
            changedRepresentatives[changedVariantName(changed)] = plan
        }
        allChangedTimings += changedTimings

        repeat(WARMUP_ITERATIONS) {
            consumed += consume(MediaSearchSessions.select(filteredState, catalog))
        }
        val freshSelectionResult = timedCandidates(SAMPLES_PER_QUERY) {
            MediaSearchSessions.select(filteredState, catalog)
        }
        consumed += freshSelectionResult.consumed
        allFreshSelectionTimings += freshSelectionResult.timings
        require((freshSelectionResult.value.ids?.size ?: 0) == selectedCount) {
            "Fresh selection changed counts"
        }

        repeat(WARMUP_ITERATIONS) {
            consumed += consume(MediaSearchSessions().plan(filteredState, catalog))
        }
        val freshPlanResult = timedPlans(SAMPLES_PER_QUERY) {
            MediaSearchSessions().plan(filteredState, catalog)
        }
        consumed += freshPlanResult.consumed
        allFreshPlanTimings += freshPlanResult.timings
        require((freshPlanResult.value.mediaIds?.size ?: 0) == selectedCount) { "Fresh plan lost candidates" }

        val sortOnlyPlan = changedRepresentatives.getValue("sort-only")
        val filteredPlan = changedRepresentatives.getValue("filtered-sort")
        require(sortOnlyPlan.sort == MediaSort.TITLE_ROMAJI) { "Sort-only fixture lost its sort" }
        require(filteredPlan.sort == MediaSort.POPULARITY_DESC) { "Filtered fixture lost its fallback" }
        require(freshPlanResult.value.sort == MediaSort.POPULARITY_DESC) {
            "Fresh filtered plan lost its fallback"
        }

        val requestDigest = digest { digestValue ->
            changedStates.forEach { state ->
                updateRequest(digestValue, requireNotNull(MediaSearchRequest.from(state)))
            }
        }
        queryResults += mapOf(
            "kind" to entry.kind.name,
            "query" to entry.query,
            "sessionUsesCatalog" to sessionUsesCatalog,
            "sessionSelectedIds" to selectedCount,
            "coreDirectReturnedIds" to directCoreResult.ids.size,
            "countComparison" to if (sessionUsesCatalog) "match" else "not-applicable-native-bypass",
            "totalCandidates" to selected.totalCandidates,
            "truncated" to selected.isTruncated,
            "requestDigest" to requestDigest,
            "changedFilterSortSorts" to mapOf(
                "sortOnly" to sortOnlyPlan.sort.name,
                "filtered" to filteredPlan.sort.name,
            ),
            "warmCachedPagePlan" to timingObject(warmResult.timings),
            "changedFilterSortPlan" to timingObject(changedTimings),
            "freshSelection" to timingObject(freshSelectionResult.timings),
            "freshSelectionAndPlan" to timingObject(freshPlanResult.timings),
            "phaseDigests" to mapOf(
                "warmCachedPagePlan" to digest { updatePlan(it, warmResult.value) },
                "changedFilterSortPlan" to digest {
                    updatePlan(it, sortOnlyPlan)
                    updatePlan(it, filteredPlan)
                },
                "freshSelection" to digest { updateCandidate(it, freshSelectionResult.value) },
                "freshSelectionAndPlan" to digest { updatePlan(it, freshPlanResult.value) },
            ),
        )
    }

    val directCoreResultDigest = directCoreDigest.digest().toHex()
    val report = mapOf(
        "schemaVersion" to 1,
        "measurement" to "host JVM; no network; decoded catalog and indexes reused; not Android device latency",
        "catalogVersion" to catalog.version,
        "catalogBytes" to catalogBytes.size,
        "catalogSha256" to catalogSha256,
        "directCoreResultDigest" to directCoreResultDigest,
        "referenceCoreResultDigest" to REFERENCE_CORE_RESULT_DIGEST,
        "directCoreDigestMatchesReference" to (directCoreResultDigest == REFERENCE_CORE_RESULT_DIGEST),
        "nativeQueriesBypassLocalSelection" to true,
        "javaVersion" to System.getProperty("java.version"),
        "javaVm" to System.getProperty("java.vm.name"),
        "os" to System.getProperty("os.name"),
        "architecture" to System.getProperty("os.arch"),
        "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
        "samplesPerQuery" to SAMPLES_PER_QUERY,
        "warmupIterations" to WARMUP_ITERATIONS,
        "candidateMode" to "complete local candidates; no result cap or narrowing",
        "changedFixture" to "sort-only TITLE_ROMAJI; filtered SEARCH_MATCH becomes POPULARITY_DESC with all session fields populated",
        "freshScope" to "fresh session and selection over the already-decoded immutable catalog; decode/index are excluded",
        "consumed" to consumed,
        "phaseSummary" to mapOf(
            "warmCachedPagePlan" to timingObject(allWarmTimings),
            "changedFilterSortPlan" to timingObject(allChangedTimings),
            "freshSelection" to timingObject(allFreshSelectionTimings),
            "freshSelectionAndPlan" to timingObject(allFreshPlanTimings),
        ),
        "corpus" to QUERY_CORPUS.map { entry ->
            mapOf("kind" to entry.kind.name, "query" to entry.query)
        },
        "queries" to queryResults,
    )
    val formatted = prettyJson(report)
    args.firstOrNull()?.let { path ->
        File(path).apply {
            parentFile?.mkdirs()
            writeText(formatted + "\n")
        }
    }
    println(formatted)
}

private data class CorpusEntry(val kind: MediaKind, val query: String) {
    fun state() = SearchUiState(
        query = query,
        searchType = when (kind) {
            MediaKind.ANIME -> SearchType.ANIME
            MediaKind.MANGA -> SearchType.MANGA
        },
        mediaSort = MediaSort.SEARCH_MATCH,
        isLoggedIn = false,
        page = 1,
        hasNextPage = true,
    )

    fun changedStates(): List<SearchUiState> {
        val manga = kind == MediaKind.MANGA
        val base = state().copy(page = 2)
        return listOf(
            base.copy(mediaSort = MediaSort.TITLE_ROMAJI),
            base.copy(
                mediaSort = MediaSort.SEARCH_MATCH,
                genresAndTagsForSearch = GenresAndTagsForSearch(
                    genreIn = setOf("Action"),
                    genreNot = setOf("Horror"),
                    tagIn = setOf("Ninja"),
                    tagNot = setOf("School"),
                    minimumTagPercentage = 70,
                ),
                selectedMediaFormats = persistentListOf(
                    if (manga) MediaFormatLocalizable.MANGA else MediaFormatLocalizable.TV
                ),
                selectedMediaStatuses = persistentListOf(MediaStatusLocalizable.FINISHED),
                startYear = 1990,
                endYear = 2026,
                season = MediaSeason.SPRING,
                episodesChaptersRange = if (manga) 1..500 else 1..1_000,
                durationVolumesRange = if (manga) 1..100 else 1..300,
                onMyList = false,
                isDoujin = false,
                isAdult = false,
                country = CountryOfOrigin.JAPAN,
                selectedSources = persistentListOf(MediaSourceLocalizable.MANGA),
            ),
        )
    }
}

private fun changedVariantName(state: SearchUiState): String =
    if (state.genresAndTagsForSearch.totalSize == 0) "sort-only" else "filtered-sort"

private data class TimedResult<T>(val value: T, val timings: List<Long>, val consumed: Long)

private fun timedPlans(samples: Int, action: () -> MediaSearchPlan): TimedResult<MediaSearchPlan> {
    lateinit var representative: MediaSearchPlan
    val timings = ArrayList<Long>(samples)
    var resultChecksum = 0L
    repeat(samples) {
        val nanos = measureNanoTime { representative = action() }
        timings += nanos
        resultChecksum = resultChecksum * 31 + consume(representative)
    }
    return TimedResult(representative, timings, resultChecksum)
}

private fun timedCandidates(samples: Int, action: () -> CandidateSelection): TimedResult<CandidateSelection> {
    lateinit var representative: CandidateSelection
    val timings = ArrayList<Long>(samples)
    var resultChecksum = 0L
    repeat(samples) {
        val nanos = measureNanoTime { representative = action() }
        timings += nanos
        resultChecksum = resultChecksum * 31 + consume(representative)
    }
    return TimedResult(representative, timings, resultChecksum)
}

private fun consume(plan: MediaSearchPlan): Long {
    var value = plan.query.hashCode().toLong()
    value = value * 31 + plan.sort.ordinal
    value = value * 31 + (plan.catalogVersion?.hashCode() ?: 0)
    value = value * 31 + plan.totalCandidates
    value = value * 31 + if (plan.isTruncated) 1 else 0
    value = value * 31 + if (plan.usesPopularityFallback) 1 else 0
    plan.mediaIds?.forEach { value = value * 31 + it }
    return value
}

private fun consume(selection: CandidateSelection): Long {
    var value = selection.key.hashCode().toLong()
    value = value * 31 + (selection.catalogVersion?.hashCode() ?: 0)
    value = value * 31 + selection.totalCandidates
    value = value * 31 + if (selection.isTruncated) 1 else 0
    selection.ids?.forEach { value = value * 31 + it }
    return value
}

private fun consume(request: MediaSearchRequest): Long = request.hashCode().toLong()

private fun timingObject(timings: List<Long>) = mapOf(
    "samples" to timings.size,
    "p50Millis" to percentile(timings, 0.50),
    "p95Millis" to percentile(timings, 0.95),
)

private fun digest(update: (MessageDigest) -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    update(digest)
    return digest.digest().toHex()
}

private fun updatePlan(digest: MessageDigest, plan: MediaSearchPlan) {
    digest.update(
        ("${plan.query}:${plan.mediaIds}:${plan.sort}:${plan.catalogVersion}:${plan.totalCandidates}:" +
            "${plan.isTruncated}:${plan.usesPopularityFallback}\n").toByteArray()
    )
}

private fun updateCandidate(digest: MessageDigest, selection: CandidateSelection) {
    digest.update(
        ("${selection.key}:${selection.ids}:${selection.catalogVersion}:${selection.totalCandidates}:" +
            "${selection.isTruncated}\n").toByteArray()
    )
}

private fun updateRequest(digest: MessageDigest, request: MediaSearchRequest) {
    digest.update((request.toString() + "\n").toByteArray())
}

private fun percentile(values: List<Long>, percentile: Double): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * percentile).toInt()] / 1_000_000.0
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun prettyJson(value: Any?): String = buildString { writeJson(this, value, 0) }

private fun writeJson(out: StringBuilder, value: Any?, level: Int) {
    when (value) {
        null -> out.append("null")
        is String -> writeJsonString(out, value)
        is Number, is Boolean -> out.append(value.toString())
        is Map<*, *> -> {
            out.append('{')
            if (value.isNotEmpty()) {
                var first = true
                value.forEach { (key, child) ->
                    if (!first) out.append(',')
                    first = false
                    out.append('\n').append("  ".repeat(level + 1))
                    writeJsonString(out, key.toString())
                    out.append(": ")
                    writeJson(out, child, level + 1)
                }
                out.append('\n').append("  ".repeat(level))
            }
            out.append('}')
        }
        is Iterable<*> -> {
            out.append('[')
            val list = value.toList()
            if (list.isNotEmpty()) {
                list.forEachIndexed { index, child ->
                    if (index > 0) out.append(',')
                    out.append('\n').append("  ".repeat(level + 1))
                    writeJson(out, child, level + 1)
                }
                out.append('\n').append("  ".repeat(level))
            }
            out.append(']')
        }
        else -> error("Unsupported JSON value: ${value::class}")
    }
}

private fun writeJsonString(out: StringBuilder, value: String) {
    out.append('"')
    value.forEach { character ->
        when (character) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (character.code < 0x20) {
                out.append("\\u%04x".format(character.code))
            } else {
                out.append(character)
            }
        }
    }
    out.append('"')
}

private const val SAMPLES_PER_QUERY = 150
private const val WARMUP_ITERATIONS = 30
private const val REFERENCE_CORE_RESULT_DIGEST =
    "929f80124d6b0ce215089d41092bcf58502bdf3f4b7ede6bd6d592575f9c22d8"

// Identical ordered ANIME/MANGA pairs as SearchPerformanceProbe.kt.
private val QUERY_CORPUS = listOf(
    CorpusEntry(MediaKind.ANIME, "\u661f"),
    CorpusEntry(MediaKind.ANIME, "\u4e4b"),
    CorpusEntry(MediaKind.ANIME, "\u706b\u5f71"),
    CorpusEntry(MediaKind.ANIME, "\u7269\u8bed"),
    CorpusEntry(MediaKind.ANIME, "\u706b\u5f71\u5fcd\u8005"),
    CorpusEntry(MediaKind.ANIME, "\u540d\u4fa6\u63a2\u67ef\u5357"),
    CorpusEntry(MediaKind.ANIME, "\u9032\u64ca\u7684\u5de8\u4eba"),
    CorpusEntry(MediaKind.ANIME, "\u3010\u6211\u63a8\u7684\u5b69\u5b50\u3011"),
    CorpusEntry(MediaKind.ANIME, "\u5b64\u72ec\u6447\u6eda"),
    CorpusEntry(MediaKind.ANIME, "\u94fe\u952f\u4eba"),
    CorpusEntry(MediaKind.ANIME, "\u91cd\u542f"),
    CorpusEntry(MediaKind.ANIME, "\u54b2\u826f"),
    CorpusEntry(MediaKind.ANIME, "Re:Zero"),
    CorpusEntry(MediaKind.ANIME, "\u846c\u9001\u7684\u8299\u8389\u83b2"),
    CorpusEntry(MediaKind.MANGA, "\u846c\u9001\u7684\u8299\u8389\u83b2"),
    CorpusEntry(MediaKind.MANGA, "\u706b\u5f71"),
    CorpusEntry(MediaKind.ANIME, "not-a-catalog-title-8247301"),
)
