package com.axiel7.anihyou.core.domain.localization

import com.axiel7.anihyou.core.localization.BangumiLink
import com.axiel7.anihyou.core.localization.CatalogSource
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaIdentity
import com.axiel7.anihyou.core.network.localization.DescriptionFailure
import com.axiel7.anihyou.core.network.localization.DescriptionFetchResult
import com.axiel7.anihyou.core.network.localization.DescriptionTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

sealed interface DescriptionState {
    data object Disabled : DescriptionState
    data object Unmapped : DescriptionState
    data object Loading : DescriptionState
    data class Available(val summary: String, val subjectId: Int) : DescriptionState
    data object NoContent : DescriptionState
    data class Failed(val kind: DescriptionFailure, val retryAt: Long) : DescriptionState
}

class ChineseDescriptionRepository(
    private val transport: DescriptionTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val link: BangumiLink, val revision: String, val hash: String)
    private data class Entry(val state: DescriptionState, val expiresAt: Long)
    private class Gate { val mutex = Mutex(); var users = 0 }
    private val cache = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val gates = mutableMapOf<Key, Gate>()
    private val permits = Semaphore(256)
    private val networkPermits = Semaphore(4)

    suspend fun fetch(link: BangumiLink, source: CatalogSource): DescriptionState {
        require(link.sourceId == source.id)
        val key = Key(link, source.revision, source.sha256)
        permits.acquire()
        val gate = synchronized(gates) { gates.getOrPut(key) { Gate() }.also { it.users++ } }
        try {
            return gate.mutex.withLock {
                val now = nowMillis()
                synchronized(cache) { cache[key]?.takeIf { now < it.expiresAt } }?.let { return@withLock it.state }
                val result = networkPermits.withPermit { transport.fetch(link) }
                currentCoroutineContext().ensureActive()
                val completed = nowMillis()
                val duration = when (result) {
                    is DescriptionFetchResult.Available -> 24 * 60 * 60 * 1000L
                    DescriptionFetchResult.NoContent -> 15 * 60 * 1000L
                    is DescriptionFetchResult.Failed -> if (result.kind == DescriptionFailure.RATE_LIMITED) {
                        (result.retryAfterMillis ?: 60_000L).coerceIn(1_000L, 24 * 60 * 60 * 1000L)
                    } else 30_000L
                }
                val expiry = if (completed > Long.MAX_VALUE - duration) Long.MAX_VALUE else completed + duration
                val state = when (result) {
                    is DescriptionFetchResult.Available -> DescriptionState.Available(result.summary, result.subjectId)
                    DescriptionFetchResult.NoContent -> DescriptionState.NoContent
                    is DescriptionFetchResult.Failed -> DescriptionState.Failed(result.kind, expiry)
                }
                synchronized(cache) {
                    cache[key] = Entry(state, expiry)
                    while (cache.size > 256) cache.remove(cache.keys.first())
                }
                state
            }
        } finally {
            synchronized(gates) { if (--gate.users == 0) gates.remove(key) }
            permits.release()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(
        identity: Flow<MediaIdentity?>,
        enabled: Flow<Boolean>,
        snapshots: StateFlow<LocalizationSnapshot>,
        retries: Flow<Unit>,
    ): Flow<DescriptionState> {
        data class Request(val enabled: Boolean, val link: BangumiLink?, val source: CatalogSource?)
        val request = combine(identity, enabled, snapshots) { media, isEnabled, snapshot ->
            val link = media?.let(snapshot::mediaLink)
            Request(isEnabled, link, link?.let { item -> snapshot.sources.firstOrNull { it.id == item.sourceId } })
        }.distinctUntilChanged { old, new ->
            old.enabled == new.enabled && old.link == new.link &&
                old.source?.revision == new.source?.revision && old.source?.sha256 == new.source?.sha256
        }
        return channelFlow {
            val publication = Mutex()
            var generation = 0L
            var active: Job? = null
            combine(request, retries.onStart { emit(Unit) }) { current, _ -> current }
                .collect { current ->
                    publication.withLock {
                        generation++
                        val requestGeneration = generation
                        active?.cancel()
                        active = null
                        when {
                            !current.enabled -> send(DescriptionState.Disabled)
                            current.link == null || current.source == null -> send(DescriptionState.Unmapped)
                            else -> {
                                send(DescriptionState.Loading)
                                active = launch {
                                    val result = fetch(current.link, current.source)
                                    publication.withLock {
                                        if (requestGeneration == generation) send(result)
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}
