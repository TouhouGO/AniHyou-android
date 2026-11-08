package com.axiel7.anihyou.feature.settings

import android.content.Context
import androidx.work.WorkManager
import com.axiel7.anihyou.core.domain.repository.CatalogStorage
import com.axiel7.anihyou.core.domain.repository.CatalogUpdater
import com.axiel7.anihyou.core.domain.repository.CatalogUpdateMonitor
import com.axiel7.anihyou.core.domain.repository.CatalogCheckPreferencePort
import com.axiel7.anihyou.core.domain.repository.CatalogCheckPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LocalizationCatalogRepository
import com.axiel7.anihyou.core.domain.repository.LoginRepository
import com.axiel7.anihyou.core.domain.repository.UserRepository
import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import com.axiel7.anihyou.core.network.localization.CatalogManifest
import com.axiel7.anihyou.core.network.localization.CatalogTransport
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCatalogOperationsTest {
    private val dispatcher = StandardTestDispatcher()
    private fun catalog(version: String) = """{"schemaVersion":1,"version":"$version","sources":[{"id":"s","revision":"r","url":"https://example.org","license":"CC0","sha256":"${"a".repeat(64)}"}],"records":[{"key":{"platform":"ANILIST","kind":"MEDIA","id":1},"displayName":"Name","aliases":[],"sourceId":"s","mediaKind":"ANIME"}]}""".toByteArray()
    private class MemoryStorage : CatalogStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun commit(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun delete() { bytes = null }
    }
    private inner class Transport : CatalogTransport {
        val bytes = catalog("2026.11.08")
        private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = CatalogManifest(1, 1, "2026.11.08", bytes.size.toLong(), digest, "https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v2026.11.08/catalog.json")
        var gate: CompletableDeferred<Unit>? = null
        var failure: Throwable? = null
        var checks = 0
        var downloads = 0
        override suspend fun check(): CatalogManifest? { checks++; gate?.await(); failure?.let { throw it }; return manifest }
        override suspend fun download(manifest: CatalogManifest): ByteArray { downloads++; return bytes }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun context(): Context {
        val context = mockk<Context>()
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_complete) } returns "complete"
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_stale) } returns "Resources changed; check again."
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_network_error) } returns "Check your connection and try again."
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_invalid_format) } returns "Choose a complete catalog.json file."
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_empty) } returns "This catalog has no records."
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_too_large) } returns "The catalog is too large."
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_collision) } returns "Use a catalog with a newer version."
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_invalid_manifest) } returns "The update channel returned invalid information."
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_up_to_date) } returns "up to date"
        every { context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_unavailable) } returns "unavailable"
        return context
    }

    private fun settings(repository: LocalizationCatalogRepository, transport: Transport, context: Context = context()): SettingsViewModel {
        val prefs = mockk<DefaultPreferencesRepository>(relaxed = true)
        val lists = mockk<ListPreferencesRepository>(relaxed = true)
        every { prefs.profileColor } returns emptyFlow()
        every { prefs.isLoggedIn } returns emptyFlow()
        every { prefs.theme } returns emptyFlow()
        every { prefs.useBlackColors } returns emptyFlow()
        every { prefs.appColorMode } returns emptyFlow()
        every { prefs.appColor } returns emptyFlow()
        every { prefs.colorPalette } returns emptyFlow()
        every { prefs.coloredMedia } returns emptyFlow()
        every { prefs.blurAdult } returns emptyFlow()
        every { prefs.showLowPriority } returns emptyFlow()
        every { prefs.defaultTab } returns emptyFlow()
        every { lists.useGeneralListStyle } returns emptyFlow()
        every { lists.generalListStyle } returns emptyFlow()
        every { lists.gridItemsPerRow } returns emptyFlow()
        every { prefs.airingOnMyList } returns emptyFlow()
        every { prefs.scoreFormat } returns emptyFlow()
        every { prefs.scoreSteps } returns emptyFlow()
        every { prefs.isNotificationsEnabled } returns emptyFlow()
        every { prefs.notificationCheckInterval } returns emptyFlow()
        every { prefs.translatorApp } returns emptyFlow()
        every { prefs.hideScores } returns emptyFlow()
        every { prefs.chineseDescriptions } returns emptyFlow()
        every { prefs.chineseTitles } returns emptyFlow()
        every { prefs.chinesePeople } returns emptyFlow()
        every { prefs.chineseTags } returns emptyFlow()
        every { prefs.useFuzzySearch } returns emptyFlow()
        every { prefs.separateNovelsAndManga } returns emptyFlow()
        val store = object : CatalogCheckPreferencePort {
            var value = CatalogCheckPreferences()
            override suspend fun read() = value
            override suspend fun write(value: CatalogCheckPreferences) { this.value = value }
        }
        val monitor = CatalogUpdateMonitor(CatalogUpdater(repository, transport), repository, store, CoroutineScope(SupervisorJob() + dispatcher))
        return SettingsViewModel(prefs, lists, mockk<UserRepository>(relaxed = true), mockk<LoginRepository>(relaxed = true),
            mockk<WorkManager>(relaxed = true), repository, monitor, context)
    }
    private fun repository(storage: MemoryStorage) = LocalizationCatalogRepository({ catalog("2026.11.07").toString(Charsets.UTF_8) }, dispatcher, storage)

    @Test fun busyPreventsOverlappingActionsAndOfferDownloads() = runTest {
        val storage = MemoryStorage(); val repository = repository(storage); val transport = Transport()
        val gate = CompletableDeferred<Unit>(); transport.gate = gate
        val viewModel = settings(repository, transport); runCurrent()
        assertEquals(1, viewModel.uiState.value.catalogRecordCount)
        viewModel.checkCatalog(); runCurrent()
        assertTrue(viewModel.uiState.value.catalogBusy)
        viewModel.checkCatalog(); viewModel.resetCatalog(); viewModel.downloadCatalog(); runCurrent()
        assertEquals(1, transport.checks)
        assertEquals(0, transport.downloads)
        assertEquals("2026.11.07", repository.state.value.status.version)
        gate.complete(Unit); runCurrent()
        assertEquals("2026.11.08", viewModel.uiState.value.catalogOfferVersion)
        assertFalse(viewModel.uiState.value.catalogBusy)
        viewModel.downloadCatalog(); runCurrent()
        assertEquals(1, transport.downloads)
        assertArrayEquals(transport.bytes, storage.bytes)
        assertEquals("2026.11.08", viewModel.uiState.value.catalogVersion)
        assertEquals(1, viewModel.uiState.value.catalogRecordCount)
        assertNull(viewModel.uiState.value.catalogOfferVersion)
    }

    @Test fun staleOfferAndCheckFailurePreserveWorkingCatalogAndShowError() = runTest {
        val storage = MemoryStorage(); val repository = repository(storage); val transport = Transport()
        val viewModel = settings(repository, transport); runCurrent()
        viewModel.checkCatalog(); runCurrent()
        viewModel.resetCatalog(); runCurrent()
        assertEquals("2026.11.07", repository.state.value.status.version)
        assertNull(storage.bytes)
        assertNull(viewModel.uiState.value.catalogOfferVersion)
        assertFalse(viewModel.uiState.value.catalogBusy)
        transport.failure = IOException("offline")
        viewModel.checkCatalog(); runCurrent()
        assertNull(viewModel.uiState.value.catalogOfferVersion)
        assertEquals("Check your connection and try again.", viewModel.uiState.value.catalogMessage)
        assertEquals("2026.11.07", viewModel.uiState.value.catalogVersion)
        assertNull(storage.bytes)
        transport.failure = CatalogFailureException(CatalogFailureCode.INVALID_FORMAT, IllegalArgumentException("Unsupported catalog schema"))
        viewModel.checkCatalog(); runCurrent()
        assertEquals("Choose a complete catalog.json file.", viewModel.uiState.value.catalogMessage)
        assertFalse(viewModel.uiState.value.catalogMessage!!.contains("Unsupported"))
        transport.failure = CatalogFailureException(CatalogFailureCode.EMPTY_CATALOG, IllegalArgumentException("records=0"))
        viewModel.checkCatalog(); runCurrent()
        assertEquals("This catalog has no records.", viewModel.uiState.value.catalogMessage)
        assertFalse(viewModel.uiState.value.catalogMessage!!.contains("records=0"))
        transport.failure = CatalogFailureException(CatalogFailureCode.FILE_TOO_LARGE)
        viewModel.checkCatalog(); runCurrent()
        assertEquals("The catalog is too large.", viewModel.uiState.value.catalogMessage)
        transport.failure = CatalogFailureException(CatalogFailureCode.VERSION_COLLISION)
        viewModel.checkCatalog(); runCurrent()
        assertEquals("Use a catalog with a newer version.", viewModel.uiState.value.catalogMessage)
        transport.failure = CatalogFailureException(CatalogFailureCode.INVALID_MANIFEST)
        viewModel.checkCatalog(); runCurrent()
        assertEquals("The update channel returned invalid information.", viewModel.uiState.value.catalogMessage)
    }
}
