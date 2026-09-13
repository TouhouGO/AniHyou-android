package com.axiel7.anihyou.core.network

import com.axiel7.anihyou.core.network.localization.BangumiEntityKind
import com.axiel7.anihyou.core.network.localization.BundleFileEntry
import com.axiel7.anihyou.core.network.localization.BundleManifest
import com.axiel7.anihyou.core.network.localization.BundleUpdateManager
import com.axiel7.anihyou.core.network.localization.ChineseCharacterProvider
import com.axiel7.anihyou.core.network.localization.ChineseConverter
import com.axiel7.anihyou.core.network.localization.ChineseTagProvider
import com.axiel7.anihyou.core.network.localization.ChineseTitleProvider
import com.axiel7.anihyou.core.network.localization.EntityNameCache
import com.axiel7.anihyou.core.network.localization.LocalizationBundleManager
import com.axiel7.anihyou.core.network.localization.RemoteBundleUpdateInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationBundleTest {

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

    @Test
    fun testBuiltInResourcesAndManifest() {
        val bundleManager = LocalizationBundleManager()
        val manifest = bundleManager.getManifest()
        assertNotNull("Built-in manifest should be loadable", manifest)
        assertTrue("Files should be defined in manifest", manifest!!.files.isNotEmpty())

        val titleProvider = ChineseTitleProvider(bundleManager)
        val tagProvider = ChineseTagProvider(bundleManager)
        val converter = ChineseConverter(bundleManager)
        val charProvider = ChineseCharacterProvider(bundleManager, converter)

        // Built-in tags should work
        assertEquals("异世界", tagProvider.getChineseTag("Isekai"))

        // Conversion should work
        assertEquals("进击的巨人", converter.toSimplified("進擊的巨人"))
    }

    @Test
    fun testZipBundleInstallAndHotReload() {
        val tempDir = Files.createTempDirectory("anihyou_bundle_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(tempDir)

            val titleProvider = ChineseTitleProvider(bundleManager)
            val tagProvider = ChineseTagProvider(bundleManager)
            val converter = ChineseConverter(bundleManager)
            val charProvider = ChineseCharacterProvider(bundleManager, converter)

            var reloaded = false
            bundleManager.registerReloadListener {
                reloaded = true
            }

            // Prepare custom files
            val customTitles = """{"999999": "测试动态标题"}""".toByteArray(Charsets.UTF_8)
            val customTags = """{"TestTag": "测试标签"}""".toByteArray(Charsets.UTF_8)
            val customChars = """{"char_88888": "测试角色"}""".toByteArray(Charsets.UTF_8)
            val customT2s = """{"幹": "干"}""".toByteArray(Charsets.UTF_8)

            val manifest = BundleManifest(
                formatVersion = 1,
                version = "2026.09.99",
                files = mapOf(
                    "titles_zh_cn.json" to BundleFileEntry(size = customTitles.size.toLong(), sha256 = sha256Hex(customTitles)),
                    "tags_zh_cn.json" to BundleFileEntry(size = customTags.size.toLong(), sha256 = sha256Hex(customTags)),
                    "staff_characters_zh_cn.json" to BundleFileEntry(size = customChars.size.toLong(), sha256 = sha256Hex(customChars)),
                    "t2s_char_map.json" to BundleFileEntry(size = customT2s.size.toLong(), sha256 = sha256Hex(customT2s))
                )
            )

            val manifestJson = Json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

            val zipBytes = createZip(
                mapOf(
                    "bundle_manifest.json" to manifestJson,
                    "titles_zh_cn.json" to customTitles,
                    "tags_zh_cn.json" to customTags,
                    "staff_characters_zh_cn.json" to customChars,
                    "t2s_char_map.json" to customT2s
                )
            )

            val result = bundleManager.installBundleFromZip(ByteArrayInputStream(zipBytes), expectedVersion = "2026.09.99")
            assertTrue("Bundle install should succeed: ${result.message}", result.isSuccess)
            assertTrue("Reload listener should have been invoked", reloaded)
            assertEquals("2026.09.99", bundleManager.getCurrentVersion())
            assertTrue("Overlay should be active", bundleManager.isOverlayActive())

            // Hot reloaded data should immediately reflect
            assertEquals("测试动态标题", titleProvider.getTitle(999999))
            assertEquals("测试标签", tagProvider.getChineseTag("TestTag"))
            assertEquals("测试角色", charProvider.getChineseCharacter(88888, "CharName"))

            // Reset back to built-in
            reloaded = false
            assertTrue("Reset should succeed", bundleManager.resetToBuiltIn())
            assertTrue("Reload listener should be invoked on reset", reloaded)
            assertFalse("Overlay should no longer be active", bundleManager.isOverlayActive())

            // Check that custom title is gone after reset
            assertEquals(null, titleProvider.getTitle(999999))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSecurityZipSlipAndPrefixBypassRejection() {
        val tempDir = Files.createTempDirectory("anihyou_zipslip_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(tempDir)

            // 1. Classic Zip Slip
            val evilZip1 = createZip(mapOf("../evil.txt" to "evil payload".toByteArray()))
            val result1 = bundleManager.installBundleFromZip(ByteArrayInputStream(evilZip1))
            assertFalse("Classic Zip slip attack should be rejected", result1.isSuccess)
            assertTrue(result1.message.contains("Security error"))

            // 2. Sibling directory prefix bypass (e.g. .next_evil)
            val evilZip2 = createZip(mapOf("../${tempDir.name}.next_evil/evil.txt" to "evil payload".toByteArray()))
            val result2 = bundleManager.installBundleFromZip(ByteArrayInputStream(evilZip2))
            assertFalse("Sibling prefix zip slip should be rejected", result2.isSuccess)
            assertTrue(result2.message.contains("Security error"))

            // 3. Non-whitelisted file
            val evilZip3 = createZip(mapOf("malicious.sh" to "rm -rf /".toByteArray()))
            val result3 = bundleManager.installBundleFromZip(ByteArrayInputStream(evilZip3))
            assertFalse("Non-whitelisted file should be rejected", result3.isSuccess)
            assertTrue(result3.message.contains("non-whitelisted entry"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testChecksumMismatchRejection() {
        val tempDir = Files.createTempDirectory("anihyou_checksum_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(tempDir)

            val customTitles = """{"999999": "测试动态标题"}""".toByteArray(Charsets.UTF_8)
            val customTags = """{"TestTag": "测试标签"}""".toByteArray(Charsets.UTF_8)
            val customChars = """{"char_88888": "测试角色"}""".toByteArray(Charsets.UTF_8)
            val customT2s = """{"幹": "干"}""".toByteArray(Charsets.UTF_8)

            val manifest = BundleManifest(
                formatVersion = 1,
                version = "2026.09.99",
                files = mapOf(
                    "titles_zh_cn.json" to BundleFileEntry(size = customTitles.size.toLong(), sha256 = "0000000000000000000000000000000000000000000000000000000000000000"),
                    "tags_zh_cn.json" to BundleFileEntry(size = customTags.size.toLong(), sha256 = sha256Hex(customTags)),
                    "staff_characters_zh_cn.json" to BundleFileEntry(size = customChars.size.toLong(), sha256 = sha256Hex(customChars)),
                    "t2s_char_map.json" to BundleFileEntry(size = customT2s.size.toLong(), sha256 = sha256Hex(customT2s))
                )
            )

            val manifestJson = Json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
            val zipBytes = createZip(
                mapOf(
                    "bundle_manifest.json" to manifestJson,
                    "titles_zh_cn.json" to customTitles,
                    "tags_zh_cn.json" to customTags,
                    "staff_characters_zh_cn.json" to customChars,
                    "t2s_char_map.json" to customT2s
                )
            )

            val result = bundleManager.installBundleFromZip(ByteArrayInputStream(zipBytes))
            assertFalse("Checksum mismatch should be rejected", result.isSuccess)
            assertTrue(result.message.contains("SHA-256 mismatch"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testVersionDowngradeRejection() {
        val tempDir = Files.createTempDirectory("anihyou_downgrade_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(tempDir)

            val customTitles = """{"999999": "测试动态标题"}""".toByteArray(Charsets.UTF_8)
            val customTags = """{"TestTag": "测试标签"}""".toByteArray(Charsets.UTF_8)
            val customChars = """{"char_88888": "测试角色"}""".toByteArray(Charsets.UTF_8)
            val customT2s = """{"幹": "干"}""".toByteArray(Charsets.UTF_8)

            // Attempt to install version older than built-in (2020.01.01 < 2026.09.08)
            val manifest = BundleManifest(
                formatVersion = 1,
                version = "2020.01.01",
                files = mapOf(
                    "titles_zh_cn.json" to BundleFileEntry(size = customTitles.size.toLong(), sha256 = sha256Hex(customTitles)),
                    "tags_zh_cn.json" to BundleFileEntry(size = customTags.size.toLong(), sha256 = sha256Hex(customTags)),
                    "staff_characters_zh_cn.json" to BundleFileEntry(size = customChars.size.toLong(), sha256 = sha256Hex(customChars)),
                    "t2s_char_map.json" to BundleFileEntry(size = customT2s.size.toLong(), sha256 = sha256Hex(customT2s))
                )
            )

            val manifestJson = Json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
            val zipBytes = createZip(
                mapOf(
                    "bundle_manifest.json" to manifestJson,
                    "titles_zh_cn.json" to customTitles,
                    "tags_zh_cn.json" to customTags,
                    "staff_characters_zh_cn.json" to customChars,
                    "t2s_char_map.json" to customT2s
                )
            )

            val result = bundleManager.installBundleFromZip(ByteArrayInputStream(zipBytes))
            assertFalse("Version downgrade should be rejected", result.isSuccess)
            assertTrue(result.message.contains("is not newer than current version"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testContentSchemaValidationRejection() {
        val tempDir = Files.createTempDirectory("anihyou_schema_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(tempDir)

            // Invalid title format (malformed Bangumi ID after pipe)
            val invalidTitles = """{"123": "测试动态标题|not_a_number"}""".toByteArray(Charsets.UTF_8)
            val customTags = """{"TestTag": "测试标签"}""".toByteArray(Charsets.UTF_8)
            val customChars = """{"char_88888": "测试角色"}""".toByteArray(Charsets.UTF_8)
            val customT2s = """{"幹": "干"}""".toByteArray(Charsets.UTF_8)

            val manifest = BundleManifest(
                formatVersion = 1,
                version = "2026.09.99",
                files = mapOf(
                    "titles_zh_cn.json" to BundleFileEntry(size = invalidTitles.size.toLong(), sha256 = sha256Hex(invalidTitles)),
                    "tags_zh_cn.json" to BundleFileEntry(size = customTags.size.toLong(), sha256 = sha256Hex(customTags)),
                    "staff_characters_zh_cn.json" to BundleFileEntry(size = customChars.size.toLong(), sha256 = sha256Hex(customChars)),
                    "t2s_char_map.json" to BundleFileEntry(size = customT2s.size.toLong(), sha256 = sha256Hex(customT2s))
                )
            )

            val manifestJson = Json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
            val zipBytes = createZip(
                mapOf(
                    "bundle_manifest.json" to manifestJson,
                    "titles_zh_cn.json" to invalidTitles,
                    "tags_zh_cn.json" to customTags,
                    "staff_characters_zh_cn.json" to customChars,
                    "t2s_char_map.json" to customT2s
                )
            )

            val result = bundleManager.installBundleFromZip(ByteArrayInputStream(zipBytes))
            assertFalse("Invalid schema should be rejected", result.isSuccess)
            assertTrue(result.message.contains("invalid Bangumi ID after pipe"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testMixedTitleKeys() {
        val tempDir = Files.createTempDirectory("anihyou_mixed_title_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(tempDir)

            val customTitles = """{"123": "数字标题|456", "進撃の巨人": "进击的巨人", "Cowboy Bebop": "星际牛仔"}""".toByteArray(Charsets.UTF_8)
            val customTags = """{"TestTag": "测试标签"}""".toByteArray(Charsets.UTF_8)
            val customChars = """{"char_88888": "测试角色"}""".toByteArray(Charsets.UTF_8)
            val customT2s = """{"幹": "干"}""".toByteArray(Charsets.UTF_8)

            val manifest = BundleManifest(
                formatVersion = 1,
                version = "2026.09.99",
                files = mapOf(
                    "titles_zh_cn.json" to BundleFileEntry(size = customTitles.size.toLong(), sha256 = sha256Hex(customTitles)),
                    "tags_zh_cn.json" to BundleFileEntry(size = customTags.size.toLong(), sha256 = sha256Hex(customTags)),
                    "staff_characters_zh_cn.json" to BundleFileEntry(size = customChars.size.toLong(), sha256 = sha256Hex(customChars)),
                    "t2s_char_map.json" to BundleFileEntry(size = customT2s.size.toLong(), sha256 = sha256Hex(customT2s))
                )
            )

            val manifestJson = Json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
            val zipBytes = createZip(
                mapOf(
                    "bundle_manifest.json" to manifestJson,
                    "titles_zh_cn.json" to customTitles,
                    "tags_zh_cn.json" to customTags,
                    "staff_characters_zh_cn.json" to customChars,
                    "t2s_char_map.json" to customT2s
                )
            )

            val result = bundleManager.installBundleFromZip(ByteArrayInputStream(zipBytes))
            assertTrue("Valid mixed title keys must be accepted, but got: ${result.message}", result.isSuccess)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testValidateTitleMapRules() {
        val bundleManager = LocalizationBundleManager()

        // 1. Valid mixed keys
        val validMap = mapOf(
            "123" to "数字标题|456",
            "進撃の巨人" to "进击的巨人",
            "Cowboy Bebop" to "星际牛仔"
        )
        val validRes = bundleManager.validateTitleMap(validMap)
        assertTrue("Valid mixed keys should pass: ${validRes.message}", validRes.isSuccess)

        // 2. Empty map rejected
        assertFalse(bundleManager.validateTitleMap(emptyMap()).isSuccess)

        // 3. Blank key rejected
        assertFalse(bundleManager.validateTitleMap(mapOf("   " to "validTitle")).isSuccess)

        // 4. Blank value rejected
        assertFalse(bundleManager.validateTitleMap(mapOf("123" to "   ")).isSuccess)

        // 5. Control character in key rejected
        assertFalse(bundleManager.validateTitleMap(mapOf("title\u0000bad" to "validTitle")).isSuccess)
        assertFalse(bundleManager.validateTitleMap(mapOf("title\nbad" to "validTitle")).isSuccess)

        // 6. Overlong key (>256 codepoints) rejected
        val overlongKey = "a".repeat(257)
        assertFalse(bundleManager.validateTitleMap(mapOf(overlongKey to "validTitle")).isSuccess)

        // 7. Overlong value (>512 codepoints) rejected
        val overlongVal = "a".repeat(513)
        assertFalse(bundleManager.validateTitleMap(mapOf("123" to overlongVal)).isSuccess)

        // 8. Malformed numeric values: "123": "title|not-a-number"
        val malformedBgm = bundleManager.validateTitleMap(mapOf("123" to "title|not-a-number"))
        assertFalse("Malformed bangumi ID should fail", malformedBgm.isSuccess)

        // 9. Non-numeric key with pipe rejected
        val nonNumericWithPipe = bundleManager.validateTitleMap(mapOf("進撃の巨人" to "title|456"))
        assertFalse("Non-numeric key with pipe should fail", nonNumericWithPipe.isSuccess)

        // 10. Non-positive numeric ID key rejected
        assertFalse(bundleManager.validateTitleMap(mapOf("0" to "title")).isSuccess)
        assertFalse(bundleManager.validateTitleMap(mapOf("-5" to "title")).isSuccess)
    }

    private fun populateValidBundle(dir: File, version: String = "2026.09.09") {
        dir.mkdirs()
        val customTitles = """{"1": "星际牛仔|253"}""".toByteArray(Charsets.UTF_8)
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
        File(dir, "bundle_manifest.json").writeText(Json.encodeToString(BundleManifest.serializer(), manifest))
        File(dir, "titles_zh_cn.json").writeBytes(customTitles)
        File(dir, "tags_zh_cn.json").writeBytes(customTags)
        File(dir, "staff_characters_zh_cn.json").writeBytes(customChars)
        File(dir, "t2s_char_map.json").writeBytes(customT2s)
    }

    private fun populateInvalidBundle(dir: File) {
        dir.mkdirs()
        File(dir, "corrupted.txt").writeText("invalid")
    }

    @Test
    fun testAllEightCrashRecoveryStates() {
        val tempRoot = Files.createTempDirectory("anihyou_8_states_test").toFile()
        try {
            val activeDir = File(tempRoot, "localization")
            val nextDir = File(tempRoot, "localization.next")
            val backupDir = File(tempRoot, "localization.backup")
            val bundleManager = LocalizationBundleManager()

            // 1. (0, 0, 0) -> SteadyState
            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            val res1 = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertEquals(com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.SteadyState, res1)

            // 2. (1, 0, 0) -> SteadyState
            populateValidBundle(activeDir)
            val res2 = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertEquals(com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.SteadyState, res2)

            // 3. (0, 1, 0) -> Valid Next promotes, Invalid Next discarded
            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateValidBundle(nextDir, "2026.09.15")
            val res3Valid = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertTrue(res3Valid is com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.PromotedNext)
            assertTrue(activeDir.exists())
            assertFalse(nextDir.exists())

            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateInvalidBundle(nextDir)
            val res3Invalid = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertTrue(res3Invalid is com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.DiscardedInvalidNext)
            assertFalse(activeDir.exists())
            assertFalse(nextDir.exists())

            // 4. (0, 0, 1) -> Backup restored to active
            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateValidBundle(backupDir, "2026.09.14")
            val res4 = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertTrue(res4 is com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.RestoredFromBackup)
            assertTrue(activeDir.exists())
            assertFalse(backupDir.exists())

            // 5. (1, 1, 0) -> Next deleted, active kept
            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateValidBundle(activeDir, "2026.09.10")
            populateInvalidBundle(nextDir)
            val res5 = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertTrue(res5 is com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.CleanedIncompleteNext)
            assertTrue(activeDir.exists())
            assertFalse(nextDir.exists())

            // 6. (1, 0, 1) -> Valid active deletes backup, invalid active restores backup
            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateValidBundle(activeDir, "2026.09.12")
            populateValidBundle(backupDir, "2026.09.11")
            val res6Valid = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertTrue(res6Valid is com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.RetainedValidActive)
            assertTrue(activeDir.exists())
            assertFalse(backupDir.exists())

            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateInvalidBundle(activeDir)
            populateValidBundle(backupDir, "2026.09.11")
            val res6Invalid = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertTrue(res6Invalid is com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.DiscardedInvalidActiveAndRestoredBackup)
            assertTrue(activeDir.exists())
            assertFalse(backupDir.exists())

            // 7. (0, 1, 1) -> Restore backup, delete next
            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateInvalidBundle(nextDir)
            populateValidBundle(backupDir, "2026.09.11")
            val res7 = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertTrue(res7 is com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.RestoredFromBackup)
            assertTrue(activeDir.exists())
            assertFalse(backupDir.exists())
            assertFalse(nextDir.exists())

            // 8. (1, 1, 1) -> Valid active deletes others; invalid active restores backup
            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateValidBundle(activeDir, "2026.09.12")
            populateInvalidBundle(nextDir)
            populateValidBundle(backupDir, "2026.09.11")
            val res8Valid = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertTrue(res8Valid is com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.RetainedValidActive)
            assertTrue(activeDir.exists())
            assertFalse(nextDir.exists())
            assertFalse(backupDir.exists())

            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateInvalidBundle(activeDir)
            populateInvalidBundle(nextDir)
            populateValidBundle(backupDir, "2026.09.11")
            val res8Invalid = bundleManager.recoverStorageState(activeDir, nextDir, backupDir)
            assertTrue(res8Invalid is com.axiel7.anihyou.core.network.localization.StorageRecoveryResult.DiscardedInvalidActiveAndRestoredBackup)
            assertTrue(activeDir.exists())
            assertFalse(nextDir.exists())
            assertFalse(backupDir.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private class FakeBundleFileOps(
        var failActiveToBackup: Boolean = false,
        var failNextToActive: Boolean = false,
        var failBackupToActive: Boolean = false
    ) : com.axiel7.anihyou.core.network.localization.BundleFileOps {
        private val delegate = com.axiel7.anihyou.core.network.localization.DefaultBundleFileOps()

        override fun rename(source: File, target: File): Boolean {
            if (failActiveToBackup && source.name == "localization" && target.name == "localization.backup") {
                return false
            }
            if (failNextToActive && source.name == "localization.next" && target.name == "localization") {
                return false
            }
            if (failBackupToActive && source.name == "localization.backup" && target.name == "localization") {
                return false
            }
            return delegate.rename(source, target)
        }

        override fun deleteRecursively(file: File): Boolean = delegate.deleteRecursively(file)
        override fun exists(file: File): Boolean = delegate.exists(file)
        override fun isDirectory(file: File): Boolean = delegate.isDirectory(file)
        override fun isCanonicallyContainedIn(child: File, parent: File): Boolean =
            delegate.isCanonicallyContainedIn(child, parent)
    }

    @Test
    fun testAtomicSwitchFailureRecovery() {
        val tempRoot = Files.createTempDirectory("anihyou_switch_failure_test").toFile()
        try {
            val activeDir = File(tempRoot, "localization")
            val nextDir = File(tempRoot, "localization.next")
            val backupDir = File(tempRoot, "localization.backup")

            // Test 1: Active -> Backup fails
            populateValidBundle(activeDir, "2026.09.01")
            populateValidBundle(nextDir, "2026.09.02")
            val fake1 = FakeBundleFileOps(failActiveToBackup = true)
            val manager1 = LocalizationBundleManager(fileOps = fake1)
            val switched1 = manager1.atomicSwitch(activeDir, nextDir, backupDir)
            assertFalse("Switch should fail if active->backup fails", switched1)
            assertTrue("Active must still exist and be intact", activeDir.exists())
            assertEquals("2026.09.01", manager1.validateExtractedDirectory(activeDir).manifest?.version)

            // Test 2: Next -> Active fails, backup is restored to active
            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateValidBundle(activeDir, "2026.09.01")
            populateValidBundle(nextDir, "2026.09.02")
            val fake2 = FakeBundleFileOps(failNextToActive = true)
            val manager2 = LocalizationBundleManager(fileOps = fake2)
            val switched2 = manager2.atomicSwitch(activeDir, nextDir, backupDir)
            assertFalse("Switch should fail if next->active fails", switched2)
            assertTrue("Active must be restored from backup", activeDir.exists())
            assertEquals("2026.09.01", manager2.validateExtractedDirectory(activeDir).manifest?.version)

            // Test 3: Post-switch validation fails on corrupted next
            activeDir.deleteRecursively(); nextDir.deleteRecursively(); backupDir.deleteRecursively()
            populateValidBundle(activeDir, "2026.09.01")
            populateInvalidBundle(nextDir)
            val manager3 = LocalizationBundleManager()
            val switched3 = manager3.atomicSwitch(activeDir, nextDir, backupDir)
            assertFalse("Switch should fail if post-switch validation fails", switched3)
            assertTrue("Active must be restored from backup after failed validation", activeDir.exists())
            assertEquals("2026.09.01", manager3.validateExtractedDirectory(activeDir).manifest?.version)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testVersionComparison() {
        val bundleManager = LocalizationBundleManager()
        val updateManager = BundleUpdateManager(bundleManager)

        assertTrue(updateManager.isNewerVersion("2026.09.09", "2026.09.08"))
        assertTrue(updateManager.isNewerVersion("2026.10.01", "2026.09.08"))
        assertTrue(updateManager.isNewerVersion("2027.01.01", "2026.09.08"))
        assertFalse(updateManager.isNewerVersion("2026.09.08", "2026.09.08"))
        assertFalse(updateManager.isNewerVersion("2026.09.07", "2026.09.08"))
        assertFalse(updateManager.isNewerVersion("2025.12.31", "2026.09.08"))
    }

    @Test
    fun testBundleOperationsDoNotAffectEntityNameCacheInLocalizationState() {
        val tempFilesDir = Files.createTempDirectory("anihyou_bundle_state_isolation").toFile()
        try {
            val localizationDir = File(tempFilesDir, "localization")
            val stateDir = File(tempFilesDir, "localization-state")
            val cacheFile = File(stateDir, "entity_names_cache.json")

            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(localizationDir)

            val cache = EntityNameCache()
            cache.setStorageFile(cacheFile)
            val t0 = 1_000_000_000_000L
            cache.putPositive(BangumiEntityKind.CHARACTER, 12345, "测试隔离角色", now = t0)
            assertEquals("测试隔离角色", cache.get(BangumiEntityKind.CHARACTER, 12345, now = t0 + 1000))

            // 1. Install a new bundle
            val customTitles = """{"1001": "测试标题"}""".toByteArray(Charsets.UTF_8)
            val customTags = """{"TestTag": "测试标签"}""".toByteArray(Charsets.UTF_8)
            val customChars = """{"char_88888": "测试角色"}""".toByteArray(Charsets.UTF_8)
            val customT2s = """{"幹": "干"}""".toByteArray(Charsets.UTF_8)

            val manifest = BundleManifest(
                formatVersion = 1,
                version = "2026.09.99",
                files = mapOf(
                    "titles_zh_cn.json" to BundleFileEntry(size = customTitles.size.toLong(), sha256 = sha256Hex(customTitles)),
                    "tags_zh_cn.json" to BundleFileEntry(size = customTags.size.toLong(), sha256 = sha256Hex(customTags)),
                    "staff_characters_zh_cn.json" to BundleFileEntry(size = customChars.size.toLong(), sha256 = sha256Hex(customChars)),
                    "t2s_char_map.json" to BundleFileEntry(size = customT2s.size.toLong(), sha256 = sha256Hex(customT2s))
                )
            )
            val manifestJson = Json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
            val zipBytes = createZip(mapOf(
                "bundle_manifest.json" to manifestJson,
                "titles_zh_cn.json" to customTitles,
                "tags_zh_cn.json" to customTags,
                "staff_characters_zh_cn.json" to customChars,
                "t2s_char_map.json" to customT2s
            ))
            val installResult = bundleManager.installBundleFromZip(ByteArrayInputStream(zipBytes), expectedVersion = "2026.09.99")
            assertTrue("Install must succeed: ${installResult.message}", installResult.isSuccess)

            // Cache in localization-state must remain intact
            assertEquals("测试隔离角色", cache.get(BangumiEntityKind.CHARACTER, 12345, now = t0 + 1000))
            assertTrue("Cache file must remain in localization-state directory", cacheFile.exists())

            // Active bundle directory must NOT contain entity_names_cache.json
            val cacheInLocalization = File(localizationDir, "entity_names_cache.json")
            assertFalse("localization bundle dir must not contain entity_names_cache.json", cacheInLocalization.exists())

            // 2. Reset bundle back to built-in
            val resetResult = bundleManager.resetToBuiltIn()
            assertTrue("Reset must succeed", resetResult)

            // Cache must STILL remain intact after reset!
            assertEquals("测试隔离角色", cache.get(BangumiEntityKind.CHARACTER, 12345, now = t0 + 1000))
            assertTrue("Cache file must still exist in localization-state directory", cacheFile.exists())
            assertFalse("localization dir must not contain entity_names_cache.json after reset", cacheInLocalization.exists())
        } finally {
            tempFilesDir.deleteRecursively()
        }
    }

    @Test
    fun testInstallBundleFromZipReturnsChineseSuccessMessage() {
        val tempDir = Files.createTempDirectory("anihyou_bundle_msg_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            bundleManager.setStorageDirectory(tempDir)

            val customTitles = """{"123": "测试"}""".toByteArray(Charsets.UTF_8)
            val customTags = """{"Tag": "标签"}""".toByteArray(Charsets.UTF_8)
            val customChars = """{"char_1": "角色"}""".toByteArray(Charsets.UTF_8)
            val customT2s = """{"幹": "干"}""".toByteArray(Charsets.UTF_8)

            val testVersion = "2026.09.99"
            val manifest = BundleManifest(
                formatVersion = 1,
                version = testVersion,
                files = mapOf(
                    "titles_zh_cn.json" to BundleFileEntry(size = customTitles.size.toLong(), sha256 = sha256Hex(customTitles)),
                    "tags_zh_cn.json" to BundleFileEntry(size = customTags.size.toLong(), sha256 = sha256Hex(customTags)),
                    "staff_characters_zh_cn.json" to BundleFileEntry(size = customChars.size.toLong(), sha256 = sha256Hex(customChars)),
                    "t2s_char_map.json" to BundleFileEntry(size = customT2s.size.toLong(), sha256 = sha256Hex(customT2s))
                )
            )
            val manifestJson = Json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
            val zipBytes = createZip(mapOf(
                "bundle_manifest.json" to manifestJson,
                "titles_zh_cn.json" to customTitles,
                "tags_zh_cn.json" to customTags,
                "staff_characters_zh_cn.json" to customChars,
                "t2s_char_map.json" to customT2s
            ))

            val result = bundleManager.installBundleFromZip(ByteArrayInputStream(zipBytes), expectedVersion = testVersion)
            assertTrue("Bundle install should succeed", result.isSuccess)
            assertEquals("已成功安装语言包 v$testVersion", result.message)
            assertEquals(testVersion, result.installedVersion)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
