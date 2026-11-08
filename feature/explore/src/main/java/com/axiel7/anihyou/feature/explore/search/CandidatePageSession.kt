package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.base.PagedResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps each candidate reachable within AniList's 5,000-result page depth. */
internal class CandidatePageSession<T>(
    ids: List<Int>,
    private val idOf: (T) -> Int,
    private val fetch: suspend (List<Int>, Int, Int) -> PagedResult<T>,
) {
    private val partitions = ids.distinct().chunked(PARTITION_SIZE)
    private val mutex = Mutex()
    private var partition = 0
    private var serverPage = 1
    private val seen = HashSet<Int>()
    private var prepared: Prepared<T>? = null
    val isPartitioned: Boolean get() = partitions.size > 1

    init { require(ids.isNotEmpty() && ids.all { it > 0 }) }

    suspend fun next(): PagedResult<T> = mutex.withLock {
        prepared?.let { return@withLock it.result }
        var nextPartition = partition
        var nextPage = serverPage
        repeat(MAX_EMPTY_REQUESTS) {
            if (nextPartition >= partitions.size) {
                return@withLock prepare(emptyList(), nextPartition, nextPage)
            }
            currentCoroutineContext().ensureActive()
            val candidates = partitions[nextPartition]
            when (val response = fetch(candidates, nextPage, PAGE_SIZE)) {
                is PagedResult.Error -> return@withLock response
                PagedResult.Loading -> return@withLock PagedResult.Error("Search did not return a page")
                is PagedResult.Success -> {
                    currentCoroutineContext().ensureActive()
                    if (response.currentPage != nextPage) {
                        return@withLock PagedResult.Error("Search did not return the requested page")
                    }
                    if (response.list.any { idOf(it) !in candidates }) {
                        return@withLock PagedResult.Error("Search returned an unexpected candidate")
                    }
                    val newIds = HashSet<Int>()
                    val items = response.list.filter { idOf(it) !in seen && newIds.add(idOf(it)) }
                    if (response.hasNextPage && items.isEmpty()) {
                        return@withLock PagedResult.Error("Search pagination made no progress")
                    }
                    if (response.hasNextPage && nextPage * PAGE_SIZE < PARTITION_SIZE) {
                        nextPage++
                    } else {
                        nextPartition++
                        nextPage = 1
                    }
                    if (items.isNotEmpty()) return@withLock prepare(items, nextPartition, nextPage)
                }
            }
        }
        // Bound one interaction without silently discarding remaining partitions.
        prepare(emptyList(), nextPartition, nextPage)
    }

    /** Publication and acknowledgement run together on the caller's UI dispatcher. */
    fun acknowledge() {
        val page = prepared ?: return
        page.result.list.forEach { seen.add(idOf(it)) }
        partition = page.partition
        serverPage = page.serverPage
        prepared = null
    }

    private fun prepare(items: List<T>, partition: Int, serverPage: Int): PagedResult.Success<T> =
        PagedResult.Success(items, null, partition < partitions.size).also {
            prepared = Prepared(it, partition, serverPage)
        }

    private data class Prepared<T>(val result: PagedResult.Success<T>, val partition: Int, val serverPage: Int)

    companion object {
        const val PARTITION_SIZE = 5_000
        const val PAGE_SIZE = 25
        private const val MAX_EMPTY_REQUESTS = 4
    }
}
