package com.axiel7.anihyou.core.domain.localization

import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.network.localization.SubjectSearchFailure
import com.axiel7.anihyou.core.network.localization.SubjectSearchPage
import com.axiel7.anihyou.core.network.localization.SubjectSearchResult
import com.axiel7.anihyou.core.network.localization.SubjectSearchTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ChineseSearchRepository(
    private val transport: SubjectSearchTransport,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Key(val query: String, val kind: MediaKind, val offset: Int, val limit: Int)
    private data class Entry(val page: SubjectSearchPage, val storedAt: Long, val bytes: Long)
    private val cache = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val permits = Semaphore(2)
    private var bytes = 0L
    private var cooldownStart = 0L
    private var cooldownDuration = 0L

    suspend fun search(query: String, kind: MediaKind, offset: Int = 0, limit: Int = 25): SubjectSearchResult {
        val trimmed = query.trim()
        if (trimmed.isBlank() || trimmed.codePointCount(0, trimmed.length) > 512 || offset < 0 || limit !in 1..50) {
            return SubjectSearchResult.Failed(SubjectSearchFailure.INVALID_REQUEST)
        }
        val key = Key(trimmed, kind, offset, limit)
        fun lookup(): SubjectSearchResult? = synchronized(cache) {
            val now = clock()
            val entry = cache[key]
            if (entry != null) {
                if (now - entry.storedAt in 0 until TTL) return@synchronized SubjectSearchResult.Success(entry.page)
                cache.remove(key)
                bytes -= entry.bytes
            }
            val remaining = cooldownDuration - (now - cooldownStart).coerceAtLeast(0)
            if (remaining > 0) SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, remaining) else null
        }
        lookup()?.let { return it }
        return permits.withPermit {
            lookup()?.let { return@withPermit it }
            val result = transport.search(trimmed, kind, offset, limit)
            currentCoroutineContext().ensureActive()
            synchronized(cache) {
                val now = clock()
                when (result) {
                    is SubjectSearchResult.Success -> {
                        val page = result.page
                        val copy = SubjectSearchPage(page.offset, page.limit, page.total, page.hits)
                        val size = copy.hits.sumOf { it.originalName.toByteArray(Charsets.UTF_8).size.toLong() + it.chineseName.toByteArray(Charsets.UTF_8).size }
                        if (size <= MAX_BYTES) {
                            cache.remove(key)?.let { bytes -= it.bytes }
                            cache[key] = Entry(copy, now, size)
                            bytes += size
                            while (cache.size > 64 || bytes > MAX_BYTES) {
                                val eldest = cache.entries.iterator().next()
                                bytes -= eldest.value.bytes
                                cache.remove(eldest.key)
                            }
                        }
                        SubjectSearchResult.Success(copy)
                    }
                    is SubjectSearchResult.Failed -> {
                        if (result.kind == SubjectSearchFailure.RATE_LIMITED) {
                            val duration = (result.retryAfterMillis ?: 30_000L).coerceIn(1_000L, 86_400_000L)
                            val remaining = cooldownDuration - (now - cooldownStart).coerceAtLeast(0)
                            if (duration > remaining) { cooldownStart = now; cooldownDuration = duration }
                            SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, maxOf(duration, remaining))
                        } else result
                    }
                }
            }
        }
    }
}

private const val TTL = 5 * 60 * 1000L
private const val MAX_BYTES = 1024 * 1024L
