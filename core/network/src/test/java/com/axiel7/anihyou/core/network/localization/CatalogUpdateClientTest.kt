package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CatalogUpdateClientTest {
    private val digest = "a".repeat(64)
    private val manifest = """{"formatVersion":1,"catalogSchemaVersion":1,"version":"2026.11.08","size":3,"sha256":"$digest","downloadUrl":"https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v2026.11.08/catalog.json"}"""
    private fun client(handler: (String) -> Pair<Int, String>, location: String? = null): CatalogUpdateClient {
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val (code, body) = handler(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { if (location != null) header("Location", location) }.body(body.toResponseBody()).build()
        }.build()
        return CatalogUpdateClient(http)
    }
    @Test fun unavailableIsNotUpToDate() = runBlocking { assertNull(client({ 404 to "" }).check()) }
    @Test fun verifiesManifestAndDownloadsExactBytes() = runBlocking {
        val client = client({ if (it.endsWith("catalog-manifest.json")) 200 to manifest else 200 to "abc" })
        val offer = requireNotNull(client.check())
        assertEquals("2026.11.08", offer.version)
        assertArrayEquals("abc".toByteArray(), client.download(offer))
    }
    @Test fun rejectsUntrustedRedirectAndInvalidManifest() = runBlocking {
        try { client({ 302 to "" }, "https://example.org/file").check(); fail() } catch (_: IllegalArgumentException) {}
        try { client({ 200 to manifest.replace("catalog-v2026.11.08", "catalog-v2026.11.09") }).check(); fail() } catch (_: IllegalArgumentException) {}
        try { client({ 200 to manifest.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1") }).check(); fail() } catch (_: IllegalArgumentException) {}
        try { client({ 503 to "" }).check(); fail() } catch (_: IOException) {}
    }
    @Test fun rejectsOversizedManifest() = runBlocking {
        try { client({ 200 to "x".repeat(32769) }).check(); fail() }
        catch (e: CatalogFailureException) { assertEquals(CatalogFailureCode.INVALID_MANIFEST, e.code) }
    }
    @Test fun rejectsOversizedCatalogAsFileTooLarge() = runBlocking {
        val client = client(handler = { url ->
            if (url.endsWith("catalog-manifest.json")) 200 to manifest
            else 200 to "x".repeat(20 * 1024 * 1024 + 1)
        })
        val offer = requireNotNull(client.check())
        try { client.download(offer); fail() }
        catch (e: CatalogFailureException) { assertEquals(CatalogFailureCode.FILE_TOO_LARGE, e.code) }
    }
    @Test fun manifestRejectsYearZeroAndNonDecemberCalendarDate() = runBlocking {
        val zero = manifest.replace("2026.11.08", "0000.01.01")
        try { client({ 200 to zero }).check(); fail("Expected year zero rejection") } catch (_: IllegalArgumentException) {}
        val impossible = manifest.replace("2026.11.08", "2026.02.30")
        try { client({ 200 to impossible }).check(); fail("Expected impossible date rejection") } catch (_: IllegalArgumentException) {}
    }
}
