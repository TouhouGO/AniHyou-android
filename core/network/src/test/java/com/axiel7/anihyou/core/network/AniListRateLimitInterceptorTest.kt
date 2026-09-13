package com.axiel7.anihyou.core.network

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class AniListRateLimitInterceptorTest {
    private val request = Request.Builder().url("https://graphql.anilist.co").build()

    @Test
    fun retryAfterStopsRepeatedRequestsUntilDeadline() {
        var now = 1_000_000L
        val interceptor = AniListRateLimitInterceptor { now }
        val chain = FakeChain(request) {
            response(429, "Too Many Requests.", mapOf("Retry-After" to "30"))
        }

        assertEquals(429, interceptor.intercept(chain).use { it.code })
        assertEquals(429, interceptor.intercept(chain).use { it.code })
        assertEquals(1, chain.proceedCount)

        now += 30_000L
        assertEquals(429, interceptor.intercept(chain).use { it.code })
        assertEquals(2, chain.proceedCount)
    }

    @Test
    fun zeroRemainingUsesServerResetAndDoesNotReplayRequest() {
        var now = 1_000_000L
        val interceptor = AniListRateLimitInterceptor { now }
        val chain = FakeChain(request) {
            response(200, "{}", mapOf(
                "X-RateLimit-Remaining" to "0",
                "X-RateLimit-Reset" to "1010",
            ))
        }

        assertEquals(200, interceptor.intercept(chain).use { it.code })
        assertEquals(429, interceptor.intercept(chain).use { it.code })
        assertEquals(1, chain.proceedCount)

        now += 10_000L
        assertEquals(200, interceptor.intercept(chain).use { it.code })
        assertEquals(2, chain.proceedCount)
    }

    @Test
    fun missingRetryHeaderUsesBoundedCooldown() {
        var now = 1_000_000L
        val interceptor = AniListRateLimitInterceptor { now }
        val chain = FakeChain(request) { response(429, "Too Many Requests.") }

        interceptor.intercept(chain).close()
        now += 59_999L
        interceptor.intercept(chain).close()
        assertEquals(1, chain.proceedCount)

        now += 1L
        interceptor.intercept(chain).close()
        assertEquals(2, chain.proceedCount)
    }

    private fun response(code: Int, body: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 429) "Too Many Requests" else "OK")
            .body(body.toResponseBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private class FakeChain(
        private val req: Request,
        private val responder: () -> Response,
    ) : Interceptor.Chain {
        var proceedCount = 0

        override fun request(): Request = req
        override fun proceed(request: Request): Response {
            proceedCount++
            return responder()
        }
        override fun connection() = null
        override fun call() = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 10000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 10000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 10000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }
}
