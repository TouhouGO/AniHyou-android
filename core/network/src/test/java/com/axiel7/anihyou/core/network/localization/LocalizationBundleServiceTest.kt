package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.cache.ApolloCacheManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalizationBundleServiceTest {

    private lateinit var tempDir: File
    private lateinit var bundleManager: LocalizationBundleManager
    private lateinit var fakeCacheManager: FakeApolloCacheManager
    private lateinit var configState: LocalizationConfigState
    private lateinit var titleProvider: ChineseTitleProvider
    private lateinit var tagProvider: ChineseTagProvider
    private lateinit var characterProvider: ChineseCharacterProvider
    private lateinit var descriptionProvider: ChineseDescriptionProvider
    private lateinit var invalidationCoordinator: LocalizationInvalidationCoordinator
    private lateinit var service: LocalizationBundleService

    private class FakeApolloCacheManager : ApolloCacheManager {
        var clearCount = 0
        override suspend fun clearCache() {
            clearCount++
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun createZip(files: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun createValidBundleZip(version: String = "2026.09.99"): ByteArray {
        val customTitles = """{"999999": "测试动态标题"}""".toByteArray(Charsets.UTF_8)
        val customTags = """{"TestTag": "测试标签"}""".toByteArray(Charsets.UTF_8)
        val customChars = """{"char_88888": "测试角色"}""".toByteArray(Charsets.UTF_8)
        val customT2s = """{"幹": "干"}""".toByteArray(Charsets.UTF_8)

        val manifest = BundleManifest(
            formatVersion = 1,
            version = version,
            files = mapOf(
                "titles_zh_cn.json" to BundleFileEntry(size = customTitles.size.toLong(), sha256 = sha256Hex(customTitles)),
                "tags_zh_cn.json" to BundleFileEntry(size = customTags.size.toLong(), sha256 = sha256Hex(customTags)),
                "staff_characters_zh_cn.json" to BundleFileEntry(size = customChars.size.toLong(), sha256 = sha256Hex(customChars)),
                "t2s_char_map.json" to BundleFileEntry(size = customT2s.size.toLong(), sha256 = sha256Hex(customT2s))
            )
        )
        val manifestJson = Json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

        return createZip(
            mapOf(
                "bundle_manifest.json" to manifestJson,
                "titles_zh_cn.json" to customTitles,
                "tags_zh_cn.json" to customTags,
                "staff_characters_zh_cn.json" to customChars,
                "t2s_char_map.json" to customT2s
            )
        )
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("bundle_service_test").toFile()
        bundleManager = LocalizationBundleManager()
        bundleManager.setStorageDirectory(tempDir)

        fakeCacheManager = FakeApolloCacheManager()
        configState = LocalizationConfigState()
        val converter = ChineseConverter(bundleManager)
        titleProvider = ChineseTitleProvider(bundleManager, converter)
        tagProvider = ChineseTagProvider(bundleManager)
        characterProvider = ChineseCharacterProvider(bundleManager, converter)
        descriptionProvider = ChineseDescriptionProvider(converter)

        invalidationCoordinator = LocalizationInvalidationCoordinator(
            apolloCacheManager = fakeCacheManager,
            configState = configState,
            titleProvider = titleProvider,
            tagProvider = tagProvider,
            characterProvider = characterProvider,
            descriptionProvider = descriptionProvider
        )

        service = LocalizationBundleService(bundleManager, invalidationCoordinator)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testInitialBundleStatusReflectsBundleManager() {
        val status = service.bundleStatus.value
        assertEquals(bundleManager.getCurrentVersion(), status.currentVersion)
        assertEquals(bundleManager.isOverlayActive(), status.isOverlayActive)
        assertFalse(status.isOverlayActive)
        assertEquals(0, fakeCacheManager.clearCount)
    }

    @Test
    fun testInstallBundleFromZipSuccessUpdatesBundleStatusAndClearsCache() = runBlocking {
        val targetVersion = "2026.09.99"
        val zipBytes = createValidBundleZip(targetVersion)

        val result = service.installBundleFromZip(ByteArrayInputStream(zipBytes), targetVersion)

        assertTrue("Install should succeed", result.isSuccess)
        assertEquals(targetVersion, result.installedVersion)
        assertEquals(targetVersion, service.bundleStatus.value.currentVersion)
        assertTrue(service.bundleStatus.value.isOverlayActive)
        assertEquals(1, fakeCacheManager.clearCount)
        assertEquals(LocalizationChangeReason.BUNDLE_INSTALL, configState.snapshot.value.lastChangeReason)
    }

    @Test
    fun testInstallBundleFromZipFailureLeavesBundleStatusUnchanged() = runBlocking {
        val initialStatus = service.bundleStatus.value
        val corruptBytes = "not a valid zip file".toByteArray(Charsets.UTF_8)

        val result = service.installBundleFromZip(ByteArrayInputStream(corruptBytes))

        assertFalse("Install should fail", result.isSuccess)
        assertEquals(initialStatus.currentVersion, service.bundleStatus.value.currentVersion)
        assertEquals(initialStatus.isOverlayActive, service.bundleStatus.value.isOverlayActive)
        assertEquals(0, fakeCacheManager.clearCount)
    }

    @Test
    fun testResetToBuiltInTurnsOverlayOffAndClearsCache() = runBlocking {
        val targetVersion = "2026.09.99"
        val zipBytes = createValidBundleZip(targetVersion)
        val installResult = service.installBundleFromZip(ByteArrayInputStream(zipBytes), targetVersion)
        assertTrue(installResult.isSuccess)
        assertTrue(service.bundleStatus.value.isOverlayActive)
        assertEquals(1, fakeCacheManager.clearCount)

        val resetResult = service.resetToBuiltIn()

        assertTrue("Reset should succeed", resetResult)
        assertFalse("Overlay should become false", service.bundleStatus.value.isOverlayActive)
        assertEquals(2, fakeCacheManager.clearCount)
        assertEquals(LocalizationChangeReason.BUNDLE_RESET, configState.snapshot.value.lastChangeReason)
    }

    @Test
    fun testBundleStatusStateFlowAutoUpdatesOnNotifyReload() {
        assertFalse(service.bundleStatus.value.isOverlayActive)

        val targetVersion = "2026.09.99"
        val zipBytes = createValidBundleZip(targetVersion)
        val installResult = bundleManager.installBundleFromZip(ByteArrayInputStream(zipBytes), targetVersion)
        assertTrue(installResult.isSuccess)

        // bundleManager.installBundleFromZip triggers notifyReload() internally
        assertEquals(targetVersion, service.bundleStatus.value.currentVersion)
        assertTrue(service.bundleStatus.value.isOverlayActive)

        bundleManager.resetToBuiltIn()
        assertFalse(service.bundleStatus.value.isOverlayActive)
    }

    @Test
    fun testGetCurrentVersionAndIsOverlayActiveDelegation() = runBlocking {
        assertEquals(bundleManager.getCurrentVersion(), service.getCurrentVersion())
        assertEquals(bundleManager.isOverlayActive(), service.isOverlayActive())

        val targetVersion = "2026.09.99"
        val zipBytes = createValidBundleZip(targetVersion)
        service.installBundleFromZip(ByteArrayInputStream(zipBytes), targetVersion)

        assertEquals(bundleManager.getCurrentVersion(), service.getCurrentVersion())
        assertEquals(bundleManager.isOverlayActive(), service.isOverlayActive())
        assertEquals(targetVersion, service.getCurrentVersion())
        assertTrue(service.isOverlayActive())

        service.resetToBuiltIn()

        assertEquals(bundleManager.getCurrentVersion(), service.getCurrentVersion())
        assertEquals(bundleManager.isOverlayActive(), service.isOverlayActive())
        assertFalse(service.isOverlayActive())
    }
}
