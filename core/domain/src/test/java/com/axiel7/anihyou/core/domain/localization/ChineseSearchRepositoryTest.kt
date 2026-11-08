package com.axiel7.anihyou.core.domain.localization

import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.network.localization.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChineseSearchRepositoryTest {
    private val anime = MediaKind.ANIME
    private fun success(offset: Int = 0, limit: Int = 25) = SubjectSearchResult.Success(SubjectSearchPage(offset, limit, offset + 1, listOf(SubjectSearchHit(1, "name", "中文"))))
    @Test fun cacheKeysExpiryAndLru() = runTest {
        var now = 0L; var calls = 0
        val repo = ChineseSearchRepository(SubjectSearchTransport { _, _, offset, limit -> calls++; success(offset, limit) }) { now }
        repo.search(" x ", anime); repo.search("x", anime)
        assertEquals(1, calls)
        repo.search("X", anime); repo.search("x", MediaKind.MANGA); repo.search("x", anime, 1); repo.search("x", anime, 0, 1)
        assertEquals(5, calls)
        now = 300_000; repo.search("x", anime)
        assertEquals(6, calls)
        repeat(65) { repo.search("query$it", anime) }
        repo.search("x", anime)
        assertEquals(72, calls)
    }
    @Test fun byteBudgetEvictsAndOversizedPagesAreNotCached() = runTest {
        var calls = 0
        val repo = ChineseSearchRepository(SubjectSearchTransport { query, _, _, _ ->
            calls++
            val size = if (query == "huge") 1024 * 1024 + 1 else 600_000
            SubjectSearchResult.Success(SubjectSearchPage(0, 25, 1, listOf(SubjectSearchHit(1, "x".repeat(size), ""))))
        })
        repo.search("first", anime); repo.search("second", anime); repo.search("first", anime)
        assertEquals(3, calls)
        repo.search("huge", anime); repo.search("huge", anime)
        assertEquals(5, calls)
        repo.search("first", anime)
        assertEquals(5, calls)
    }
    @Test fun cooldownAndNoNegativeCaching() = runTest {
        var now = 0L; var calls = 0
        val repo = ChineseSearchRepository(SubjectSearchTransport { _, _, _, _ ->
            calls++; when (calls) { 1 -> SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, 1); 2 -> SubjectSearchResult.Failed(SubjectSearchFailure.NETWORK); else -> success() }
        }) { now }
        assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, 1000), repo.search("x", anime))
        assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, 1000), repo.search("y", anime))
        now = 1000
        assertEquals(SubjectSearchFailure.NETWORK, (repo.search("y", anime) as SubjectSearchResult.Failed).kind)
        assertTrue(repo.search("y", anime) is SubjectSearchResult.Success)
        assertEquals(3, calls)
    }
    @Test fun firstRateLimitReportsClampedCooldown() = runTest {
        for ((retryAfter, expected) in listOf(null to 30_000L, 0L to 1_000L, Long.MAX_VALUE to 86_400_000L)) {
            var now = 0L
            var calls = 0
            val repo = ChineseSearchRepository(SubjectSearchTransport { _, _, _, _ ->
                calls++
                SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, retryAfter)
            }) { now }
            assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, expected), repo.search("first", anime))
            now = 500
            assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, expected - 500), repo.search("second", anime))
            assertEquals(1, calls)
        }
    }
    @Test fun concurrentRateLimitsReportLongestRemainingCooldown() = runTest {
        for ((firstDelay, secondDelay) in listOf(10_000L to 1_000L, 1_000L to 10_000L)) {
            var now = 0L
            val firstRelease = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()
            val repo = ChineseSearchRepository(SubjectSearchTransport { query, _, _, _ ->
                if (query == "first") firstRelease.await() else secondRelease.await()
                SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, if (query == "first") firstDelay else secondDelay)
            }) { now }
            val first = async { repo.search("first", anime) }
            val second = async { repo.search("second", anime) }
            runCurrent()
            firstRelease.complete(Unit)
            assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, firstDelay), first.await())
            now = 500
            secondRelease.complete(Unit)
            val remaining = maxOf(firstDelay - 500, secondDelay)
            assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, remaining), second.await())
            now = 750
            assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, remaining - 250), repo.search("third", anime))
        }
    }
    @Test fun boundsConcurrencyAndCancellationDoesNotCache() = runTest {
        var active = 0; var max = 0; var calls = 0
        val release = CompletableDeferred<Unit>()
        val repo = ChineseSearchRepository(SubjectSearchTransport { _, _, _, _ ->
            calls++; active++; max = maxOf(active, max)
            try { withContext(NonCancellable) { release.await() }; success() } finally { active-- }
        })
        val first = launch { repo.search("a", anime) }
        val second = async { repo.search("b", anime) }
        val third = async { repo.search("c", anime) }
        runCurrent(); assertEquals(2, active); assertEquals(2, max)
        first.cancel(); release.complete(Unit); first.join(); second.await(); third.await()
        repo.search("a", anime)
        assertEquals(4, calls)
    }
    @Test fun waitingCancellationReleasesPermit() = runTest {
        val release = CompletableDeferred<Unit>(); var calls = 0
        val repo = ChineseSearchRepository(SubjectSearchTransport { _, _, _, _ -> calls++; release.await(); success() })
        val a = launch { repo.search("a", anime) }; val b = launch { repo.search("b", anime) }
        val waiting = launch { repo.search("c", anime) }
        runCurrent(); waiting.cancelAndJoin(); release.complete(Unit); a.join(); b.join()
        repo.search("c", anime)
        assertEquals(3, calls)
    }
}
