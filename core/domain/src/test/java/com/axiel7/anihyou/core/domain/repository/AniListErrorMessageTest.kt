package com.axiel7.anihyou.core.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AniListErrorMessageTest {
    @Test
    fun mapsHttp429ToActionableChineseMessage() {
        assertEquals(
            "AniList 请求过于频繁，请稍后重试",
            aniListErrorMessage(429, "Too Many Requests."),
        )
    }

    @Test
    fun mapsGraphQlRateLimitMessageWithoutHttpStatus() {
        assertEquals(
            "AniList 请求过于频繁，请稍后重试",
            aniListErrorMessage(null, "Error(message=Too Many Requests.)"),
        )
    }

    @Test
    fun preservesOtherServerErrors() {
        assertEquals("Invalid token", aniListErrorMessage(401, "Invalid token"))
    }
}
