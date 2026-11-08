package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.localization.MediaKind
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

data class SubjectSearchHit(val subjectId: Int, val originalName: String, val chineseName: String)

class SubjectSearchPage(val offset: Int, val limit: Int, val total: Int, hits: List<SubjectSearchHit>) {
    val hits: List<SubjectSearchHit> = Collections.unmodifiableList(hits.toList())
    val nextOffset: Int? get() = (offset.toLong() + hits.size).let {
        if (hits.isNotEmpty() && it < total && it <= Int.MAX_VALUE) it.toInt() else null
    }
}

enum class SubjectSearchFailure { INVALID_REQUEST, NETWORK, HTTP, RATE_LIMITED, INVALID_RESPONSE, TOO_LARGE }
sealed interface SubjectSearchResult {
    data class Success(val page: SubjectSearchPage) : SubjectSearchResult
    data class Failed(val kind: SubjectSearchFailure, val retryAfterMillis: Long? = null) : SubjectSearchResult
}

fun interface SubjectSearchTransport {
    suspend fun search(query: String, kind: MediaKind, offset: Int, limit: Int): SubjectSearchResult
}

class BangumiSubjectSearchApi(
    version: String,
    client: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SubjectSearchTransport {
    private val userAgent = "TouhouGO/AniHyou-android/$version (https://github.com/TouhouGO/AniHyou-android)"
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES).build()

    init { require(version.isNotBlank() && version.none { it == '\r' || it == '\n' }) }

    override suspend fun search(query: String, kind: MediaKind, offset: Int, limit: Int): SubjectSearchResult {
        if (query.isBlank() || query.codePointCount(0, query.length) > 512 || offset < 0 || limit !in 1..50) {
            return SubjectSearchResult.Failed(SubjectSearchFailure.INVALID_REQUEST)
        }
        val type = if (kind == MediaKind.ANIME) 2 else 1
        val json = buildJsonObject {
            put("keyword", query)
            put("sort", "match")
            put("filter", buildJsonObject { put("type", buildJsonArray { add(JsonPrimitive(type)) }) })
        }.toString()
        val request = Request.Builder().url("https://api.bgm.tv/v0/search/subjects?limit=$limit&offset=$offset")
            .header("User-Agent", userAgent).post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(SubjectSearchResult.Failed(SubjectSearchFailure.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            when {
                                it.code == 429 -> SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, parseRetryAfter(it.header("Retry-After"), nowMillis()))
                                !it.isSuccessful -> SubjectSearchResult.Failed(SubjectSearchFailure.HTTP)
                                else -> readSearchResponse(it, type, offset, limit)
                            }
                        }
                    } catch (_: IOException) { SubjectSearchResult.Failed(SubjectSearchFailure.NETWORK) }
                    catch (_: Exception) { SubjectSearchResult.Failed(SubjectSearchFailure.INVALID_RESPONSE) }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}

private fun readSearchResponse(response: Response, type: Int, offset: Int, requestedLimit: Int): SubjectSearchResult {
    val invalid = SubjectSearchResult.Failed(SubjectSearchFailure.INVALID_RESPONSE)
    val body = response.body ?: return invalid
    if (body.contentLength() > SEARCH_BODY_LIMIT) return SubjectSearchResult.Failed(SubjectSearchFailure.TOO_LARGE)
    val buffer = Buffer()
    val source = body.source()
    while (buffer.size <= SEARCH_BODY_LIMIT) {
        if (source.read(buffer, minOf(8192, SEARCH_BODY_LIMIT + 1 - buffer.size)) == -1L) break
    }
    if (buffer.size > SEARCH_BODY_LIMIT) return SubjectSearchResult.Failed(SubjectSearchFailure.TOO_LARGE)
    val text = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { return invalid }
    val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return invalid
    fun integer(obj: JsonObject, key: String): Int? {
        val v = obj[key] as? JsonPrimitive ?: return null
        return if (!v.isString && v.content.matches(Regex("-?(0|[1-9][0-9]*)"))) v.intOrNull else null
    }
    fun string(obj: JsonObject, key: String): String? {
        val v = obj[key] as? JsonPrimitive ?: return null
        return v.content.takeIf { v.isString && it.codePointCount(0, it.length) <= 4096 }
    }
    val actualOffset = integer(root, "offset") ?: return invalid
    val limit = integer(root, "limit") ?: return invalid
    val total = integer(root, "total") ?: return invalid
    val data = root["data"] as? JsonArray ?: return invalid
    if (actualOffset != offset || limit !in 1..requestedLimit || total < 0 || data.size > limit ||
        (data.isEmpty() && offset < total) || (data.isNotEmpty() && offset.toLong() + data.size > total)) return invalid
    val hits = ArrayList<SubjectSearchHit>(data.size)
    val ids = HashSet<Int>()
    for (element in data) {
        val obj = element as? JsonObject ?: return invalid
        val id = integer(obj, "id") ?: return invalid
        if (id <= 0 || integer(obj, "type") != type || !ids.add(id)) return invalid
        hits.add(SubjectSearchHit(id, string(obj, "name") ?: return invalid, string(obj, "name_cn") ?: return invalid))
    }
    return SubjectSearchResult.Success(SubjectSearchPage(offset, limit, total, hits))
}

private const val SEARCH_BODY_LIMIT = 2 * 1024 * 1024L
