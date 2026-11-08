package com.axiel7.anihyou.core.domain.repository

import com.axiel7.anihyou.core.localization.EntityKey
import com.axiel7.anihyou.core.localization.EntityKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalizationCatalogRepositoryTest {
    private val json = """{"schemaVersion":1,"version":"v1","sources":[{"id":"s","revision":"r","url":"https://example.org","license":"CC0","sha256":"${"a".repeat(64)}"}],"records":[{"key":{"platform":"ANILIST","kind":"MEDIA","id":20},"displayName":"火影忍者","aliases":[],"sourceId":"s","mediaKind":"ANIME"}]}"""

    @Test fun concurrentLoadsPublishOnceAndDisplayDoesNotChangeRawValue() = runBlocking {
        val calls = AtomicInteger()
        val repository = LocalizationCatalogRepository({ calls.incrementAndGet(); json }, Dispatchers.Default)
        (1..12).map { async { repository.load() } }.awaitAll()
        assertEquals(1, calls.get())
        val raw = "Naruto"
        val key = EntityKey(EntityKind.MEDIA, 20)
        assertEquals("火影忍者", repository.snapshot.value.displayName(key, raw, true))
        assertEquals(raw, repository.snapshot.value.displayName(key, raw, false))
        assertEquals("Naruto", raw)
    }

    @Test fun bundledResourceIsAvailableAndValid() = runBlocking {
        val repository = LocalizationCatalogRepository({
            requireNotNull(LocalizationCatalogRepository::class.java.classLoader?.getResourceAsStream("localization/catalog.json"))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }, Dispatchers.Default)
        repository.load()
        assertNull(repository.error.value)
        assertTrue(repository.snapshot.value.recordCount > 0)
    }

    @Test fun cancellationDoesNotPublishAndLaterLoadCanRetry() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val repository = LocalizationCatalogRepository({
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            json
        }, Dispatchers.Default)
        val job = launch(Dispatchers.Default) { repository.load() }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, repository.snapshot.value.recordCount)
        assertNull(repository.error.value)
        repository.load()
        assertEquals(2, calls.get())
        assertEquals(1, repository.snapshot.value.recordCount)
    }

    @Test fun invalidInputFallsBackAndCanRetry() = runBlocking {
        var input = "invalid"
        val repository = LocalizationCatalogRepository({ input }, Dispatchers.Default)
        repository.load()
        assertNotNull(repository.error.value)
        assertEquals(0, repository.snapshot.value.recordCount)
        input = json
        repository.load()
        assertNull(repository.error.value)
        assertEquals(1, repository.snapshot.value.recordCount)
    }
}
