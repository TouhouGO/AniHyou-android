package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.localization.BangumiLink
import com.axiel7.anihyou.core.localization.MediaKind
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

enum class DescriptionFailure { NETWORK, HTTP, RATE_LIMITED, INVALID_RESPONSE, TOO_LARGE }

sealed interface DescriptionFetchResult {
    data class Available(val summary: String, val subjectId: Int) : DescriptionFetchResult
    data object NoContent : DescriptionFetchResult
    data class Failed(val kind: DescriptionFailure, val retryAfterMillis: Long? = null) : DescriptionFetchResult
}

fun interface DescriptionTransport {
    suspend fun fetch(link: BangumiLink): DescriptionFetchResult
}

class BangumiDescriptionApi(
    version: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DescriptionTransport {
    private val userAgent = "TouhouGO/AniHyou-android/$version (https://github.com/TouhouGO/AniHyou-android)"

    init { require(version.isNotBlank() && version.none { it == '\r' || it == '\n' }) }

    override suspend fun fetch(link: BangumiLink): DescriptionFetchResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url("https://api.bgm.tv/v0/subjects/${link.subjectId}")
            .header("User-Agent", userAgent)
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(DescriptionFetchResult.Failed(DescriptionFailure.NETWORK))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    try {
                        when {
                            it.code == 429 -> DescriptionFetchResult.Failed(
                                DescriptionFailure.RATE_LIMITED,
                                parseRetryAfter(it.header("Retry-After"), nowMillis()),
                            )
                            !it.isSuccessful -> DescriptionFetchResult.Failed(DescriptionFailure.HTTP)
                            else -> readResponse(it, link)
                        }
                    } catch (_: IOException) {
                        DescriptionFetchResult.Failed(DescriptionFailure.NETWORK)
                    }
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
}

private fun readResponse(response: Response, link: BangumiLink): DescriptionFetchResult {
    val body = response.body ?: return DescriptionFetchResult.Failed(DescriptionFailure.INVALID_RESPONSE)
    if (body.contentLength() > MAX_BODY_BYTES) return DescriptionFetchResult.Failed(DescriptionFailure.TOO_LARGE)
    val source = body.source()
    val buffer = okio.Buffer()
    while (buffer.size <= MAX_BODY_BYTES) {
        val count = source.read(buffer, minOf(8192L, MAX_BODY_BYTES + 1 - buffer.size))
        if (count == -1L) break
    }
    if (buffer.size > MAX_BODY_BYTES) return DescriptionFetchResult.Failed(DescriptionFailure.TOO_LARGE)
    val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
    } catch (_: CharacterCodingException) {
        return DescriptionFetchResult.Failed(DescriptionFailure.INVALID_RESPONSE)
    }
    return parseSubject(text, link)
}

internal fun parseSubject(text: String, link: BangumiLink): DescriptionFetchResult {
    val obj = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        ?: return DescriptionFetchResult.Failed(DescriptionFailure.INVALID_RESPONSE)
    fun integer(name: String): Int? {
        val value = obj[name] as? JsonPrimitive ?: return null
        return if (!value.isString && value.content.matches(Regex("-?(0|[1-9][0-9]*)"))) value.intOrNull else null
    }
    val expectedType = if (link.media.kind == MediaKind.ANIME) 2 else 1
    if (integer("id") != link.subjectId || integer("type") != expectedType) {
        return DescriptionFetchResult.Failed(DescriptionFailure.INVALID_RESPONSE)
    }
    val summary = obj["summary"] ?: return DescriptionFetchResult.NoContent
    if (summary !is JsonPrimitive || !summary.isString) return DescriptionFetchResult.Failed(DescriptionFailure.INVALID_RESPONSE)
    return if (summary.content.isBlank()) DescriptionFetchResult.NoContent
    else DescriptionFetchResult.Available(summary.content, link.subjectId)
}

internal fun parseRetryAfter(value: String?, nowMillis: Long): Long? {
    val text = value?.trim() ?: return null
    if (text.isNotEmpty() && text.all { it in '0'..'9' }) {
        val seconds = text.toLongOrNull() ?: return Long.MAX_VALUE
        return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
    }
    return try {
        (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0)
    } catch (_: Exception) { null }
}

private const val MAX_BODY_BYTES = 256 * 1024L
