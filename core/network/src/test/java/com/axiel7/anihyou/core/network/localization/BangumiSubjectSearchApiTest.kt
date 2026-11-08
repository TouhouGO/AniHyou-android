package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.localization.MediaKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

class BangumiSubjectSearchApiTest {
    private val anime = MediaKind.ANIME
    private fun api(body: ResponseBody, status: Int = 200, inspect: (okhttp3.Request) -> Unit = {}): BangumiSubjectSearchApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .header("Retry-After", "3").body(body).build()
        }.build()
        return BangumiSubjectSearchApi("1.2.3", client)
    }
    private fun api(text: String, status: Int = 200, inspect: (okhttp3.Request) -> Unit = {}) = api(text.toResponseBody(), status, inspect)
    private fun page(data: String, total: String = "1", offset: String = "0", limit: String = "25") =
        """{"offset":$offset,"limit":$limit,"total":$total,"data":[$data]}"""
    private val hit = """{"id":1,"type":2,"name":"原名","name_cn":"中文"}"""
    @Test fun requestAndTypeIsolation() = runBlocking {
        for ((kind, type) in listOf(anime to 2, MediaKind.MANGA to 1)) {
            val result = api(page(hit.replace("\"type\":2", "\"type\":$type"))) { req ->
                assertEquals("https://api.bgm.tv/v0/search/subjects?limit=25&offset=0", req.url.toString())
                assertEquals("POST", req.method)
                assertNull(req.header("Authorization")); assertNull(req.header("Cookie"))
                assertEquals("TouhouGO/AniHyou-android/1.2.3 (https://github.com/TouhouGO/AniHyou-android)", req.header("User-Agent"))
                val buffer = Buffer(); req.body!!.writeTo(buffer)
                assertEquals("""{"keyword":"中文","sort":"match","filter":{"type":[$type]}}""", buffer.readUtf8())
            }.search("中文", kind, 0, 25) as SubjectSearchResult.Success
            assertEquals(1, result.page.hits.size); assertNull(result.page.nextOffset)
        }
    }
    @Test fun pagingAndInvalidInputs() = runBlocking {
        val partial = api(page(hit, "3")).search("x", anime, 0, 25) as SubjectSearchResult.Success
        assertEquals(1, partial.page.nextOffset)
        val empty = api(page("", "0")).search("x", anime, 0, 25) as SubjectSearchResult.Success
        assertNull(empty.page.nextOffset)
        val full = api(page(hit, "2", limit = "1")).search("x", anime, 0, 1) as SubjectSearchResult.Success
        assertEquals(1, full.page.nextOffset)
        val api = api("")
        for ((q, off, lim) in listOf(Triple(" ", 0, 25), Triple("a".repeat(513), 0, 25), Triple("x", -1, 25), Triple("x", 0, 51)))
            assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.INVALID_REQUEST), api.search(q, anime, off, lim))
    }
    @Test fun rejectsMalformedAndImpossiblePages() = runBlocking {
        val invalid = listOf(page(hit.replace("\"id\":1", "\"id\":\"1\"")), page(hit.replace("\"id\":1", "\"id\":1.0")),
            page(hit.replace("\"id\":1", "\"id\":-1")), page(hit.replace("\"type\":2", "\"type\":1")),
            page(hit.replace("\"name\":\"原名\"", "\"name\":null")), page("$hit,$hit", "2"), page("", "1"),
            page(hit, "0"), page(hit, offset = "1"), page(hit, limit = "0"), page(hit, total = "\"1\""),
            page(hit.replace("原名", "x".repeat(4097))))
        for (body in invalid) assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.INVALID_RESPONSE), api(body).search("x", anime, 0, 25))
        assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.HTTP), api("", 500).search("x", anime, 0, 25))
        assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.RATE_LIMITED, 3000), api("", 429).search("x", anime, 0, 25))
    }
    @Test fun boundsAndUtf8() = runBlocking {
        val invalid = byteArrayOf(0xc3.toByte(), 0x28).toResponseBody()
        assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.INVALID_RESPONSE), api(invalid).search("x", anime, 0, 25))
        val big = "x".repeat(2 * 1024 * 1024 + 1)
        assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.TOO_LARGE), api(big).search("x", anime, 0, 25))
        var closed = false
        val unknown = object : ResponseBody() {
            val buffer = Buffer().writeUtf8(big)
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = buffer
            override fun close() { closed = true; super.close() }
        }
        assertEquals(SubjectSearchResult.Failed(SubjectSearchFailure.TOO_LARGE), api(unknown).search("x", anime, 0, 25))
        assertTrue(closed)
    }
    @Test fun cancellationCancelsCall() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        var call: okhttp3.Call? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call = chain.call(); started.countDown(); release.await(5, TimeUnit.SECONDS); throw java.io.IOException()
        }.build()
        val job = launch(Dispatchers.Default) { BangumiSubjectSearchApi("1", client).search("x", anime, 0, 25) }
        try { assertTrue(started.await(5, TimeUnit.SECONDS)); job.cancelAndJoin(); assertTrue(call!!.isCanceled()) }
        finally { release.countDown() }
    }
}
