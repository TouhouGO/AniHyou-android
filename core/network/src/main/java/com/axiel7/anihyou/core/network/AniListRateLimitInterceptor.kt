package com.axiel7.anihyou.core.network

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Prevents repeated AniList calls during a server-advertised rate-limit window. */
class AniListRateLimitInterceptor(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Interceptor {
    private val blockedUntilMillis = AtomicLong(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val now = nowMillis()
        val remainingMillis = blockedUntilMillis.get() - now
        if (remainingMillis > 0) {
            val retrySeconds = (remainingMillis + 999) / 1000
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", retrySeconds.toString())
                .body(
                    """{"errors":[{"message":"Too Many Requests."}]}"""
                        .toResponseBody("application/json; charset=utf-8".toMediaType())
                )
                .build()
        }

        val response = chain.proceed(chain.request())
        val waitMillis = when {
            response.code == 429 -> {
                val retrySeconds = response.header("Retry-After")?.toLongOrNull()
                TimeUnit.SECONDS.toMillis((retrySeconds ?: DEFAULT_RETRY_SECONDS).coerceIn(1, MAX_RETRY_SECONDS))
            }

            response.header("X-RateLimit-Remaining") == "0" -> {
                val resetSeconds = response.header("X-RateLimit-Reset")?.toLongOrNull()
                resetSeconds?.let { TimeUnit.SECONDS.toMillis(it) - nowMillis() }
                    ?.coerceIn(0, TimeUnit.SECONDS.toMillis(MAX_RETRY_SECONDS)) ?: 0
            }

            else -> 0
        }
        if (waitMillis > 0) {
            val deadline = nowMillis() + waitMillis
            blockedUntilMillis.updateAndGet { maxOf(it, deadline) }
        }
        return response
    }

    private companion object {
        const val DEFAULT_RETRY_SECONDS = 60L
        const val MAX_RETRY_SECONDS = 300L
    }
}
