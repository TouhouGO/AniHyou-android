package com.axiel7.anihyou.core.domain.repository

import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class CatalogLifecycleTest {
    private fun catalog(version: String, name: String = "Name") = """{"schemaVersion":1,"version":"$version","sources":[{"id":"s","revision":"r","url":"https://example.org","license":"CC0","sha256":"${"a".repeat(64)}"}],"records":[{"key":{"platform":"ANILIST","kind":"MEDIA","id":1},"displayName":"$name","aliases":[],"sourceId":"s","mediaKind":"ANIME"}]}""".toByteArray()
    private class Storage : CatalogStorage {
        var data: ByteArray? = null
        var failCommit = false
        var failDelete = false
        override fun read() = data
        override fun commit(bytes: ByteArray) { if (failCommit) throw IOException("commit"); data = bytes.copyOf() }
        override fun delete() { if (failDelete) throw IOException("delete"); data = null }
    }
    private fun repo(storage: Storage) = LocalizationCatalogRepository({ catalog("2026.11.07").toString(Charsets.UTF_8) }, Dispatchers.Default, storage)
    private suspend fun failureCode(action: suspend () -> Unit): CatalogFailureCode = try {
        action(); throw AssertionError("Expected failure")
    } catch (e: CatalogFailureException) { e.code }
    private suspend fun fails(action: suspend () -> Unit) { failureCode(action) }
    private fun emptyCatalog(version: String) = """{"schemaVersion":1,"version":"$version","sources":[],"records":[]}""".toByteArray()

    @Test fun rejectsCollisionDowngradeMalformedAndWrongHash() = runBlocking {
        val storage = Storage(); val repository = repo(storage); repository.load()
        fails { repository.install(catalog("2026.11.07", "Different")) }
        fails { repository.install(catalog("2026.11.06")) }
        fails { repository.install(byteArrayOf(0xc3.toByte(), 0x28)) }
        fails { repository.install(ByteArray(MAX_CATALOG_BYTES + 1)) }
        fails { repository.install(catalog("2026.02.30")) }
        val next = catalog("2026.11.08")
        fails { repository.install(next, CatalogExpected("2026.11.08", next.size.toLong(), "0".repeat(64))) }
        repository.install(catalog("2026.11.07"))
        assertNull(repository.error.value)
        assertNull(repository.state.value.status.error)
        assertNull(storage.data)
        assertEquals("2026.11.07", repository.snapshot.value.version)
    }
    @Test fun failedStorageAndStaleDownloadPreserveWorkingSnapshot() = runBlocking {
        val storage = Storage(); val repository = repo(storage); repository.load()
        val token = repository.captureGeneration()
        storage.failCommit = true
        fails { repository.install(catalog("2026.11.08")) }
        assertEquals("2026.11.07", repository.snapshot.value.version)
        storage.failCommit = false
        repository.install(catalog("2026.11.08"))
        storage.failDelete = true
        fails { repository.reset() }
        assertEquals("2026.11.08", repository.snapshot.value.version)
        storage.failDelete = false
        repository.reset()
        fails { repository.install(catalog("2026.11.09"), token = token) }
        assertEquals("2026.11.07", repository.snapshot.value.version)
        assertNull(storage.data)
    }
    @Test fun rejectsEmptyAndYearZeroDatedCatalogsButInstallsSmallNonEmptyCatalog() = runBlocking {
        val storage = Storage(); val repository = repo(storage); repository.load()
        assertEquals(CatalogFailureCode.EMPTY_CATALOG, failureCode { repository.install(emptyCatalog("2026.11.08")) })
        assertEquals(CatalogFailureCode.INVALID_VERSION, failureCode { repository.install(catalog("0000.01.01")) })
        repository.install(catalog("2026.11.08"))
        assertEquals(1, repository.state.value.status.recordCount)
        assertArrayEquals(catalog("2026.11.08"), storage.data)
    }
    @Test fun installedEmptyCatalogFallsBackWithoutDeletingEvidence() = runBlocking {
        val evidence = emptyCatalog("2026.11.08")
        val storage = Storage().apply { data = evidence }
        val repository = repo(storage); repository.load()
        assertEquals("2026.11.07", repository.snapshot.value.version)
        assertEquals(CatalogFailureCode.EMPTY_CATALOG, (repository.error.value as CatalogFailureException).code)
        assertArrayEquals(evidence, storage.data)
    }
    @Test fun placeholderTagRawKeyIsRejectedBeforeCommit() = runBlocking {
        val storage = Storage(); val repository = repo(storage); repository.load()
        val placeholder = catalog("2026.11.08").toString(Charsets.UTF_8).replace(
            "\"records\":",
            "\"tags\":[{\"rawName\":\"{{Action}}\",\"displayName\":\"动作\",\"sourceId\":\"s\"}],\"records\":",
        ).toByteArray()
        assertEquals(CatalogFailureCode.INVALID_FORMAT, failureCode { repository.install(placeholder) })
        assertNull(storage.data)
        assertEquals("2026.11.07", repository.snapshot.value.version)
    }
    @Test fun corruptOverlayFallsBackWithoutDeletingEvidence() = runBlocking {
        val storage = Storage().apply { data = "broken".toByteArray() }
        val repository = repo(storage); repository.load()
        assertEquals("2026.11.07", repository.snapshot.value.version)
        assertNotNull(repository.error.value)
        assertArrayEquals("broken".toByteArray(), storage.data)
    }
    @Test fun publicationWaitsForStorageCommit() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = object : CatalogStorage {
            override fun read(): ByteArray? = null
            override fun commit(bytes: ByteArray) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            override fun delete() = Unit
        }
        val repository = LocalizationCatalogRepository({ catalog("2026.11.07").toString(Charsets.UTF_8) }, Dispatchers.Default, storage)
        repository.load()
        val install = async(Dispatchers.Default) { repository.install(catalog("2026.11.08")) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals("2026.11.07", repository.snapshot.value.version)
        } finally { release.countDown() }
        install.await()
        assertEquals("2026.11.08", repository.snapshot.value.version)
    }

    @Test fun cancellationDuringCommitStillPublishesCommittedData() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var stored: ByteArray? = null
        val storage = object : CatalogStorage {
            override fun read(): ByteArray? = stored
            override fun commit(bytes: ByteArray) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); stored = bytes }
            override fun delete() { stored = null }
        }
        val repository = LocalizationCatalogRepository({ catalog("2026.11.07").toString(Charsets.UTF_8) }, Dispatchers.Default, storage)
        repository.load()
        val job = launch(Dispatchers.Default) { repository.install(catalog("2026.11.08")) }
        try { assertTrue(entered.await(10, TimeUnit.SECONDS)); job.cancel() } finally { release.countDown() }
        job.cancelAndJoin()
        assertArrayEquals(catalog("2026.11.08"), stored)
        assertEquals("2026.11.08", repository.state.value.status.version)
        assertTrue(repository.state.value.status.installed)
    }

    @Test fun cancellationWhileReadingOverlayDoesNotPublishFallback() = runBlocking {
        val storage = object : CatalogStorage {
            override fun read(): ByteArray? = throw CancellationException("cancelled")
            override fun commit(bytes: ByteArray) = Unit
            override fun delete() = Unit
        }
        val repository = LocalizationCatalogRepository({ catalog("2026.11.07").toString(Charsets.UTF_8) }, Dispatchers.Default, storage)
        try { repository.load(); fail("Expected cancellation") } catch (_: CancellationException) {}
        assertEquals("", repository.snapshot.value.version)
        assertNull(repository.error.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationBeforeValidationDoesNotCommitOrPublish() = runTest {
        val storage = Storage()
        val io = StandardTestDispatcher(testScheduler, name = "catalog-io")
        val repository = LocalizationCatalogRepository({ catalog("2026.11.07").toString(Charsets.UTF_8) }, io, storage)
        val loading = async { repository.load() }
        testScheduler.advanceUntilIdle()
        loading.await()
        val install = launch(start = CoroutineStart.UNDISPATCHED) { repository.install(catalog("2026.11.08")) }
        install.cancelAndJoin()
        testScheduler.advanceUntilIdle()
        assertNull(storage.data)
        assertEquals("2026.11.07", repository.state.value.status.version)
        assertFalse(repository.state.value.status.installed)
        assertNull(repository.error.value)
    }

    @Test fun commitVerificationRejectsMissingSizeAndByteMismatch() {
        val dir = Files.createTempDirectory("catalog-verify").toFile()
        try {
            val file = java.io.File(dir, "catalog.json")
            val expected = catalog("2026.11.07")
            try { verifyExactFile(file, expected); fail("Expected missing commit failure") } catch (_: IOException) {}
            file.writeBytes(expected.copyOf(expected.size - 1))
            try { verifyExactFile(file, expected); fail("Expected size mismatch") } catch (_: IOException) {}
            file.writeBytes(expected.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
            try { verifyExactFile(file, expected); fail("Expected digest mismatch") } catch (_: IOException) {}
            file.writeBytes(expected)
            verifyExactFile(file, expected)
        } finally { dir.deleteRecursively() }
    }

}
