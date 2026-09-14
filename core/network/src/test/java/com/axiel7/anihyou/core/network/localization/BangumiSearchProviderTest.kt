package com.axiel7.anihyou.core.network.localization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class BangumiSearchProviderTest {

    private lateinit var bundleManager: LocalizationBundleManager
    private lateinit var converter: ChineseConverter
    private lateinit var titleProvider: ChineseTitleProvider

    @Before
    fun setUp() {
        bundleManager = LocalizationBundleManager()
        converter = ChineseConverter(bundleManager)
        titleProvider = ChineseTitleProvider(bundleManager, converter)
    }

    @Test
    fun testFindMediaIdsByBangumiIdMapsCorrectly() {
        // Sagrada Reset: Bangumi ID 193378 -> AniList ID 97660
        val anilistIds = titleProvider.findMediaIdsByBangumiId(193378)
        assertTrue("Bangumi ID 193378 should map to AniList ID 97660", anilistIds.contains(97660))
    }

    @Test
    fun testBangumiSearchProviderParsesAndCaches() {
        var networkCallCount = 0
        val fakeJson = """
            {
              "results": 1,
              "list": [
                {
                  "id": 193378,
                  "type": 2,
                  "name": "サクラダリセット",
                  "name_cn": "重啓咲良田",
                  "air_date": "2017-04-05"
                }
              ]
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                networkCallCount++
                val request = chain.request()
                val decodedUrl = URLDecoder.decode(request.url.toString(), "UTF-8")
                assertTrue("URL should query Sagrada Reset", decodedUrl.contains("重启咲良田"))
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(fakeJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val provider = BangumiSearchProvider(chineseConverter = converter, customHttpClient = mockClient)

        // 1. First call: hits network
        val results = provider.searchBangumi("重启咲良田", isAnime = true)
        assertEquals(1, results.size)
        assertEquals(1, networkCallCount)
        val first = results[0]
        assertEquals(193378, first.bangumiId)
        assertEquals("サクラダリセット", first.name)
        assertEquals("重启咲良田", first.nameCn) // Converted to simplified
        assertEquals(2, first.type)

        // 2. Second call: hits LRU cache (0 network calls)
        val cachedResults = provider.searchBangumi("重启咲良田", isAnime = true)
        assertEquals(1, cachedResults.size)
        assertEquals(1, networkCallCount) // Call count remains 1!
    }

    @Test
    fun testChineseTitleInterceptorRewritesWithBangumiWhenLocalNotFound() {
        val fakeBangumiJson = """
            {
              "results": 1,
              "list": [
                {
                  "id": 193378,
                  "type": 2,
                  "name": "サクラダリセット",
                  "name_cn": "重启咲良田",
                  "air_date": "2017-04-05"
                }
              ]
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(fakeBangumiJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val bangumiSearch = BangumiSearchProvider(chineseConverter = converter, customHttpClient = mockClient)

        val rawRequestJson = """
            {
              "operationName": "SearchMedia",
              "query": "query SearchMedia(${'$'}search: String, ${'$'}type: MediaType) { Page { media(search: ${'$'}search, type: ${'$'}type) { id title { userPreferred } } } }",
              "variables": {
                "search": "重启咲良田",
                "type": "ANIME"
              }
            }
        """.trimIndent()

        val element = Json.parseToJsonElement(rawRequestJson)
        val rewritten = ChineseTitleInterceptor.rewriteRequest(
            element = element,
            titleProvider = titleProvider,
            bangumiSearchProvider = bangumiSearch
        )

        val vars = (rewritten as JsonObject)["variables"] as JsonObject
        val idIn = vars["id_in"] as JsonArray
        assertTrue("id_in should be injected with AniList ID 97660", idIn.any { it.toString() == "97660" })
    }

    @Test
    fun testChineseTitleInterceptorDirectlyInjectsBangumiMatchesOnNonExactSearch() {
        val fakeBangumiJson = """
            {
              "results": 1,
              "list": [
                {
                  "id": 193378,
                  "type": 2,
                  "name": "サクラダリセット",
                  "name_cn": "重启咲良田"
                }
              ]
            }
        """.trimIndent()

        val mockBangumiClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(fakeBangumiJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val bangumiSearch = BangumiSearchProvider(chineseConverter = converter, customHttpClient = mockBangumiClient)
        val interceptor = ChineseTitleInterceptor(
            titleProvider = titleProvider,
            bangumiSearchProvider = bangumiSearch
        )

        var anilistCallCount = 0
        var firstInjectedIds: String? = null

        val fakeChain = object : Interceptor.Chain {
            private var currentRequest = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(
                    """
                    {
                      "operationName": "SearchMedia",
                      "query": "query SearchMedia(${'$'}search: String, ${'$'}type: MediaType) { Page { media(search: ${'$'}search, type: ${'$'}type) { id title { userPreferred } } } }",
                      "variables": {
                        "search": "重启",
                        "type": "ANIME"
                      }
                    }
                    """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()

            override fun request(): Request = currentRequest

            override fun proceed(request: Request): Response {
                anilistCallCount++
                currentRequest = request
                val bodyStr = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
                firstInjectedIds = bodyStr

                val responseJson = """{"data":{"Page":{"media":[{"id":97660,"title":{"userPreferred":"Sagrada Reset"}}]}}}"""

                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }

            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 10000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 10000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 10000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val finalResponse = interceptor.intercept(fakeChain)
        val responseBody = finalResponse.body.string()

        // Verify that on non-exact search, Bangumi matches are injected directly without a wasted roundtrip
        assertEquals(1, anilistCallCount)
        assertNotNull(firstInjectedIds)
        assertTrue("First AniList call should already contain 97660", firstInjectedIds!!.contains("97660"))
        assertTrue("Final localized response should contain 咲良田重置 or 重启咲良田",
            responseBody.contains("咲良田") || responseBody.contains("重启咲良田"))
    }

    @Test
    fun testChineseTitleInterceptorEndToEndRetriesWhenLocalResultsAreEmpty() {
        val fakeBangumiJson = """
            {
              "results": 1,
              "list": [
                {
                  "id": 193378,
                  "type": 2,
                  "name": "サクラダリセット",
                  "name_cn": "重启咲良田"
                }
              ]
            }
        """.trimIndent()

        val mockBangumiClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(fakeBangumiJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val bangumiSearch = BangumiSearchProvider(chineseConverter = converter, customHttpClient = mockBangumiClient)
        // Set exact match for "重启" to simulate exact match pointing to manga ID
        titleProvider.updateTitle(122315, "重启")

        val interceptor = ChineseTitleInterceptor(
            titleProvider = titleProvider,
            bangumiSearchProvider = bangumiSearch
        )

        var anilistCallCount = 0
        var lastInjectedIds: String? = null

        val fakeChain = object : Interceptor.Chain {
            private var currentRequest = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(
                    """
                    {
                      "operationName": "SearchMedia",
                      "query": "query SearchMedia(${'$'}search: String, ${'$'}type: MediaType) { Page { media(search: ${'$'}search, type: ${'$'}type) { id title { userPreferred } } } }",
                      "variables": {
                        "search": "重启",
                        "type": "ANIME"
                      }
                    }
                    """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()

            override fun request(): Request = currentRequest

            override fun proceed(request: Request): Response {
                anilistCallCount++
                currentRequest = request
                val bodyStr = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
                lastInjectedIds = bodyStr

                val responseJson = if (anilistCallCount == 1) {
                    // First call: local exact match (122315 is manga), but type=ANIME -> empty list!
                    """{"data":{"Page":{"media":[]}}}"""
                } else {
                    // Second call: retry with Bangumi mapped ID (97660) -> success!
                    """{"data":{"Page":{"media":[{"id":97660,"title":{"userPreferred":"Sagrada Reset"}}]}}}"""
                }

                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }

            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 10000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 10000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 10000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val finalResponse = interceptor.intercept(fakeChain)
        val responseBody = finalResponse.body.string()

        // Verify that it retried when local exact match returned empty media
        assertEquals(2, anilistCallCount)
        assertNotNull(lastInjectedIds)
        assertTrue("Second AniList call should contain 97660", lastInjectedIds!!.contains("97660"))
        assertTrue("Final localized response should contain 咲良田重置 or 重启咲良田",
            responseBody.contains("咲良田") || responseBody.contains("重启咲良田"))
    }

    @Test
    fun testMultiBatchSearchFetchesSecondBatchWhenFirstBatchIsFull() {
        var callCount = 0

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount++
                val request = chain.request()
                val url = request.url.toString()
                val listSize = if (url.contains("start=0")) 25 else 10

                val itemsJson = (0 until listSize).joinToString(",") { i ->
                    val id = if (url.contains("start=0")) 1000 + i else 2000 + i
                    """{"id": $id, "type": 2, "name": "Item $id", "name_cn": "条目 $id"}"""
                }
                val body = """{"results": 100, "list": [$itemsJson]}"""

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val provider = BangumiSearchProvider(chineseConverter = converter, customHttpClient = mockClient)
        val results = provider.searchBangumi("测试", isAnime = true, maxBatches = 2)

        // Since batch 0 returned 25 items, batch 1 was also fetched!
        assertEquals(35, results.size)
        assertEquals(2, callCount)
    }

    @Test
    fun testTitleProviderLearnsBangumiNameAndOverridesCardTitle() {
        val tp = ChineseTitleProvider(bundleManager, converter)
        tp.updateTitle(97660, "咲良田重置", 193378)
        assertEquals("咲良田重置", tp.getTitle(97660))

        // Learn updated community title from Bangumi
        tp.learnTitleFromBangumi(97660, "重启咲良田", 193378)
        assertEquals("重启咲良田", tp.getTitle(97660))

        val mediaJson = """
            {
              "id": 97660,
              "title": {
                "userPreferred": "Sagrada Reset",
                "romaji": "Sagrada Reset"
              }
            }
        """.trimIndent()
        val rewritten = ChineseTitleInterceptor.rewriteMediaTitles(
            Json.parseToJsonElement(mediaJson),
            tp
        )
        val userPreferred = rewritten.jsonObject["title"]?.jsonObject?.get("userPreferred")?.jsonPrimitive?.content
        assertEquals("重启咲良田", userPreferred)
    }

    @Test
    fun testRelevanceRankingSortsDirectKeywordMatchesFirst() {
        val tp = ChineseTitleProvider(bundleManager, converter)
        tp.updateTitle(101, "日常搞笑番")
        tp.updateTitle(102, "重启咲良田")
        tp.updateTitle(103, "普通冒险故事")
        tp.updateTitle(104, "大运动会 重启！")

        val candidates = listOf(101, 103, 102, 104)
        val ranked = ChineseTitleInterceptor.rankCandidatesByRelevance(candidates, "重启", tp)

        // 102 and 104 both contain "重启", so they must be ranked at the front!
        assertEquals(listOf(102, 104), ranked.take(2))
    }

    @Test
    fun testSessionCacheSupportsSeamlessPagination() {
        val tp = ChineseTitleProvider(bundleManager, converter)
        tp.updateTitle(201, "重启第一部", 1)
        tp.updateTitle(202, "重启第二部", 2)
        tp.updateTitle(203, "重启第三部", 3)

        ChineseTitleInterceptor.clearSearchSessionCache()

        // Page 1
        val reqPage1 = """
            {
              "query": "query SearchMedia(${'$'}page: Int, ${'$'}search: String) { Page(page: ${'$'}page) { media(search: ${'$'}search) { id } } }",
              "variables": {
                "search": "重启",
                "page": 1,
                "perPage": 2,
                "type": "ANIME"
              }
            }
        """.trimIndent()

        val rewriteResult1 = ChineseTitleInterceptor.rewriteRequestWithContext(
            Json.parseToJsonElement(reqPage1),
            rawJson = reqPage1,
            titleProvider = tp
        )
        val vars1 = rewriteResult1.element.jsonObject["variables"]?.jsonObject
        val idIn1 = vars1?.get("id_in")?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
        assertNotNull(idIn1)
        assertTrue(idIn1!!.contains(201))

        // Page 2: should retrieve from session cache and inject the same candidate list!
        val reqPage2 = """
            {
              "query": "query SearchMedia(${'$'}page: Int, ${'$'}search: String) { Page(page: ${'$'}page) { media(search: ${'$'}search) { id } } }",
              "variables": {
                "search": "重启",
                "page": 2,
                "perPage": 2,
                "type": "ANIME"
              }
            }
        """.trimIndent()

        val rewriteResult2 = ChineseTitleInterceptor.rewriteRequestWithContext(
            Json.parseToJsonElement(reqPage2),
            rawJson = reqPage2,
            titleProvider = tp
        )
        val vars2 = rewriteResult2.element.jsonObject["variables"]?.jsonObject
        val idIn2 = vars2?.get("id_in")?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
        assertNotNull(idIn2)
        assertEquals(idIn1, idIn2) // id_in list preserved for seamless AniList pagination!
    }

    @Test
    fun testBangumiCandidateFilteringRemovesNoise() {
        val tp = ChineseTitleProvider(bundleManager, converter)
        tp.updateTitle(97660, "重启咲良田", 193378)
        tp.updateTitle(113425, "回复术士的重启人生", 295017)
        tp.updateTitle(164299, "重启人生的千金小姐正在攻略龙帝陛下", 432597)
        tp.updateTitle(120534, "搏斗运动员们 大运动会 重启！", 309331)
        tp.updateTitle(156841, "名侦探柯南 黑铁的鱼影", 407515)
        tp.updateTitle(114811, "数码宝贝大冒险：", 298451)

        // Matches
        val sakurada = BangumiSearchResult(193378, "サクラダリセット", "重启咲良田", 2)
        val healer = BangumiSearchResult(295017, "回復術士のやり直し", "回复术士的重来人生", 2)
        val villainess = BangumiSearchResult(432597, "やり直し令嬢は竜帝陛下を攻略中", "重启人生的千金小姐正在攻略龙帝陛下", 2)
        val athletes = BangumiSearchResult(309331, "バトルアスリーテス大運動会 ReSTART!", "搏斗运动员们 大运动会 重启！", 2)

        assertTrue(ChineseTitleInterceptor.isSubjectRelevantToQuery(sakurada, "重启", tp, isAnime = true))
        assertTrue(ChineseTitleInterceptor.isSubjectRelevantToQuery(healer, "重启", tp, isAnime = true))
        assertTrue(ChineseTitleInterceptor.isSubjectRelevantToQuery(villainess, "重启", tp, isAnime = true))
        assertTrue(ChineseTitleInterceptor.isSubjectRelevantToQuery(athletes, "重启", tp, isAnime = true))

        // Noise entries that must be rejected!
        val conan = BangumiSearchResult(407515, "名探偵コナン 黒鉄の魚影", "名侦探柯南 黑铁的鱼影", 2)
        val digimon = BangumiSearchResult(298451, "デジモンアドベンチャー：", "数码宝贝大冒险：", 2)
        val estabLife = BangumiSearchResult(366253, "エスタブライフ グレイトエスケープ", "Estab-Life Great Escape", 2)
        val maou = BangumiSearchResult(234134, "魔王様、リトライ！", "重来吧、魔王大人！", 1)
        val jk = BangumiSearchResult(318057, "JKからやり直すシルバープラン", "重返JK：Silver Plan", 1)

        assertFalse(ChineseTitleInterceptor.isSubjectRelevantToQuery(conan, "重启", tp, isAnime = true))
        assertFalse(ChineseTitleInterceptor.isSubjectRelevantToQuery(digimon, "重启", tp, isAnime = true))
        assertFalse(ChineseTitleInterceptor.isSubjectRelevantToQuery(estabLife, "重启", tp, isAnime = true))
        assertFalse(ChineseTitleInterceptor.isSubjectRelevantToQuery(maou, "重启", tp, isAnime = false))
        assertFalse(ChineseTitleInterceptor.isSubjectRelevantToQuery(jk, "重启", tp, isAnime = false))
    }

    @Test
    fun testRewriteMediaTitlesFiltersNoiseAndSortsByRelevance() {
        val tp = ChineseTitleProvider(bundleManager, converter)
        tp.updateTitle(97660, "重启咲良田", 193378)
        tp.updateTitle(113425, "回复术士的重启人生", 295017)
        tp.updateTitle(120534, "搏斗运动员们 大运动会 重启！", 309331)
        tp.updateTitle(87487, "机动警察 REBOOT", 198246)
        tp.updateTitle(156841, "名侦探柯南 黑铁的鱼影", 407515)
        tp.updateTitle(114811, "数码宝贝大冒险：", 298451)

        // AniList returns items in ID ASC order (87487, 97660, 113425, 114811, 120534, 156841)
        val responseJson = """
            {
              "data": {
                "Page": {
                  "media": [
                    {
                      "id": 87487,
                      "title": { "userPreferred": "Kidou Keisatsu Patlabor REBOOT", "romaji": "Kidou Keisatsu Patlabor REBOOT" }
                    },
                    {
                      "id": 97660,
                      "title": { "userPreferred": "Sagrada Reset", "romaji": "Sagrada Reset" }
                    },
                    {
                      "id": 113425,
                      "title": { "userPreferred": "Kaifuku Jutsushi no Yarinaoshi", "romaji": "Kaifuku Jutsushi no Yarinaoshi" }
                    },
                    {
                      "id": 114811,
                      "title": { "userPreferred": "Digimon Adventure:", "romaji": "Digimon Adventure:" }
                    },
                    {
                      "id": 120534,
                      "title": { "userPreferred": "Battle Athletess Daiundoukai: ReSTART!", "romaji": "Battle Athletess Daiundoukai: ReSTART!" }
                    },
                    {
                      "id": 156841,
                      "title": { "userPreferred": "Meitantei Conan: Kurogane no Submarine", "romaji": "Meitantei Conan: Kurogane no Submarine" }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val rewritten = ChineseTitleInterceptor.rewriteMediaTitles(
            Json.parseToJsonElement(responseJson),
            provider = tp,
            searchKeyword = "重启",
        )

        val mediaArray = rewritten.jsonObject["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray
        assertNotNull(mediaArray)

        // 1. Noise items (Conan, Digimon) must be FILTERED OUT!
        val ids = mediaArray!!.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull }
        assertFalse("Conan (156841) should be filtered out", ids.contains(156841))
        assertFalse("Digimon (114811) should be filtered out", ids.contains(114811))

        // 2. Titles starting with 重启 (重启咲良田) must be ranked #1!
        val titles = mediaArray.mapNotNull { it.jsonObject["title"]?.jsonObject?.get("userPreferred")?.jsonPrimitive?.content }
        assertEquals("重启咲良田", titles[0])

        // 3. Titles containing 重启 (回复术士, 大运动会) must precede REBOOT
        assertTrue(titles.contains("回复术士的重启人生"))
        assertTrue(titles.contains("搏斗运动员们 大运动会 重启！"))
        assertEquals("机动警察 REBOOT", titles.last())
    }

    @Test
    fun testPreserveRedoOfHealerRestartTitle() {
        val tp = ChineseTitleProvider(bundleManager, converter)
        tp.updateTitle(113425, "回复术士的重启人生", 295017)
        assertEquals("回复术士的重启人生", tp.getTitle(113425))

        // Bangumi might have "回复术士的重来人生", but we must NOT downgrade "重启"
        tp.learnTitleFromBangumi(113425, "回复术士的重来人生", 295017)
        assertEquals("回复术士的重启人生", tp.getTitle(113425))
    }

    @Test
    fun testBroadQueryLikeMonogatariUsesLocalFirstAndSkipsBangumi() {
        var bangumiCallCount = 0
        val mockBangumiClient = OkHttpClient.Builder()
            .addInterceptor { _ ->
                bangumiCallCount++
                throw AssertionError("Bangumi should not be called when local dictionary has >= 10 matches!")
            }
            .build()

        val bangumiSearch = BangumiSearchProvider(chineseConverter = converter, customHttpClient = mockBangumiClient)
        val tp = ChineseTitleProvider(bundleManager, converter)

        // Verify local has >= 10 matches for "物语"
        val localMatches = tp.findMediaIdsByChineseTitle("物语", limit = 100)
        assertTrue("Local database should have >= 10 Monogatari entries", localMatches.size >= 10)

        ChineseTitleInterceptor.clearSearchSessionCache()

        val req = """
            {
              "operationName": "SearchMedia",
              "query": "query SearchMedia(${'$'}search: String, ${'$'}type: MediaType) { Page { media(search: ${'$'}search, type: ${'$'}type) { id title { userPreferred } } } }",
              "variables": {
                "search": "物语",
                "type": "ANIME"
              }
            }
        """.trimIndent()

        val rewriteResult = ChineseTitleInterceptor.rewriteRequestWithContext(
            Json.parseToJsonElement(req),
            rawJson = req,
            titleProvider = tp,
            bangumiSearchProvider = bangumiSearch
        )

        // Verify that Bangumi was NOT called (0 network overhead!)
        assertEquals(0, bangumiCallCount)

        val vars = rewriteResult.element.jsonObject["variables"]?.jsonObject
        val idIn = vars?.get("id_in")?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
        assertNotNull(idIn)
        assertTrue("id_in should contain Monogatari items", idIn!!.isNotEmpty())
        assertTrue("wasLocallyMatched should be true", rewriteResult.searchContext?.wasLocallyMatched == true)
    }
}
