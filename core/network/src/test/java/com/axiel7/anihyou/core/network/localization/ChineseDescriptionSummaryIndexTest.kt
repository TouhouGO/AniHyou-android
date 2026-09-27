package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.type.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChineseDescriptionSummaryIndexTest {

    private lateinit var bundleManager: LocalizationBundleManager
    private lateinit var converter: ChineseConverter

    @Before
    fun setUp() {
        bundleManager = LocalizationBundleManager()
        converter = ChineseConverter(bundleManager)
    }

    @Test
    fun testGetCachedSummaryReturnsSummaryWrittenByGetOrFetchInfo() {
        val subjectJson = """
            {
              "id": 12345,
              "name_cn": "葬送的芙莉蓮",
              "summary": "這是一個關於芙莉蓮的故事。"
            }
        """.trimIndent()

        val mockClient = createMockOkHttpClient { request ->
            createJsonResponse(request, subjectJson)
        }

        val provider = ChineseDescriptionProvider(converter, bundleManager, mockClient)

        assertNull(provider.getCachedSummary(1))

        val info = provider.getOrFetchInfo(
            mediaId = 1,
            bangumiId = 12345,
            nativeTitle = "葬送のフリーレン",
            mediaType = MediaType.ANIME,
            releaseYear = 2023
        )

        assertEquals("<p>这是一个关于芙莉莲的故事。</p>", info.summary)
        assertEquals(info.summary, provider.getCachedSummary(1))
        // Unknown media ids stay absent from the reverse index.
        assertNull(provider.getCachedSummary(2))
    }

    @Test
    fun testClearCacheRemovesSummaryFromIndex() {
        val subjectJson = """
            {
              "id": 12345,
              "name_cn": "葬送的芙莉莲",
              "summary": "简述"
            }
        """.trimIndent()

        val mockClient = createMockOkHttpClient { request ->
            createJsonResponse(request, subjectJson)
        }

        val provider = ChineseDescriptionProvider(converter, bundleManager, mockClient)
        provider.getOrFetchInfo(
            mediaId = 10,
            bangumiId = 12345,
            nativeTitle = "Test",
            mediaType = MediaType.ANIME,
            releaseYear = 2023
        )
        assertEquals("<p>简述</p>", provider.getCachedSummary(10))

        provider.clearCache()

        assertNull(provider.getCachedSummary(10))
    }

    private fun createJsonResponse(request: Request, json: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun createMockOkHttpClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                handler(chain.request())
            }
            .build()
    }
}
