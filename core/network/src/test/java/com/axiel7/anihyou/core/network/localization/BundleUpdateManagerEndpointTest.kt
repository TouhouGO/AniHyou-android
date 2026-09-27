package com.axiel7.anihyou.core.network.localization

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleUpdateManagerEndpointTest {

    private companion object {
        /**
         * Fixed stand-in for the built-in bundle version. The real built-in manifest is
         * regenerated on every OTA release, so tests must not depend on its value.
         */
        const val BUILT_IN_STUB_VERSION = "2026.09.08"
    }

    /**
     * Builds a bundle manager whose current version comes from a manifest written into a
     * temp directory, NOT from the built-in resource. The built-in manifest is regenerated
     * by every OTA backfill, so reading it would make these tests rot on each release.
     */
    private fun bundleManagerAt(version: String): LocalizationBundleManager {
        val tempDir = Files.createTempDirectory("bundle_update_manager_test").toFile()
        tempDir.deleteOnExit()
        File(tempDir, "bundle_manifest.json").writeText("""{"version":"$version"}""")
        return LocalizationBundleManager().apply { setStorageDirectory(tempDir) }
    }

    /** 2026.09.08 -> 2026.09.09, so the "newer" version never has to be hardcoded. */
    private fun nextDayVersion(version: String): String {
        val parts = version.split(".")
        val day = parts[2].toInt() + 1
        return "${parts[0]}.${parts[1]}.${day.toString().padStart(2, '0')}"
    }

    private fun createClient(responseCode: Int, body: String): OkHttpClient {
        return OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message(if (responseCode == 200) "OK" else "Error $responseCode")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }

    private fun validManifestJson(version: String = "2026.09.10"): String = """
        {
            "formatVersion": 1,
            "version": "$version",
            "buildTimestamp": 1788912000000,
            "description": "OTA test update",
            "downloadUrl": "https://github.com/TouhouGO/AniHyou-android/releases/download/v$version/localization_bundle.zip",
            "archiveSize": 524288,
            "archiveSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        }
    """.trimIndent()

    @Test
    fun testDefaultEndpointUsesStableLocalizationReleaseAndTreats404AsUnavailable() = runBlocking {
        var requestedUrl: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl = chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("not found".toResponseBody("text/plain".toMediaType()))
                .build()
        }.build()
        val result = BundleUpdateManager(bundleManagerAt(BUILT_IN_STUB_VERSION), customHttpClient = client)
            .checkUpdate()

        assertEquals(
            "https://github.com/TouhouGO/AniHyou-android/releases/download/localization-latest/remote_bundle_manifest.json",
            requestedUrl
        )
        assertSame(BundleUpdateCheckResult.RemoteUnavailable, result)
        assertNotEquals(BundleUpdateCheckResult.NoUpdate::class, result::class)
    }

    @Test
    fun testHttp200RemoteVersionEqualsCurrentReturnsNoUpdate() = runBlocking {
        val current = BUILT_IN_STUB_VERSION
        val client = createClient(200, validManifestJson(version = current))
        val result = BundleUpdateManager(bundleManagerAt(current), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected NoUpdate but got $result", result is BundleUpdateCheckResult.NoUpdate)
        val noUpdate = result as BundleUpdateCheckResult.NoUpdate
        assertEquals(current, noUpdate.info.remoteVersion)
        assertEquals(false, noUpdate.info.hasUpdate)
    }

    @Test
    fun testHttp200RemoteVersionNewerReturnsUpdateAvailable() = runBlocking {
        val current = BUILT_IN_STUB_VERSION
        val newer = nextDayVersion(current)
        val client = createClient(200, validManifestJson(version = newer))
        val result = BundleUpdateManager(bundleManagerAt(current), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected UpdateAvailable but got $result", result is BundleUpdateCheckResult.UpdateAvailable)
        val updateAvailable = result as BundleUpdateCheckResult.UpdateAvailable
        assertEquals(newer, updateAvailable.info.remoteVersion)
        assertEquals(current, updateAvailable.info.currentVersion)
        assertEquals(true, updateAvailable.info.hasUpdate)
    }

    @Test
    fun testHttp404ReturnsRemoteUnavailableAndNeverNoUpdate() = runBlocking {
        val client = createClient(404, "Not found")
        val result = BundleUpdateManager(bundleManagerAt(BUILT_IN_STUB_VERSION), customHttpClient = client)
            .checkUpdate()

        assertEquals(BundleUpdateCheckResult.RemoteUnavailable, result)
        assertTrue(result !is BundleUpdateCheckResult.NoUpdate)
    }

    @Test
    fun testHttp200MalformedJsonReturnsManifestInvalid() = runBlocking {
        val client = createClient(200, "{ invalid json content ...")
        val result = BundleUpdateManager(bundleManagerAt(BUILT_IN_STUB_VERSION), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected ManifestInvalid but got $result", result is BundleUpdateCheckResult.ManifestInvalid)
        assertTrue((result as BundleUpdateCheckResult.ManifestInvalid).message.contains("Malformed"))
    }

    @Test
    fun testHttp5xxReturnsNetworkUnavailable() = runBlocking {
        val client = createClient(502, "Bad Gateway")
        val result = BundleUpdateManager(bundleManagerAt(BUILT_IN_STUB_VERSION), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected NetworkUnavailable but got $result", result is BundleUpdateCheckResult.NetworkUnavailable)
        assertTrue((result as BundleUpdateCheckResult.NetworkUnavailable).message.contains("502"))
    }

    @Test
    fun testNetworkTimeoutReturnsNetworkUnavailable() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor {
            throw SocketTimeoutException("Connection timed out")
        }.build()
        val result = BundleUpdateManager(bundleManagerAt(BUILT_IN_STUB_VERSION), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected NetworkUnavailable but got $result", result is BundleUpdateCheckResult.NetworkUnavailable)
        assertTrue((result as BundleUpdateCheckResult.NetworkUnavailable).message.contains("Connection timed out"))
    }
}
