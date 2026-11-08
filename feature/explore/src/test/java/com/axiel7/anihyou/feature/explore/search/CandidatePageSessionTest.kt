package com.axiel7.anihyou.feature.explore.search

import com.axiel7.anihyou.core.base.PagedResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CandidatePageSessionTest {
    @Test fun fiveHundredOneCandidatesRemainOneServerSortedPool() = runTest {
        val ids = (1..501).toList()
        val calls = mutableListOf<Pair<List<Int>, Int>>()
        val pager = CandidatePageSession(ids, { it }) { pool, page, size ->
            calls += pool to page
            val rows = pool.reversed().drop((page - 1) * size).take(size)
            PagedResult.Success(rows, page, page * size < pool.size)
        }
        val received = mutableListOf<Int>()
        do {
            val result = pager.next() as PagedResult.Success
            received += result.list
            pager.acknowledge()
        } while (result.hasNextPage)
        assertFalse(pager.isPartitioned)
        assertEquals(ids.reversed(), received)
        assertTrue(calls.all { it.first == ids })
        assertEquals((1..21).toList(), calls.map { it.second })
    }

    @Test fun filteredEmptyPartitionAdvancesToTheTail() = runTest {
        val calls = mutableListOf<List<Int>>()
        val pager = CandidatePageSession((1..5001).toList(), { it }) { pool, page, _ ->
            calls += pool
            PagedResult.Success(pool.filter { it == 5001 }, page, false)
        }
        val result = pager.next() as PagedResult.Success
        assertEquals(listOf(5001), result.list)
        assertFalse(result.hasNextPage)
        assertEquals(listOf(5000, 1), calls.map { it.size })
        assertTrue(pager.isPartitioned)
    }

    @Test fun everyPartitionStaysWithinServerPageDepth() = runTest {
        val ids = (1..5001).toList()
        val calls = mutableListOf<Pair<Int, Int>>()
        val pager = CandidatePageSession(ids, { it }) { pool, page, size ->
            calls += pool.first() to page
            val rows = pool.drop((page - 1) * size).take(size)
            PagedResult.Success(rows, page, page * size <= pool.size)
        }
        val seen = mutableListOf<Int>()
        do {
            val result = pager.next() as PagedResult.Success
            seen += result.list
            pager.acknowledge()
        } while (result.hasNextPage)
        assertEquals(ids, seen)
        assertEquals(200, calls.maxOf { it.second })
        assertEquals(5001 to 1, calls.last())
    }

    @Test fun failuresAndCancellationRetryTheSameCursor() = runTest {
        val calls = mutableListOf<Int>()
        var attempt = 0
        val pager = CandidatePageSession(listOf(1, 2), { it }) { _, page, _ ->
            calls += page
            when (attempt++) {
                0 -> PagedResult.Error("temporary")
                1 -> throw CancellationException("cancelled")
                else -> PagedResult.Success(listOf(1, 2), page, false)
            }
        }
        assertTrue(pager.next() is PagedResult.Error)
        try { pager.next(); fail("Expected cancellation") } catch (_: CancellationException) { }
        val result = pager.next() as PagedResult.Success
        assertEquals(listOf(1, 2), result.list)
        assertEquals(listOf(1, 1, 1), calls)
    }

    @Test fun fetchedPageRemainsPreparedUntilPublished() = runTest {
        var calls = 0
        val pager = CandidatePageSession(listOf(1, 2), { it }) { _, page, _ ->
            calls++
            PagedResult.Success(listOf(1, 1, 2), page, false)
        }
        val first = pager.next()
        assertSame(first, pager.next())
        assertEquals(1, calls)
        assertEquals(listOf(1, 2), (first as PagedResult.Success).list)
        pager.acknowledge()
        assertEquals(emptyList<Int>(), (pager.next() as PagedResult.Success).list)
    }

    @Test fun brokenUpstreamPagingIsAnErrorNotFalseExhaustion() = runTest {
        val pager = CandidatePageSession(listOf(1), { it }) { _, page, _ ->
            PagedResult.Success(emptyList<Int>(), page, true)
        }
        assertTrue(pager.next() is PagedResult.Error)
        val wrongId = CandidatePageSession(listOf(1), { it }) { _, page, _ ->
            PagedResult.Success(listOf(2), page, false)
        }
        assertTrue(wrongId.next() is PagedResult.Error)
    }

    @Test fun missingOrWrongPageIsRetryableAndCannotAdvanceTheCursor() = runTest {
        val pages = mutableListOf<Int>()
        var attempt = 0
        val pager = CandidatePageSession(listOf(1), { it }) { _, page, _ ->
            pages += page
            when (attempt++) {
                0 -> PagedResult.Success(emptyList(), null, false)
                1 -> PagedResult.Success(emptyList(), page + 1, false)
                else -> PagedResult.Success(listOf(1), page, false)
            }
        }
        assertTrue(pager.next() is PagedResult.Error)
        assertTrue(pager.next() is PagedResult.Error)
        assertEquals(listOf(1), (pager.next() as PagedResult.Success).list)
        assertEquals(listOf(1, 1, 1), pages)
    }
}
