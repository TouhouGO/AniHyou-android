package com.axiel7.anihyou.core.network

import com.axiel7.anihyou.core.network.localization.RemoteBundleManifest
import com.axiel7.anihyou.core.network.localization.RemoteBundleManifestValidator
import com.axiel7.anihyou.core.network.localization.RemoteManifestValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBundleManifestTest {

    private val validSha256 = "a".repeat(64)
    private val validVersion = "2026.09.09"
    private val validUrl = "https://github.com/TouhouGO/AniHyou-android/releases/download/v2026.09.09/localization_bundle.zip"

    private fun createManifest(
        formatVersion: Int = 1,
        version: String = validVersion,
        archiveSize: Long = 1024 * 1024L,
        archiveSha256: String = validSha256,
        downloadUrl: String = validUrl,
        description: String = "Test bundle"
    ) = RemoteBundleManifest(
        formatVersion = formatVersion,
        version = version,
        buildTimestamp = 1788883200000L,
        description = description,
        downloadUrl = downloadUrl,
        archiveSize = archiveSize,
        archiveSha256 = archiveSha256
    )

    @Test
    fun testValidManifestAccepted() {
        val manifest = createManifest()
        val result = RemoteBundleManifestValidator.validate(manifest)
        assertTrue("Valid manifest should pass validation", result is RemoteManifestValidation.Valid)
        assertEquals(manifest, (result as RemoteManifestValidation.Valid).manifest)
    }

    @Test
    fun testWrongFormatVersionRejected() {
        val manifest = createManifest(formatVersion = 2)
        val result = RemoteBundleManifestValidator.validate(manifest)
        assertTrue("Format version != 1 should fail", result is RemoteManifestValidation.Invalid)
        assertTrue((result as RemoteManifestValidation.Invalid).message.contains("Unsupported formatVersion"))
    }

    @Test
    fun testMalformedVersionRejected() {
        val badVersions = listOf("2026.9.9", "2026-09-09", "v2026.09.09", "2026.bad.09", "2026.09", "")
        for (badVer in badVersions) {
            val manifest = createManifest(version = badVer)
            val result = RemoteBundleManifestValidator.validate(manifest)
            assertTrue("Malformed version '$badVer' should fail", result is RemoteManifestValidation.Invalid)
        }
    }

    @Test
    fun testArchiveSizeConstraints() {
        // Zero
        assertTrue(RemoteBundleManifestValidator.validate(createManifest(archiveSize = 0L)) is RemoteManifestValidation.Invalid)
        // Negative
        assertTrue(RemoteBundleManifestValidator.validate(createManifest(archiveSize = -100L)) is RemoteManifestValidation.Invalid)
        // Oversized (>10MB)
        assertTrue(RemoteBundleManifestValidator.validate(createManifest(archiveSize = 10L * 1024 * 1024 + 1)) is RemoteManifestValidation.Invalid)
    }

    @Test
    fun testArchiveSha256Constraints() {
        // Too short
        assertTrue(RemoteBundleManifestValidator.validate(createManifest(archiveSha256 = "abc")) is RemoteManifestValidation.Invalid)
        // Non-hex character
        assertTrue(RemoteBundleManifestValidator.validate(createManifest(archiveSha256 = "g".repeat(64))) is RemoteManifestValidation.Invalid)
        // Too long
        assertTrue(RemoteBundleManifestValidator.validate(createManifest(archiveSha256 = "a".repeat(65))) is RemoteManifestValidation.Invalid)
    }

    @Test
    fun testDownloadUrlConstraints() {
        // HTTP instead of HTTPS
        val httpUrl = "http://github.com/TouhouGO/AniHyou-android/releases/download/v2026.09.09/localization_bundle.zip"
        assertTrue(RemoteBundleManifestValidator.validate(createManifest(downloadUrl = httpUrl)) is RemoteManifestValidation.Invalid)

        // Unexpected host
        val badHostUrl = "https://evil.com/TouhouGO/AniHyou-android/releases/download/v2026.09.09/localization_bundle.zip"
        assertTrue(RemoteBundleManifestValidator.validate(createManifest(downloadUrl = badHostUrl)) is RemoteManifestValidation.Invalid)

        // Tag disagrees with manifest version
        val tagMismatchUrl = "https://github.com/TouhouGO/AniHyou-android/releases/download/v2026.09.10/localization_bundle.zip"
        val mismatchRes = RemoteBundleManifestValidator.validate(createManifest(version = "2026.09.09", downloadUrl = tagMismatchUrl))
        assertTrue(mismatchRes is RemoteManifestValidation.Invalid)
        assertTrue((mismatchRes as RemoteManifestValidation.Invalid).message.contains("Version mismatch"))
    }
}
