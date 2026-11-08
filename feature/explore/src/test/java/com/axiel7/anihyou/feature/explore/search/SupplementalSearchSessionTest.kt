package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.localization.BangumiLink
import com.axiel7.anihyou.core.localization.CatalogSource
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaIdentity
import com.axiel7.anihyou.core.localization.MediaKind
import com.axiel7.anihyou.core.network.localization.SubjectSearchFailure
import com.axiel7.anihyou.core.network.localization.SubjectSearchHit
import com.axiel7.anihyou.core.network.localization.SubjectSearchPage
import com.axiel7.anihyou.core.network.localization.SubjectSearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupplementalSearchSessionTest {
    private val catalog = LocalizationSnapshot.create(
        "v1", listOf(CatalogSource("s", "r", "https://example.org", "CC0", "a".repeat(64))), emptyList(),
        links = listOf(BangumiLink(MediaIdentity(MediaKind.ANIME, 20), 42, "s")),
    )
    private fun page(offset: Int = 0, total: Int = 0, vararg hits: SubjectSearchHit) =
        SubjectSearchResult.Success(SubjectSearchPage(offset, 25, total, hits.toList()))

    @Test fun nativeTextPublishesWhileBangumiIsStillPending() = runTest {
        val remote = CompletableDeferred<SubjectSearchResult>()
        val received = mutableListOf<Int>()
        val session = SupplementalSearchSession("中文", MediaKind.ANIME, catalog, listOf(10), { it: Int -> it },
            discover = { remote.await() }, fetch = { text, ids, p ->
                assertEquals("中文", text)
                assertNull(ids)
                PagedResult.Success(listOf(30), p, false)
            })
        val job = async { session.loadMore { received += it } }
        repeat(10) { runCurrent() }
        assertEquals(listOf(30), received)
        assertFalse(job.isCompleted)
        remote.complete(page())
        assertFalse(job.await().hasMore)
    }

    @Test fun readyMappedFetchPublishesWhileNativeFetchIsStillPending() = runTest {
        val native = CompletableDeferred<PagedResult<Int>>()
        val requests = mutableListOf<List<Int>?>()
        val received = mutableListOf<Int>()
        val session = SupplementalSearchSession("中文", MediaKind.ANIME, catalog, listOf(10), { it: Int -> it },
            discover = { page(total = 1, hits = arrayOf(SubjectSearchHit(42, "mapped", "中文"))) },
            fetch = { _, ids, p ->
                requests += ids
                if (ids == null) native.await() else PagedResult.Success(listOf(20), p, false)
            })

        val job = async { session.loadMore { received += it } }
        runCurrent()

        assertEquals(listOf(20), received)
        assertEquals(listOf(null, listOf(20)), requests)
        assertFalse(job.isCompleted)
        native.complete(PagedResult.Success(emptyList(), 1, false))
        assertFalse(job.await().hasMore)
    }

    @Test fun discoveryFreeInteractionFillsBothFetchSlots() = runTest {
        val slow = CompletableDeferred<PagedResult<Int>>()
        val requests = mutableListOf<String>()
        val received = mutableListOf<Int>()
        var slowAttempts = 0
        val session = SupplementalSearchSession("中文", MediaKind.ANIME, catalog, listOf(10), { it: Int -> it },
            discover = { page(total = 2, hits = arrayOf(
                SubjectSearchHit(1, "slow", "中文"),
                SubjectSearchHit(2, "ready", "中文"),
            )) },
            fetch = { text, _, p ->
                requests += text
                when (text) {
                    "中文" -> PagedResult.Success(emptyList(), p, false)
                    "slow" -> if (slowAttempts++ == 0) PagedResult.Error("first failure") else slow.await()
                    else -> PagedResult.Success(listOf(2), p, false)
                }
            })

        assertTrue(session.loadMore { }.failed)
        assertEquals(2, requests.size)

        val job = async { session.loadMore { received += it } }
        repeat(10) { runCurrent() }

        assertEquals(listOf(2), received)
        assertEquals(listOf("ready", "slow"), requests.drop(2))
        assertFalse(job.isCompleted)
        slow.complete(PagedResult.Error("offline"))
        val progress = job.await()
        assertTrue(progress.failed)
        assertTrue(progress.hasMore)
    }

    @Test fun twoFailedCursorsRotateBehindTheThirdPendingSource() = runTest {
        val requests = mutableListOf<String>()
        val session = SupplementalSearchSession("中文", MediaKind.ANIME, catalog, emptyList(), { it: Int -> it },
            discover = { page(total = 3, hits = arrayOf(
                SubjectSearchHit(1, "one", "中文"),
                SubjectSearchHit(2, "two", "中文"),
                SubjectSearchHit(3, "three", "中文"),
            )) },
            fetch = { text, _, _ -> requests += text; PagedResult.Error("offline") })

        val first = session.loadMore { }
        assertTrue(first.failed)
        assertEquals(listOf("one", "two"), requests)

        val second = session.loadMore { }
        assertTrue(second.failed)
        assertEquals(4, requests.size)
        assertTrue(requests.drop(2).contains("three"))
    }

    @Test fun explicitLinksAndOriginalNamesStayDifferentSources() = runTest {
        val requests = mutableListOf<Pair<String, List<Int>?>>()
        val received = mutableListOf<Int>()
        val session = SupplementalSearchSession("中文", MediaKind.ANIME, catalog, emptyList(), { it: Int -> it },
            discover = { page(total = 3,
                hits = arrayOf(SubjectSearchHit(42, "mapped", "中文"), SubjectSearchHit(43, "Original", "中文"), SubjectSearchHit(44, "Original", "中文"))) },
            fetch = { text, ids, p ->
                requests += text to ids
                PagedResult.Success(if (ids != null) listOf(20) else listOf(20, 30), p, false)
            })
        val result = session.loadMore { received += it }
        assertFalse(result.hasMore)
        assertEquals(listOf("" to listOf(20), "Original" to null), requests)
        assertEquals(listOf(20, 30), received)
        assertNull(catalog.mediaForBangumi(MediaKind.MANGA, 42))
    }

    @Test fun failedNameRemainsRetryableWhileOtherNamesProceed() = runTest {
        var failFirst = true
        val requests = mutableListOf<String>()
        val received = mutableListOf<Int>()
        val session = SupplementalSearchSession("中文", MediaKind.MANGA, catalog, emptyList(), { it: Int -> it },
            discover = { page(total = 2, hits = arrayOf(SubjectSearchHit(1, "one", ""), SubjectSearchHit(2, "two", ""))) },
            fetch = { text, _, p ->
                requests += text
                if (text == "one" && failFirst) PagedResult.Error("offline")
                else PagedResult.Success(listOf(if (text == "one") 1 else 2), p, false)
            })
        val first = session.loadMore { received += it }
        assertTrue(first.failed)
        assertTrue(first.hasMore)
        assertEquals(listOf(2), received)
        failFirst = false
        val retried = session.loadMore { received += it }
        assertFalse(retried.failed)
        assertFalse(retried.hasMore)
        assertEquals(listOf("one", "two", "one"), requests)
        assertEquals(listOf(2, 1), received)
    }

    @Test fun partialLocalHitsStillDiscoverAndDoNotRequestKnownMappedIds() = runTest {
        val requests = mutableListOf<String>()
        val session = SupplementalSearchSession("中文", MediaKind.ANIME, catalog, listOf(20), { it: Int -> it },
            discover = { page(total = 1, hits = arrayOf(SubjectSearchHit(42, "mapped", ""))) },
            fetch = { text, ids, p -> requests += text; assertNull(ids); PagedResult.Success(emptyList(), p, false) })
        assertFalse(session.loadMore { }.hasMore)
        assertEquals(listOf("中文"), requests)
    }

    @Test fun rateLimitAndRepeatedDiscoveryPagesAreNotSuccessfulExhaustion() = runTest {
        var attempt = 0
        val session = SupplementalSearchSession("中文", MediaKind.ANIME, catalog, emptyList(), { it: Int -> it },
            discover = { offset ->
                if (attempt++ == 0) SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, 2000)
                else page(offset, 3, SubjectSearchHit(1, "", "中文"))
            }, fetch = { _, _, p -> PagedResult.Success(emptyList(), p, false) })
        val limited = session.loadMore { }
        assertTrue(limited.failed)
        assertEquals(2000L, limited.retryAfterMillis)
        assertTrue(session.loadMore { }.hasMore)
        val repeated = session.loadMore { }
        assertTrue(repeated.failed)
        assertTrue(repeated.hasMore)
        assertEquals(1, repeated.unmappedWithoutName)
    }

    @Test fun missingCurrentPageKeepsMappedCursorForRetry() = runTest {
        val requests = mutableListOf<Int>()
        var attempt = 0
        val session = SupplementalSearchSession("中文", MediaKind.ANIME, catalog, emptyList(), { it: Int -> it },
            discover = { page(total = 1, hits = arrayOf(SubjectSearchHit(42, "mapped", "中文"))) },
            fetch = { _, ids, p ->
                assertNotNull(ids)
                requests += p
                when (attempt++) {
                    0 -> PagedResult.Success(emptyList(), null, false)
                    1 -> PagedResult.Success(emptyList(), p + 1, false)
                    else -> PagedResult.Success(listOf(20), p, false)
                }
            })

        assertTrue(session.loadMore { }.failed)
        assertTrue(session.loadMore { }.failed)
        val received = mutableListOf<Int>()
        val completed = session.loadMore { received += it }
        assertFalse(completed.failed)
        assertFalse(completed.hasMore)
        assertEquals(listOf(20), received)
        assertEquals(listOf(1, 1, 1), requests)
    }

    @Test fun mappedIdFallbackIsReportedIndependently() = runTest {
        val session = SupplementalSearchSession("中文", MediaKind.ANIME, catalog, emptyList(), { it: Int -> it },
            discover = { page(total = 1, hits = arrayOf(SubjectSearchHit(42, "mapped", "中文"))) },
            fetch = { _, ids, p -> PagedResult.Success(listOf(20), p, false) },
            mappedIdsUsePopularityFallback = true)
        val progress = session.loadMore { }
        assertTrue(progress.mappedIdsUsePopularityFallback)
    }
}
