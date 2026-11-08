package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.localization.BangumiLink
import com.axiel7.anihyou.core.localization.MediaIdentity
import com.axiel7.anihyou.core.localization.MediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.ResponseBody
import okhttp3.MediaType
import okio.Buffer
import okio.BufferedSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BangumiDescriptionApiTest {
    private val link = BangumiLink(MediaIdentity(MediaKind.ANIME, 1), 42, "source")
    private fun api(body: String, status: Int = 200, inspect: (okhttp3.Request) -> Unit = {}): BangumiDescriptionApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        return BangumiDescriptionApi("1.2.3", client)
    }
    @Test fun retryAfterDeltaAndDate() {
        assertEquals(3000L, parseRetryAfter("3", 0L))
        assertEquals(1000L, parseRetryAfter("Thu, 01 Jan 1970 00:00:01 GMT", 0L))
        assertNull(parseRetryAfter("invalid", 0L))
    }
    @Test fun literalUtf8AndNoCredentials() = runBlocking {
        val result = api("""{"id":42,"type":2,"summary":"你好\n<b>literal</b>"}""") {
            assertEquals("https://api.bgm.tv/v0/subjects/42", it.url.toString())
            assertEquals(null, it.header("Authorization"))
            assertEquals("TouhouGO/AniHyou-android/1.2.3 (https://github.com/TouhouGO/AniHyou-android)", it.header("User-Agent"))
        }.fetch(link)
        assertEquals(DescriptionFetchResult.Available("你好\n<b>literal</b>", 42), result)
    }
    @Test fun responseValidation() = runBlocking {
        for (body in listOf("{}", "{", """{"id":"42","type":2}""", """{"id":42,"type":1}""", """{"id":43,"type":2}""", """{"id":42,"type":2,"summary":1}""")) {
            assertEquals(DescriptionFetchResult.Failed(DescriptionFailure.INVALID_RESPONSE), api(body).fetch(link))
        }
        assertEquals(DescriptionFetchResult.NoContent, api("""{"id":42,"type":2,"summary":"  "}""").fetch(link))
        assertEquals(DescriptionFetchResult.NoContent, api("""{"id":42,"type":2}""").fetch(link))
        assertEquals(DescriptionFetchResult.Failed(DescriptionFailure.TOO_LARGE), api("x".repeat(256 * 1024 + 1)).fetch(link))
        assertEquals(DescriptionFetchResult.Failed(DescriptionFailure.HTTP), api("", 500).fetch(link))
        assertEquals(DescriptionFetchResult.Failed(DescriptionFailure.RATE_LIMITED), api("", 429).fetch(link))
    }
    @Test fun unknownLengthBodyIsBoundedAndClosed() = runBlocking {
        var closed = false
        val body = object : ResponseBody() {
            private val buffer = Buffer().writeUtf8("x".repeat(256 * 1024 + 1))
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = buffer
            override fun close() { closed = true; super.close() }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body).build()
        }.build()
        assertEquals(DescriptionFetchResult.Failed(DescriptionFailure.TOO_LARGE), BangumiDescriptionApi("1", client).fetch(link))
        assertEquals(true, closed)
    }
    @Test fun cancellingRequestCancelsOkHttpCall() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        var call: okhttp3.Call? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call = chain.call()
            started.countDown()
            released.await(5, TimeUnit.SECONDS)
            throw java.io.IOException("finished")
        }.build()
        val job = launch(Dispatchers.Default) { BangumiDescriptionApi("1", client).fetch(link) }
        assertEquals(true, started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertEquals(true, call!!.isCanceled())
        released.countDown()
    }
    @Test fun enormousRetryAfterSaturatesInsteadOfDisappearing() {
        assertEquals(Long.MAX_VALUE, parseRetryAfter("9".repeat(100), 0L))
    }
    @Test fun malformedUtf8IsRejected() = runBlocking {
        val bytes = "{\"id\":42,\"type\":2,\"summary\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(bytes.toResponseBody()).build()
        }.build()
        assertEquals(DescriptionFetchResult.Failed(DescriptionFailure.INVALID_RESPONSE), BangumiDescriptionApi("1", client).fetch(link))
    }
}
