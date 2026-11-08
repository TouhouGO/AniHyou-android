package com.axiel7.anihyou.core.domain.localization

import com.axiel7.anihyou.core.localization.*
import com.axiel7.anihyou.core.network.localization.*
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChineseDescriptionRepositoryTest {
    private val source = CatalogSource("s", "r", "url", "license", "a".repeat(64))
    private val link = BangumiLink(MediaIdentity(MediaKind.ANIME, 1), 2, "s")
    @Test fun coalescesAndExpiresAndKeepsSourceSeparate() = runTest {
        var now = 0L
        var calls = 0
        val repo = ChineseDescriptionRepository(DescriptionTransport { calls++; delay(10); DescriptionFetchResult.Available("x", 2) }) { now }
        val first = async { repo.fetch(link, source) }
        val second = async { repo.fetch(link, source) }
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
        now = 24 * 60 * 60 * 1000L
        repo.fetch(link, source)
        repo.fetch(link, source.copy(revision = "new"))
        assertEquals(3, calls)
    }
    @Test fun cooldownAndRecovery() = runTest {
        var now = 0L
        var calls = 0
        val repo = ChineseDescriptionRepository(DescriptionTransport {
            calls++
            if (calls == 1) DescriptionFetchResult.Failed(DescriptionFailure.RATE_LIMITED, 1) else DescriptionFetchResult.NoContent
        }) { now }
        assertEquals(DescriptionState.Failed(DescriptionFailure.RATE_LIMITED, 1000), repo.fetch(link, source))
        repo.fetch(link, source)
        assertEquals(1, calls)
        now = 1000
        assertEquals(DescriptionState.NoContent, repo.fetch(link, source))
        assertEquals(2, calls)
    }
    @Test fun cancelledNonCooperativeFetchDoesNotPopulateCache() = runTest {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val repo = ChineseDescriptionRepository(DescriptionTransport {
            calls++
            if (calls == 1) withContext(NonCancellable) { release.await() }
            DescriptionFetchResult.Available("value", 2)
        })
        val job = launch { repo.fetch(link, source) }
        runCurrent()
        job.cancel()
        release.complete(Unit)
        job.join()
        repo.fetch(link, source)
        assertEquals(2, calls)
    }
    @Test fun observerDiscardsLateResultWhenDisabled() = runTest {
        val release = CompletableDeferred<Unit>()
        val repo = ChineseDescriptionRepository(DescriptionTransport {
            withContext(NonCancellable) { release.await() }
            DescriptionFetchResult.Available("stale", 2)
        })
        val snapshot = LocalizationSnapshot.create("v", listOf(source), emptyList(), links = listOf(link))
        val enabled = MutableStateFlow(true)
        val states = mutableListOf<DescriptionState>()
        val job = launch { repo.observe(MutableStateFlow(link.media), enabled, MutableStateFlow(snapshot), MutableSharedFlow()).collect { states.add(it) } }
        runCurrent()
        enabled.value = false
        runCurrent()
        try {
            assertEquals(DescriptionState.Disabled, states.last())
        } finally {
            release.complete(Unit)
        }
        runCurrent()
        assertEquals(false, states.any { it is DescriptionState.Available })
        job.cancelAndJoin()
    }
    @Test fun cacheEvictsOldestAndNetworkConcurrencyIsBounded() = runTest {
        var calls = 0
        val repo = ChineseDescriptionRepository(DescriptionTransport { calls++; DescriptionFetchResult.NoContent })
        repeat(257) { id -> repo.fetch(BangumiLink(MediaIdentity(MediaKind.ANIME, id + 1), id + 1, "s"), source) }
        repo.fetch(BangumiLink(MediaIdentity(MediaKind.ANIME, 1), 1, "s"), source)
        assertEquals(258, calls)
        var active = 0
        var maximum = 0
        val limited = ChineseDescriptionRepository(DescriptionTransport {
            active++
            maximum = maxOf(maximum, active)
            delay(10)
            active--
            DescriptionFetchResult.NoContent
        })
        (1..12).map { id -> async { limited.fetch(BangumiLink(MediaIdentity(MediaKind.ANIME, id), id, "s"), source) } }.forEach { it.await() }
        assertEquals(4, maximum)
    }
    @Test fun noContentExpiresAfterFifteenMinutes() = runTest {
        var now = 0L
        var calls = 0
        val repo = ChineseDescriptionRepository(DescriptionTransport { calls++; DescriptionFetchResult.NoContent }) { now }
        repo.fetch(link, source)
        now = 899_999
        repo.fetch(link, source)
        assertEquals(1, calls)
        now++
        repo.fetch(link, source)
        assertEquals(2, calls)
    }
    @Test fun waitingCallerContinuesAfterOwnerIsCancelled() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val repo = ChineseDescriptionRepository(DescriptionTransport {
            calls++
            if (calls == 1) { started.complete(Unit); never.await() }
            DescriptionFetchResult.NoContent
        })
        val first = launch { repo.fetch(link, source) }
        started.await()
        val second = async { repo.fetch(link, source) }
        runCurrent()
        first.cancelAndJoin()
        assertEquals(DescriptionState.NoContent, second.await())
        assertEquals(2, calls)
    }
    @Test fun observerChangesMappingAndSnapshotGeneration() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val other = BangumiLink(link.media, 3, "s")
        val snapshots = MutableStateFlow(LocalizationSnapshot.create("v1", listOf(source), emptyList(), links = listOf(link)))
        val repo = ChineseDescriptionRepository(DescriptionTransport {
            calls++
            if (calls == 1) { started.complete(Unit); withContext(NonCancellable) { release.await() } }
            DescriptionFetchResult.Available("current", it.subjectId)
        })
        val states = mutableListOf<DescriptionState>()
        val job = launch { repo.observe(MutableStateFlow(link.media), MutableStateFlow(true), snapshots, MutableSharedFlow()).collect { states.add(it) } }
        started.await()
        snapshots.value = LocalizationSnapshot.create("v2", listOf(source), emptyList(), links = listOf(other))
        runCurrent()
        try {
            assertEquals(2, calls)
            assertEquals(DescriptionState.Available("current", 3), states.last())
        } finally {
            release.complete(Unit)
        }
        runCurrent()
        assertEquals(DescriptionState.Available("current", 3), states.last())
        assertEquals(false, states.contains(DescriptionState.Available("current", 2)))
        assertEquals(2, calls)
        job.cancelAndJoin()
    }
    @Test fun equivalentSnapshotsDoNotRestartOrEmitLoading() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val snapshots = MutableStateFlow(LocalizationSnapshot.create("v1", listOf(source), emptyList(), links = listOf(link)))
        val repo = ChineseDescriptionRepository(DescriptionTransport {
            calls++
            started.complete(Unit)
            release.await()
            DescriptionFetchResult.Available("value", 2)
        })
        val states = mutableListOf<DescriptionState>()
        val job = launch { repo.observe(MutableStateFlow(link.media), MutableStateFlow(true), snapshots, MutableSharedFlow()).collect { states.add(it) } }
        started.await()
        snapshots.value = LocalizationSnapshot.create("v2", listOf(source), emptyList(), links = listOf(link))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf(DescriptionState.Loading, DescriptionState.Available("value", 2)), states)
        snapshots.value = LocalizationSnapshot.create("v3", listOf(source), emptyList(), links = listOf(link))
        runCurrent()
        assertEquals(listOf(DescriptionState.Loading, DescriptionState.Available("value", 2)), states)
        job.cancelAndJoin()
    }
    @Test fun removingMappingPublishesUnmappedBeforeOldRequestCompletes() = runTest {
        val release = CompletableDeferred<Unit>()
        val snapshots = MutableStateFlow(LocalizationSnapshot.create("v1", listOf(source), emptyList(), links = listOf(link)))
        val repo = ChineseDescriptionRepository(DescriptionTransport {
            withContext(NonCancellable) { release.await() }
            DescriptionFetchResult.Available("stale", 2)
        })
        val states = mutableListOf<DescriptionState>()
        val job = launch { repo.observe(MutableStateFlow(link.media), MutableStateFlow(true), snapshots, MutableSharedFlow()).collect { states.add(it) } }
        runCurrent()
        snapshots.value = LocalizationSnapshot.create("v2", listOf(source), emptyList())
        runCurrent()
        try {
            assertEquals(DescriptionState.Unmapped, states.last())
        } finally {
            release.complete(Unit)
        }
        runCurrent()
        assertEquals(false, states.any { it is DescriptionState.Available })
        job.cancelAndJoin()
    }
}
