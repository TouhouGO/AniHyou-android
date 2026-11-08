package com.axiel7.anihyou.core.domain.repository

import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import com.axiel7.anihyou.core.network.localization.CatalogManifest
import com.axiel7.anihyou.core.network.localization.CatalogTransport
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogUpdaterTest {
    private fun catalog(version: String) = """{"schemaVersion":1,"version":"$version","sources":[{"id":"s","revision":"r","url":"https://example.org","license":"CC0","sha256":"${"a".repeat(64)}"}],"records":[{"key":{"platform":"ANILIST","kind":"MEDIA","id":1},"displayName":"Name","aliases":[],"sourceId":"s","mediaKind":"ANIME"}]}""".toByteArray()
    private class MemoryStorage : CatalogStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun commit(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun delete() { bytes = null }
    }
    private suspend fun failureCode(action: suspend () -> Unit): CatalogFailureCode = try {
        action(); throw AssertionError("Expected failure")
    } catch (e: CatalogFailureException) { e.code }
    private fun repository() = LocalizationCatalogRepository(
        { catalog("2026.11.07").toString(Charsets.UTF_8) },
        Dispatchers.Default,
        MemoryStorage(),
    )
    @Test fun offerDownloadsVerifiesAndPublishes() = runBlocking {
        val bytes = catalog("2026.11.08")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = CatalogManifest(1, 1, "2026.11.08", bytes.size.toLong(), digest, "https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v2026.11.08/catalog.json")
        val transport = object : CatalogTransport {
            override suspend fun check() = manifest
            override suspend fun download(manifest: CatalogManifest) = bytes
        }
        val storage = MemoryStorage()
        val repository = LocalizationCatalogRepository({ catalog("2026.11.07").toString(Charsets.UTF_8) }, Dispatchers.Default, storage)
        val updater = CatalogUpdater(repository, transport)
        val offer = updater.check() as CatalogCheck.Available
        updater.download(offer)
        assertArrayEquals(bytes, storage.bytes)
        assertEquals("2026.11.08", repository.state.value.status.version)
        assertEquals(CatalogCheck.UpToDate, updater.check())
    }
    @Test fun unavailableDoesNotBecomeUpToDate() = runBlocking {
        val transport = object : CatalogTransport {
            override suspend fun check(): CatalogManifest? = null
            override suspend fun download(manifest: CatalogManifest): ByteArray = error("not offered")
        }
        val repository = LocalizationCatalogRepository({ catalog("2026.11.07").toString(Charsets.UTF_8) }, Dispatchers.Default, MemoryStorage())
        assertEquals(CatalogCheck.Unavailable, CatalogUpdater(repository, transport).check())
    }
    @Test fun sameVersionDifferentDigestUsesTypedCollision() = runBlocking {
        val manifest = CatalogManifest(1, 1, "2026.11.07", 1, "b".repeat(64), "https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v2026.11.07/catalog.json")
        val transport = object : CatalogTransport {
            override suspend fun check() = manifest
            override suspend fun download(manifest: CatalogManifest): ByteArray = error("not offered")
        }
        val updater = CatalogUpdater(repository(), transport)
        assertEquals(CatalogFailureCode.VERSION_COLLISION, failureCode { updater.check() })
    }
    @Test fun actualTransportIOExceptionRemainsNetworkFailure() = runBlocking {
        val transport = object : CatalogTransport {
            override suspend fun check(): CatalogManifest? = throw IOException("offline")
            override suspend fun download(manifest: CatalogManifest): ByteArray = throw IOException("offline")
        }
        val updater = CatalogUpdater(repository(), transport)
        assertEquals(CatalogFailureCode.NETWORK_FAILURE, failureCode { updater.check() })
    }
}
