package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.type.MediaType
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ChineseDescriptionProviderTest {

    private lateinit var bundleManager: LocalizationBundleManager
    private lateinit var converter: ChineseConverter

    @Before
    fun setUp() {
        bundleManager = LocalizationBundleManager()
        converter = ChineseConverter(bundleManager)
    }

    @Test
    fun testParseCandidatesFromJson() {
        val jsonString = """
            {
              "list": [
                {
                  "id": 12345,
                  "type": 2,
                  "name": "Sousou no Frieren",
                  "name_cn": "葬送的芙莉莲",
                  "air_date": "2023-09-29"
                },
                {
                  "id": 67890,
                  "type": 1,
                  "name": "Sousou no Frieren Manga",
                  "name_cn": "葬送的芙莉莲 漫画",
                  "air_date": "2020-04-28"
                }
              ]
            }
        """.trimIndent()

        val provider = ChineseDescriptionProvider(converter, bundleManager)
        val candidates = provider.parseCandidates(jsonString)

        assertEquals(2, candidates.size)
        assertEquals(12345, candidates[0].id)
        assertEquals(2, candidates[0].type)
        assertEquals("Sousou no Frieren", candidates[0].name)
        assertEquals("葬送的芙莉莲", candidates[0].nameCn)
        assertEquals("2023-09-29", candidates[0].airDate)

        assertEquals(67890, candidates[1].id)
        assertEquals(1, candidates[1].type)
    }

    @Test
    fun testExplicitBangumiIdFetchesDirectly() {
        val subjectJson = """
            {
              "id": 12345,
              "name_cn": "葬送的芙莉蓮",
              "summary": "這是一個關於芙莉蓮的故事。\r\n勇者辛美爾逝世之後……"
            }
        """.trimIndent()

        val mockClient = createMockOkHttpClient { request ->
            assertEquals("https://api.bgm.tv/v0/subjects/12345", request.url.toString())
            createJsonResponse(request, subjectJson)
        }

        val provider = ChineseDescriptionProvider(converter, bundleManager, mockClient)
        val info = provider.getOrFetchInfo(
            mediaId = 1,
            bangumiId = 12345,
            nativeTitle = "葬送のフリーレン",
            mediaType = MediaType.ANIME,
            releaseYear = 2023
        )

        assertEquals(BangumiMatchSource.EXPLICIT_ID, info.source)
        assertEquals(12345, info.bangumiId)
        assertEquals("葬送的芙莉莲", info.nameCn) // Traditional -> Simplified
        assertEquals("<p>这是一个关于芙莉莲的故事。</p><p>勇者辛美尔逝世之后……</p>", info.summary)
    }

    @Test
    fun testSearchMatchResolvesUniqueCandidate() {
        val searchJson = """
            {
              "list": [
                {
                  "id": 99999,
                  "type": 2,
                  "name": "Sousou no Frieren",
                  "name_cn": "葬送的芙莉莲",
                  "air_date": "2023-09-29"
                }
              ]
            }
        """.trimIndent()

        val subjectJson = """
            {
              "id": 99999,
              "name_cn": "葬送的芙莉莲",
              "summary": "魔法使芙莉莲的旅程。"
            }
        """.trimIndent()

        var searchCalled = false
        var subjectCalled = false

        val mockClient = createMockOkHttpClient { request ->
            val url = request.url.toString()
            if (url.contains("/search/subject/")) {
                searchCalled = true
                createJsonResponse(request, searchJson)
            } else if (url.contains("/v0/subjects/99999")) {
                subjectCalled = true
                createJsonResponse(request, subjectJson)
            } else {
                throw IOException("Unexpected URL: $url")
            }
        }

        val provider = ChineseDescriptionProvider(converter, bundleManager, mockClient)
        val info = provider.getOrFetchInfo(
            mediaId = 2,
            bangumiId = null,
            nativeTitle = "Sousou no Frieren",
            mediaType = MediaType.ANIME,
            releaseYear = 2023
        )

        assertEquals(BangumiMatchSource.UNIQUE_SEARCH_MATCH, info.source)
        assertEquals(99999, info.bangumiId)
        assertEquals("葬送的芙莉莲", info.nameCn)
        assertEquals("<p>魔法使芙莉莲的旅程。</p>", info.summary)
    }

    @Test
    fun testCacheHitReturnsEquivalenceWithoutNetwork() {
        var networkCallCount = 0
        val subjectJson = """{"id": 123, "name_cn": "测试", "summary": "简述"}"""

        val mockClient = createMockOkHttpClient { request ->
            networkCallCount++
            createJsonResponse(request, subjectJson)
        }

        val provider = ChineseDescriptionProvider(converter, bundleManager, mockClient)
        val first = provider.getOrFetchInfo(
            mediaId = 10,
            bangumiId = 123,
            nativeTitle = "Test",
            mediaType = MediaType.ANIME,
            releaseYear = 2023
        )
        assertEquals(1, networkCallCount)

        val second = provider.getOrFetchInfo(
            mediaId = 10,
            bangumiId = 123,
            nativeTitle = "Test",
            mediaType = MediaType.ANIME,
            releaseYear = 2023
        )
        assertEquals(1, networkCallCount) // Cache hit!
        assertSame(first, second)
        assertEquals(first.summary, second.summary)
        assertEquals(first.source, second.source)
    }

    @Test
    fun testBundleReloadClearsCache() {
        var networkCallCount = 0
        val subjectJson = """{"id": 123, "name_cn": "测试", "summary": "简述"}"""

        val mockClient = createMockOkHttpClient { request ->
            networkCallCount++
            createJsonResponse(request, subjectJson)
        }

        val provider = ChineseDescriptionProvider(converter, bundleManager, mockClient)
        provider.getOrFetchInfo(
            mediaId = 10,
            bangumiId = 123,
            nativeTitle = "Test",
            mediaType = MediaType.ANIME,
            releaseYear = 2023
        )
        assertEquals(1, networkCallCount)

        // Notify bundle reload
        bundleManager.notifyReload()

        provider.getOrFetchInfo(
            mediaId = 10,
            bangumiId = 123,
            nativeTitle = "Test",
            mediaType = MediaType.ANIME,
            releaseYear = 2023
        )
        // Cache cleared, must call network again
        assertEquals(2, networkCallCount)
    }

    @Test
    fun testCachedSummaryIsIsolatedByAniListMediaId() {
        val mockClient = createMockOkHttpClient { request ->
            val summary = when {
                request.url.toString().endsWith("/subjects/1001") -> "作品一简介"
                request.url.toString().endsWith("/subjects/2002") -> "作品二简介"
                else -> throw IOException("Unexpected URL: ${request.url}")
            }
            createJsonResponse(request, """{"name_cn":"测试","summary":"$summary"}""")
        }
        val provider = ChineseDescriptionProvider(converter, bundleManager, mockClient)

        provider.getOrFetchInfo(
            mediaId = 101,
            bangumiId = 1001,
            nativeTitle = "Work One",
            mediaType = MediaType.ANIME,
            releaseYear = 2020
        )
        provider.getOrFetchInfo(
            mediaId = 202,
            bangumiId = 2002,
            nativeTitle = "Work Two",
            mediaType = MediaType.ANIME,
            releaseYear = 2021
        )

        assertEquals("<p>作品一简介</p>", provider.getCachedSummary(101))
        assertEquals("<p>作品二简介</p>", provider.getCachedSummary(202))
        assertNull(provider.getCachedSummary(999))
    }

    @Test
    fun testBangumiOriginalLanguageAppendixIsRemovedFromChineseSummary() {
        val subjectJson = """
            {
              "name_cn": "测试作品",
              "summary": "第一段中文简介。\n第二段中文简介。\n\n[简介原文]\nこれは日本語の原文です。"
            }
        """.trimIndent()
        val mockClient = createMockOkHttpClient { request ->
            createJsonResponse(request, subjectJson)
        }
        val provider = ChineseDescriptionProvider(converter, bundleManager, mockClient)

        val info = provider.getOrFetchInfo(
            mediaId = 303,
            bangumiId = 3003,
            nativeTitle = "Test",
            mediaType = MediaType.ANIME,
            releaseYear = 2022
        )

        assertEquals("<p>第一段中文简介。</p><p>第二段中文简介。</p>", info.summary)
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
