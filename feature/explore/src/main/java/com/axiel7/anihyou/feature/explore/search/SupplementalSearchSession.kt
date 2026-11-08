package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.network.localization.SubjectSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select

internal data class SupplementProgress(
    val hasMore: Boolean,
    val failed: Boolean = false,
    val retryAfterMillis: Long? = null,
    val depthLimited: Boolean = false,
    val unmappedWithoutName: Int = 0,
    val mappedIdsUsePopularityFallback: Boolean = false,
)

/** A caller-owned session: one action discovers a page and resolves at most two sources. */
internal class SupplementalSearchSession<T>(
    private val query: String,
    private val kind: MediaKind,
    private val snapshot: LocalizationSnapshot,
    localIds: List<Int>,
    private val idOf: (T) -> Int,
    private val discover: suspend (Int) -> SubjectSearchResult,
    private val fetch: suspend (String, List<Int>?, Int) -> PagedResult<T>,
    private val mappedIdsUsePopularityFallback: Boolean = false,
) {
    private data class Cursor(val query: String, val ids: List<Int>? = null, val page: Int = 1)
    private sealed interface WorkOutcome<out T> {
        data class Discovery(val result: SubjectSearchResult) : WorkOutcome<Nothing>
        data object DiscoveryFailed : WorkOutcome<Nothing>
        data class Fetch<T>(val cursor: Cursor, val result: PagedResult<T>) : WorkOutcome<T>
    }

    private val pending = ArrayDeque<Cursor>()
    private val knownIds = localIds.toMutableSet()
    private val names = hashSetOf(query.trim())
    private val subjects = HashSet<Int>()
    private val returned = HashSet<Int>()
    private val sourceSeen = HashMap<Pair<String, List<Int>?>, MutableSet<Int>>()
    private var nextOffset: Int? = 0
    private var depthLimited = false
    private var nameless = 0

    init {
        // With no local candidates, the primary search already uses this text.
        if (localIds.isNotEmpty()) pending.add(Cursor(query))
    }

    suspend fun loadMore(onPage: (List<T>) -> Unit): SupplementProgress =
        loadMoreWithMetadata { rows, _ -> onPage(rows) }

    suspend fun loadMoreWithMetadata(
        onPage: (rows: List<T>, mappedIdsUsePopularityFallback: Boolean) -> Unit,
    ): SupplementProgress = coroutineScope {
        var failed = false
        var retryAfter: Long? = null
        var popularityFallback = false
        val offset = nextOffset?.takeIf { pending.size < 2 }
        var discovery: Deferred<WorkOutcome<T>>? = offset?.let {
            async {
                try { WorkOutcome.Discovery(discover(it)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { WorkOutcome.DiscoveryFailed }
            }
        }
        var remaining = MAX_RESOLUTIONS
        val attempted = HashSet<Cursor>()
        val activeFetches = mutableListOf<Pair<Cursor, Deferred<WorkOutcome<T>>>>()

        fun fillFetchSlots() {
            val discoverySlot = if (discovery?.isActive == true) 1 else 0
            while (remaining > 0 && activeFetches.size + discoverySlot < MAX_CONCURRENCY) {
                val cursor = pending.firstOrNull { it !in attempted } ?: return
                attempted.add(cursor)
                remaining--
                if (cursor.ids != null && mappedIdsUsePopularityFallback) popularityFallback = true
                val work = async<WorkOutcome<T>> {
                    val result = try { fetch(cursor.query, cursor.ids, cursor.page) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { PagedResult.Error("Search source unavailable") }
                    WorkOutcome.Fetch(cursor, result)
                }
                activeFetches += cursor to work
            }
        }

        fun deferFailedCursor(cursor: Cursor) {
            pending.remove(cursor)
            pending.addLast(cursor)
        }

        fun failFetch(cursor: Cursor) {
            failed = true
            deferFailedCursor(cursor)
        }

        fun processFetch(outcome: WorkOutcome.Fetch<T>) {
            val cursor = outcome.cursor
            activeFetches.removeAll { it.first == cursor }
            val result = outcome.result
            if (result !is PagedResult.Success || result.currentPage != cursor.page) {
                failFetch(cursor)
                return
            }
            val previous = sourceSeen.getOrPut(cursor.query to cursor.ids) { HashSet() }
            if ((cursor.ids != null && result.list.any { idOf(it) !in cursor.ids }) ||
                (result.hasNextPage && result.list.none { idOf(it) !in previous })) {
                failFetch(cursor)
                return
            }
            pending.remove(cursor)
            previous.addAll(result.list.map(idOf))
            val rows = result.list.filter { returned.add(idOf(it)) }
            if (result.hasNextPage) {
                if (cursor.page * CandidatePageSession.PAGE_SIZE < CandidatePageSession.PARTITION_SIZE) {
                    pending.addLast(cursor.copy(page = cursor.page + 1))
                } else depthLimited = true
            }
            onPage(rows, cursor.ids != null && mappedIdsUsePopularityFallback)
        }

        fun processDiscovery(outcome: WorkOutcome.Discovery) {
            discovery = null
            when (val result = outcome.result) {
                is SubjectSearchResult.Failed -> {
                    failed = true
                    retryAfter = result.retryAfterMillis
                }
                is SubjectSearchResult.Success -> {
                    val page = result.page
                    val fresh = page.hits.filter { it.subjectId !in subjects }
                    if (page.nextOffset != null && fresh.isEmpty()) {
                        failed = true
                    } else {
                        nextOffset = page.nextOffset
                        val mapped = ArrayList<Int>()
                        for (hit in fresh) {
                            subjects.add(hit.subjectId)
                            val identity = snapshot.mediaForBangumi(kind, hit.subjectId)
                            if (identity != null) {
                                if (knownIds.add(identity.id)) mapped.add(identity.id)
                            } else {
                                val original = hit.originalName.trim()
                                if (original.isBlank()) nameless++
                                else if (names.add(original)) pending.addLast(Cursor(original))
                            }
                        }
                        if (mapped.isNotEmpty()) pending.addFirst(Cursor("", mapped))
                    }
                }
            }
        }

        fillFetchSlots()
        while (activeFetches.isNotEmpty() || discovery != null) {
            val outcome = select<WorkOutcome<T>> {
                discovery?.let { work -> work.onAwait { it } }
                activeFetches.forEach { (_, work) -> work.onAwait { it } }
            }
            currentCoroutineContext().ensureActive()
            when (outcome) {
                is WorkOutcome.Discovery -> processDiscovery(outcome)
                is WorkOutcome.DiscoveryFailed -> {
                    discovery = null
                    failed = true
                }
                is WorkOutcome.Fetch<T> -> processFetch(outcome)
            }
            fillFetchSlots()
        }
        SupplementProgress(
            hasMore = pending.isNotEmpty() || nextOffset != null,
            failed = failed,
            retryAfterMillis = retryAfter,
            depthLimited = depthLimited,
            unmappedWithoutName = nameless,
            mappedIdsUsePopularityFallback = popularityFallback,
        )
    }

    companion object {
        private const val MAX_RESOLUTIONS = 2
        private const val MAX_CONCURRENCY = 2
    }
}
