package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val CHANNEL = "https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-latest/catalog-manifest.json"
private const val MAX_MANIFEST = 32 * 1024
private const val MAX_CATALOG = 20 * 1024 * 1024

@Serializable
data class CatalogManifest(val formatVersion: Int, val catalogSchemaVersion: Int, val version: String, val size: Long, val sha256: String, val downloadUrl: String) {
    fun validate() {
        require(formatVersion == 1 && catalogSchemaVersion == 1)
        require(version.matches(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")))
        val year = version.substring(0, 4).toInt()
        require(year in 1..9999)
        try { LocalDate.of(year, version.substring(5, 7).toInt(), version.substring(8, 10).toInt()) }
        catch (e: java.time.DateTimeException) { throw IllegalArgumentException("Invalid catalog version", e) }
        require(size in 1..MAX_CATALOG.toLong() && sha256.matches(Regex("[0-9a-f]{64}")))
        require(downloadUrl == "https://github.com/TouhouGO/AniHyou-android/releases/download/catalog-v$version/catalog.json")
    }
}

interface CatalogTransport {
    suspend fun check(): CatalogManifest?
    suspend fun download(manifest: CatalogManifest): ByteArray
}

class CatalogUpdateClient(client: OkHttpClient = OkHttpClient()) : CatalogTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
    override suspend fun check(): CatalogManifest? {
        val bytes = get(CHANNEL, MAX_MANIFEST, true, CatalogFailureCode.INVALID_MANIFEST) ?: return null
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        // Parse first so the duplicate-key scanner only sees syntactically valid JSON.
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Manifest must be an object")
        require(root.keys == setOf("formatVersion", "catalogSchemaVersion", "version", "size", "sha256", "downloadUrl")) { "Invalid manifest fields" }
        for (name in listOf("formatVersion", "catalogSchemaVersion", "size")) {
            val field = root[name] as? JsonPrimitive
            require(field != null && !field.isString && field.content.matches(Regex("0|[1-9][0-9]*"))) { "Invalid manifest integer" }
        }
        for (name in listOf("version", "sha256", "downloadUrl")) {
            require((root[name] as? JsonPrimitive)?.isString == true) { "Invalid manifest string" }
        }
        ManifestKeys(text).check()
        return Json.decodeFromString<CatalogManifest>(text).also { it.validate() }
    }
    override suspend fun download(manifest: CatalogManifest): ByteArray {
        manifest.validate()
        return requireNotNull(get(manifest.downloadUrl, MAX_CATALOG, false, CatalogFailureCode.FILE_TOO_LARGE))
    }
    private data class Reply(val status: Int, val location: String?, val bytes: ByteArray?)
    private suspend fun get(
        initial: String,
        limit: Int,
        unavailableOn404: Boolean,
        oversizeCode: CatalogFailureCode,
    ): ByteArray? {
        var url = initial.toHttpUrl()
        repeat(6) {
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.port == 443 && url.host in setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")) { "Untrusted redirect" }
            val reply = execute(Request.Builder().url(url).build(), limit, oversizeCode)
            if (reply.status in listOf(301, 302, 303, 307, 308)) {
                url = url.resolve(reply.location ?: throw IOException("Missing redirect location")) ?: throw IOException("Invalid redirect")
            } else {
                if (reply.status == 404 && unavailableOn404) return null
                if (reply.status !in 200..299) throw IOException("HTTP ${reply.status}")
                return requireNotNull(reply.bytes)
            }
        }
        throw IOException("Too many redirects")
    }
    private suspend fun execute(request: Request, limit: Int, oversizeCode: CatalogFailureCode): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val bytes = if (it.isSuccessful) {
                            val body = it.body ?: throw IOException("Missing response")
                            if (body.contentLength() > limit) throw CatalogFailureException(oversizeCode)
                            val input = body.byteStream()
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                if (output.size() > limit) throw CatalogFailureException(oversizeCode)
                            }
                            output.toByteArray()
                        } else null
                        Reply(it.code, it.header("Location"), bytes)
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}

private class ManifestKeys(private val text: String) {
    private var index = 0
    fun check() { value() }
    private fun space() { while (index < text.length && text[index].isWhitespace()) index++ }
    private fun string(): String {
        val start = index++
        while (true) when (text[index++]) {
            '\\' -> index++
            '"' -> return Json.decodeFromString(text.substring(start, index))
        }
    }
    private fun value() {
        space()
        when (text[index]) {
            '{' -> {
                index++; space(); val keys = mutableSetOf<String>()
                if (text[index] == '}') { index++; return }
                while (true) {
                    space(); require(keys.add(string())) { "Duplicate manifest key" }
                    space(); index++; value(); space()
                    if (text[index++] == '}') return
                }
            }
            '[' -> {
                index++; space()
                if (text[index] == ']') { index++; return }
                while (true) { value(); space(); if (text[index++] == ']') return }
            }
            '"' -> { string() }
            else -> while (index < text.length && text[index] !in ",]} \t\r\n") index++
        }
    }
}
