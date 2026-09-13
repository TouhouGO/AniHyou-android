package com.axiel7.anihyou.core.network.localization

import java.io.IOException
import java.net.SocketTimeoutException
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
        val result = BundleUpdateManager(LocalizationBundleManager(), customHttpClient = client)
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
        val client = createClient(200, validManifestJson(version = "2026.09.08"))
        val result = BundleUpdateManager(LocalizationBundleManager(), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected NoUpdate but got $result", result is BundleUpdateCheckResult.NoUpdate)
        val noUpdate = result as BundleUpdateCheckResult.NoUpdate
        assertEquals("2026.09.08", noUpdate.info.remoteVersion)
        assertEquals(false, noUpdate.info.hasUpdate)
    }

    @Test
    fun testHttp200RemoteVersionNewerReturnsUpdateAvailable() = runBlocking {
        val client = createClient(200, validManifestJson(version = "2026.09.15"))
        val result = BundleUpdateManager(LocalizationBundleManager(), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected UpdateAvailable but got $result", result is BundleUpdateCheckResult.UpdateAvailable)
        val updateAvailable = result as BundleUpdateCheckResult.UpdateAvailable
        assertEquals("2026.09.15", updateAvailable.info.remoteVersion)
        assertEquals(true, updateAvailable.info.hasUpdate)
    }

    @Test
    fun testHttp404ReturnsRemoteUnavailableAndNeverNoUpdate() = runBlocking {
        val client = createClient(404, "Not found")
        val result = BundleUpdateManager(LocalizationBundleManager(), customHttpClient = client)
            .checkUpdate()

        assertEquals(BundleUpdateCheckResult.RemoteUnavailable, result)
        assertTrue(result !is BundleUpdateCheckResult.NoUpdate)
    }

    @Test
    fun testHttp200MalformedJsonReturnsManifestInvalid() = runBlocking {
        val client = createClient(200, "{ invalid json content ...")
        val result = BundleUpdateManager(LocalizationBundleManager(), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected ManifestInvalid but got $result", result is BundleUpdateCheckResult.ManifestInvalid)
        assertTrue((result as BundleUpdateCheckResult.ManifestInvalid).message.contains("Malformed"))
    }

    @Test
    fun testHttp5xxReturnsNetworkUnavailable() = runBlocking {
        val client = createClient(502, "Bad Gateway")
        val result = BundleUpdateManager(LocalizationBundleManager(), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected NetworkUnavailable but got $result", result is BundleUpdateCheckResult.NetworkUnavailable)
        assertTrue((result as BundleUpdateCheckResult.NetworkUnavailable).message.contains("502"))
    }

    @Test
    fun testNetworkTimeoutReturnsNetworkUnavailable() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor {
            throw SocketTimeoutException("Connection timed out")
        }.build()
        val result = BundleUpdateManager(LocalizationBundleManager(), customHttpClient = client)
            .checkUpdate()

        assertTrue("Expected NetworkUnavailable but got $result", result is BundleUpdateCheckResult.NetworkUnavailable)
        assertTrue((result as BundleUpdateCheckResult.NetworkUnavailable).message.contains("Connection timed out"))
    }
}
