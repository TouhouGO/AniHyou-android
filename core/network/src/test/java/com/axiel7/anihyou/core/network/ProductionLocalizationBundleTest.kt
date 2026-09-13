package com.axiel7.anihyou.core.network

import com.axiel7.anihyou.core.network.localization.BundleFileEntry
import com.axiel7.anihyou.core.network.localization.BundleManifest
import com.axiel7.anihyou.core.network.localization.ChineseTitleProvider
import com.axiel7.anihyou.core.network.localization.LocalizationBundleManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionLocalizationBundleTest {

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

    private fun readResourceBytes(fileName: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(fileName)
            ?: File("src/main/resources", fileName).inputStream()
        return stream.use { it.readBytes() }
    }

    @Test
    fun testInstallProductionResourcesAndProviderLookup() {
        val titlesBytes = readResourceBytes("titles_zh_cn.json")
        val tagsBytes = readResourceBytes("tags_zh_cn.json")
        val staffBytes = readResourceBytes("staff_characters_zh_cn.json")
        val t2sBytes = readResourceBytes("t2s_char_map.json")

        assertTrue("titles_zh_cn.json must not be empty", titlesBytes.isNotEmpty())
        assertTrue("tags_zh_cn.json must not be empty", tagsBytes.isNotEmpty())
        assertTrue("staff_characters_zh_cn.json must not be empty", staffBytes.isNotEmpty())
        assertTrue("t2s_char_map.json must not be empty", t2sBytes.isNotEmpty())

        val tempDir = Files.createTempDirectory("anihyou_production_bundle_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(tempDir)

            val manifest = BundleManifest(
                formatVersion = 1,
                version = "2099.01.01",
                description = "Production resources test bundle",
                files = mapOf(
                    "titles_zh_cn.json" to BundleFileEntry(size = titlesBytes.size.toLong(), sha256 = sha256Hex(titlesBytes)),
                    "tags_zh_cn.json" to BundleFileEntry(size = tagsBytes.size.toLong(), sha256 = sha256Hex(tagsBytes)),
                    "staff_characters_zh_cn.json" to BundleFileEntry(size = staffBytes.size.toLong(), sha256 = sha256Hex(staffBytes)),
                    "t2s_char_map.json" to BundleFileEntry(size = t2sBytes.size.toLong(), sha256 = sha256Hex(t2sBytes))
                )
            )

            val manifestBytes = Json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
            val zipBytes = createZip(
                mapOf(
                    "bundle_manifest.json" to manifestBytes,
                    "titles_zh_cn.json" to titlesBytes,
                    "tags_zh_cn.json" to tagsBytes,
                    "staff_characters_zh_cn.json" to staffBytes,
                    "t2s_char_map.json" to t2sBytes
                )
            )

            val result = bundleManager.installBundleFromZip(ByteArrayInputStream(zipBytes))
            assertTrue("Production resource bundle installation failed: ${result.message}", result.isSuccess)
            assertEquals("2099.01.01", result.installedVersion)

            // Instantiate ChineseTitleProvider against installed directory
            val titleProvider = ChineseTitleProvider(bundleManager)

            // Test numeric ID lookup (e.g. ID 1 is Cowboy Bebop / 星际牛仔)
            val idTitle = titleProvider.getTitle(1, "Cowboy Bebop")
            assertEquals("星际牛仔", idTitle)

            // AniList 169582 upstream translation from automatic dictionary
            assertEquals(
                "最强出涸皇子的暗跃帝位争夺",
                titleProvider.getTitle(169582, "最強出涸らし皇子の暗躍帝位争い")
            )

            // Test exact title-name fallback lookup (e.g. "白蛇伝" -> "白蛇传")
            val nameTitle = titleProvider.getTitle(999999999, "白蛇伝")
            assertEquals("白蛇传", nameTitle)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testInstallGeneratedProductionZipAndValidateManifests() {
        val zipFile = File("build/outputs/localization/localization_bundle.zip")
        val manifestFile = File("build/outputs/localization/remote_bundle_manifest.json")
        if (!zipFile.exists() || !manifestFile.exists()) {
            return // Skip if task hasn't produced it yet in current task graph
        }

        val manifestText = manifestFile.readText(Charsets.UTF_8)
        val manifestJson = Json.parseToJsonElement(manifestText) as kotlinx.serialization.json.JsonObject
        val expectedSha = manifestJson["archiveSha256"]?.toString()?.trim('"')
        val expectedSize = manifestJson["archiveSize"]?.toString()?.toLongOrNull()
        val expectedVer = manifestJson["version"]?.toString()?.trim('"')

        val actualSha = sha256Hex(zipFile.readBytes())
        val actualSize = zipFile.length()

        assertEquals("Remote manifest archiveSha256 must match ZIP", expectedSha, actualSha)
        assertEquals("Remote manifest archiveSize must match ZIP", expectedSize, actualSize)

        val tempDir = Files.createTempDirectory("anihyou_generated_zip_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(tempDir)

            zipFile.inputStream().use { fis ->
                val result = bundleManager.installBundleFromZip(fis, expectedVersion = expectedVer)
                assertTrue("Generated production bundle must install cleanly: ${result.message}", result.isSuccess)
                assertEquals(expectedVer, result.installedVersion)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
