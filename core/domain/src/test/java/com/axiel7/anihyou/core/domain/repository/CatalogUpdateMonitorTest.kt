package com.axiel7.anihyou.core.domain.repository

import com.axiel7.anihyou.core.network.localization.CatalogManifest
import com.axiel7.anihyou.core.network.localization.CatalogTransport
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CatalogUpdateMonitorTest {
    private fun bytes(v: String) = """{"schemaVersion":1,"version":"$v","sources":[{"id":"s","revision":"r","url":"https://example.org","license":"CC0","sha256":"${"a".repeat(64)}"}],"records":[{"key":{"platform":"ANILIST","kind":"MEDIA","id":1},"displayName":"Name","aliases":[],"sourceId":"s","mediaKind":"ANIME"}]}""".toByteArray()
    private class Store : CatalogCheckPreferencePort {
        var value = CatalogCheckPreferences()
        var suspendWrites = false
        override suspend fun read() = value
        override suspend fun write(value: CatalogCheckPreferences) { if (suspendWrites) yield(); this.value = value }
    }
    private inner class Fixture(scope: CoroutineScope) {
        val managedScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        var clock = 1_000_000_000L
        val store = Store()
        var calls = 0
        var downloads = 0
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var unavailable = false
        val payload = bytes("2026.11.08")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val manifest = CatalogManifest(1, 1, "2026.11.08", payload.size.toLong(), digest, "https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v2026.11.08/catalog.json")
        val storage = object : CatalogStorage { var contents: ByteArray? = null; override fun read() = contents; override fun commit(bytes: ByteArray) { contents = bytes }; override fun delete() { contents = null } }
        val repo = LocalizationCatalogRepository({ bytes("2026.11.07").toString(Charsets.UTF_8) }, Dispatchers.Unconfined, storage)
        val transport = object : CatalogTransport {
            override suspend fun check(): CatalogManifest? { calls++; gate?.await(); if (fail) throw IOException(); return if (unavailable) null else manifest }
            override suspend fun download(manifest: CatalogManifest): ByteArray { downloads++; return payload }
        }
        val monitor = CatalogUpdateMonitor(CatalogUpdater(repo, transport), repo, store, managedScope) { clock }
    }
    @Test fun throttleRestartRollbackAndManualDownload() = runBlocking {
        val f = Fixture(this)
        assertFalse(f.monitor.state.value.loaded)
        f.monitor.check(false)
        assertEquals(1, f.calls); assertEquals(0, f.downloads)
        f.monitor.check(false); assertEquals(1, f.calls)
        val restarted = CatalogUpdateMonitor(CatalogUpdater(f.repo, f.transport), f.repo, f.store, f.managedScope) { f.clock }
        restarted.initializeForUi(); assertEquals("2026.11.08", restarted.state.value.offerVersion)
        f.clock -= 2 * CatalogUpdateMonitor.DAY
        restarted.check(false); assertEquals(f.clock + CatalogUpdateMonitor.HOUR, f.store.value.nextAttempt)
        f.clock += CatalogUpdateMonitor.HOUR
        restarted.check(false); assertEquals(2, f.calls)
        restarted.download(); assertEquals(3, f.calls); assertEquals(1, f.downloads)
        assertNull(restarted.state.value.offerVersion)
    }
    @Test fun failureCooldownAndDisabledManualBypass() = runBlocking {
        val f = Fixture(this); f.fail = true
        try { f.monitor.check(false); fail() } catch (_: Exception) { }
        f.monitor.check(false); assertEquals(1, f.calls)
        f.clock += CatalogUpdateMonitor.HOUR; f.fail = false
        f.monitor.setEnabled(false); f.monitor.check(false); assertEquals(1, f.calls)
        f.monitor.check(true); assertEquals(2, f.calls)
    }
    @Test fun unavailableUsesShortCooldownAndAttemptIsPersistedBeforeResponse() = runBlocking {
        val f = Fixture(this); f.unavailable = true; f.gate = CompletableDeferred()
        val pending = async { f.monitor.check(false) }; yield()
        assertEquals(f.clock, f.store.value.lastAttempt)
        assertEquals(f.clock + CatalogUpdateMonitor.HOUR, f.store.value.nextAttempt)
        f.gate!!.complete(Unit); pending.await()
        f.monitor.check(false); assertEquals(1, f.calls)
        f.clock += CatalogUpdateMonitor.HOUR; f.monitor.check(false); assertEquals(2, f.calls)
    }
    @Test fun coalescesAndDisableCancelsAndSuppressesOffer() = runBlocking {
        val f = Fixture(this); f.gate = CompletableDeferred()
        val auto = async { f.monitor.check(false) }; yield()
        val manual = async { f.monitor.check(true) }; yield()
        assertEquals(1, f.calls)
        f.monitor.setEnabled(false); f.gate!!.complete(Unit)
        try { auto.await() } catch (_: CancellationException) { }
        try { manual.await() } catch (_: CancellationException) { }
        assertNull(f.monitor.state.value.offerVersion); assertEquals(0, f.downloads)
    }
    @Test fun invalidationPreventsLateOffer() = runBlocking {
        val f = Fixture(this); f.gate = CompletableDeferred()
        val check = async { f.monitor.check(false) }; yield()
        f.monitor.invalidate(); f.repo.reset(); f.gate!!.complete(Unit)
        try { check.await() } catch (_: CancellationException) { }
        assertNull(f.monitor.state.value.offerVersion)
        assertNull(f.store.value.offerVersion)
    }
    @Test fun cancelledDuringCommitClearsInstalledOfferAndPropagatesCancellation() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val payload = bytes("2026.11.08")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val manifest = CatalogManifest(1, 1, "2026.11.08", payload.size.toLong(), digest, "https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v2026.11.08/catalog.json")
        val storage = object : CatalogStorage {
            var contents: ByteArray? = null
            override fun read() = contents
            override fun commit(bytes: ByteArray) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                contents = bytes.copyOf()
            }
            override fun delete() { contents = null }
        }
        val repository = LocalizationCatalogRepository({ bytes("2026.11.07").toString(Charsets.UTF_8) }, Dispatchers.IO, storage)
        val transport = object : CatalogTransport {
            override suspend fun check() = manifest
            override suspend fun download(manifest: CatalogManifest) = payload
        }
        val store = Store().apply { suspendWrites = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = CatalogUpdateMonitor(CatalogUpdater(repository, transport), repository, store, scope)
        try {
            val download = async { monitor.download() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            assertEquals(manifest.version, store.value.offerVersion)
            download.cancel()
            release.countDown()
            download.join()
            assertTrue(download.isCancelled)
            try {
                download.await()
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(manifest.version, repository.state.value.status.version)
            assertArrayEquals(payload, storage.contents)
            assertNull(monitor.state.value.offerVersion)
            assertNull(store.value.offerVersion)
        } finally {
            release.countDown()
            monitor.close()
        }
    }

    @Test fun newerOfferSurvivesEarlierDownloadAndRestart() = runBlocking {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newer = f.manifest.copy(version = "2026.11.09", downloadUrl = "https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v2026.11.09/catalog.json")
        var checks = 0
        val transport = object : CatalogTransport {
            override suspend fun check(): CatalogManifest = if (checks++ == 0) f.manifest else newer
            override suspend fun download(manifest: CatalogManifest): ByteArray {
                entered.complete(Unit)
                release.await()
                return f.payload
            }
        }
        val monitor = CatalogUpdateMonitor(CatalogUpdater(f.repo, transport), f.repo, f.store, f.managedScope) { f.clock }
        val download = async { monitor.download() }
        entered.await()
        monitor.check(manual = true)
        assertEquals(newer.version, monitor.state.value.offerVersion)
        release.complete(Unit)
        download.await()
        assertEquals("2026.11.08", f.repo.state.value.status.version)
        assertEquals(newer.version, monitor.state.value.offerVersion)
        assertEquals(newer.version, f.store.value.offerVersion)
        val restarted = CatalogUpdateMonitor(CatalogUpdater(f.repo, transport), f.repo, f.store, f.managedScope) { f.clock }
        restarted.initializeForUi()
        assertEquals(newer.version, restarted.state.value.offerVersion)
    }

}
