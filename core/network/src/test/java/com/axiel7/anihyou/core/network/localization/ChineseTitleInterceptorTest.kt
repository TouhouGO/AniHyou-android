package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.base.ANILIST_GRAPHQL_URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseTitleInterceptorTest {

    private val bundleManager = LocalizationBundleManager()
    private val converter = ChineseConverter(bundleManager)
    private val tagProvider = ChineseTagProvider(bundleManager)
    private val characterProvider = ChineseCharacterProvider(bundleManager, converter)
    private val titleProvider = ChineseTitleProvider(bundleManager)
    private val descProvider = ChineseDescriptionProvider(converter)

    @Test
    fun `test ChineseConverter converts Traditional Chinese to Simplified Chinese`() {
        val traditionalText = "Leaf社於1998年發售的18X美少女PC用AVG「WHITE ALBUM」，就當時來說、該遊戲的情節性、音樂和人物刻畫可算是比較優秀的鎮社之作。"
        val simplified = converter.toSimplified(traditionalText)
        val expected = "Leaf社于1998年发售的18X美少女PC用AVG「WHITE ALBUM」，就当时来说、该游戏的情节性、音乐和人物刻画可算是比较优秀的镇社之作。"
        assertEquals(expected, simplified)

        assertEquals("水树奈奈", converter.toSimplified("水樹奈奈"))
        assertEquals("户松遥", converter.toSimplified("戸松遥"))
    }

    @Test
    fun `test ChineseDescriptionProvider title normalization`() {
        assertEquals("clannad", descProvider.normalizeTitle("CLANNAD"))
        assertEquals("clannadafterstory", descProvider.normalizeTitle("CLANNAD 〜AFTER STORY〜"))
        assertEquals("clannadafterstory", descProvider.normalizeTitle("CLANNAD: After Story"))
        assertEquals("进击的巨人", descProvider.normalizeTitle("进击的巨人！"))
    }

    @Test
    fun `test ChineseTagProvider translates anime and manga tags`() {
        assertEquals("三角恋", tagProvider.getChineseTag("Love Triangle"))
        assertEquals("假扮情侣", tagProvider.getChineseTag("Fake Relationship"))
        assertEquals("成长", tagProvider.getChineseTag("Coming of Age"))
        assertEquals("异性恋", tagProvider.getChineseTag("Heterosexual"))
        assertEquals("性心理", tagProvider.getChineseTag("Psychosexual"))
        assertEquals("女主角", tagProvider.getChineseTag("Female Protagonist"))
        assertEquals("校园", tagProvider.getChineseTag("School"))
        assertEquals("年龄差", tagProvider.getChineseTag("Age Gap"))
    }

    @Test
    fun `test ChineseCharacterProvider translates voice actors and characters`() {
        assertEquals("户松遥", characterProvider.getChineseStaffOrVoiceActor(95890, "Haruka Tomatsu"))
        assertEquals("水树奈奈", characterProvider.getChineseStaffOrVoiceActor(95081, "Nana Mizuki"))
        assertEquals("花泽香菜", characterProvider.getChineseStaffOrVoiceActor(95185, "Kana Hanazawa"))
        assertEquals("关智一", characterProvider.getChineseStaffOrVoiceActor(95001, "Tomokazu Seki"))

        // Test native Japanese Kanji to Simplified Chinese conversion
        assertEquals("绪方理奈", characterProvider.getChineseCharacter(19878, "Rina Ogata", nativeName = "緒方理奈"))
        assertEquals("森川由绮", characterProvider.getChineseCharacter(19877, "Yuki Morikawa", nativeName = "森川由綺"))
        assertEquals("藤井冬弥", characterProvider.getChineseCharacter(19876, "Touya Fujii", nativeName = "藤井冬弥"))

        // Test Wikidata ID mapping
        assertEquals("艾连·叶卡", characterProvider.getChineseCharacter(40882, "Eren Yeager"))

        // Test alternative aliases with Chinese CJK when ID/name is not in dictionary
        assertEquals("艾伦·耶格尔", characterProvider.getChineseCharacter(99999, "Unknown Person", nativeName = "エレン", alternative = listOf("艾伦·耶格尔")))
    }

    @Test
    fun `test ChineseTitleProvider loads titles from resources`() {
        assertTrue("Provider should load more than 20,000 titles", titleProvider.size > 20000)

        // Test Attack on Titan (16498) - Anime
        val aotTitle = titleProvider.getTitle(16498)
        assertNotNull("Attack on Titan title should not be null", aotTitle)
        assertEquals("进击的巨人", aotTitle)

        // Test Frieren (154587) - Anime
        val frierenTitle = titleProvider.getTitle(154587)
        assertNotNull("Frieren title should not be null", frierenTitle)
        assertEquals("葬送的芙莉莲", frierenTitle)

        // Test Girlfriend Girlfriend (116266) - Manga
        val kanojoTitle = titleProvider.getTitle(116266)
        assertNotNull("Kanojo mo Kanojo title should not be null", kanojoTitle)
        assertEquals("女友成双", kanojoTitle)

        // Test Oshi no Ko (117195) - Manga
        val oshiTitle = titleProvider.getTitle(117195)
        assertNotNull("Oshi no Ko title should not be null", oshiTitle)
        assertEquals("【我推的孩子】", oshiTitle)

        // Test Berserk (30002) - Manga (Mainland title & Bangumi ID)
        val berserkTitle = titleProvider.getTitle(30002)
        assertEquals("剑风传奇", berserkTitle)
        assertEquals(Integer.valueOf(9640), titleProvider.getBangumiId(30002))
        assertEquals(Integer.valueOf(55770), titleProvider.getBangumiId(16498))

        // Test Native Fallback
        val fallbackTitle = titleProvider.getTitle(-1, nativeTitle = "カノジョも彼女")
        assertEquals("女友成双", fallbackTitle)
    }

    @Test
    fun `test rewriteMediaTitles translates titles, tags, and characters simultaneously`() {
        val sampleJson = """
            {
              "data": {
                "Media": {
                  "id": 16498,
                  "type": "ANIME",
                  "title": {
                    "userPreferred": "Shingeki no Kyojin",
                    "native": "進撃の巨人"
                  },
                  "description": "Leaf社於1998年發售的18X美少女PC用AVG「WHITE ALBUM」，就當時來說、該遊戲的情節性、音樂和人物刻畫可算是比較優秀的鎮社之作。",
                  "tags": [
                    { "id": 1, "name": "Love Triangle" },
                    { "id": 2, "name": "School" },
                    { "id": 3, "name": "Age Gap" }
                  ],
                  "characters": {
                    "edges": [
                      {
                        "role": "MAIN",
                        "node": {
                          "id": 19878,
                          "name": {
                            "userPreferred": "Rina Ogata",
                            "native": "緒方理奈"
                          }
                        },
                        "voiceActors": [
                          {
                            "id": 95081,
                            "name": {
                              "userPreferred": "Nana Mizuki",
                              "native": "水樹奈々"
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = Json.parseToJsonElement(sampleJson)
        val rewritten = ChineseTitleInterceptor.rewriteMediaTitles(
            element = parsed,
            provider = titleProvider,
            descriptionProvider = descProvider,
            tagProvider = tagProvider,
            characterProvider = characterProvider,
            chineseConverter = converter
        )

        val mediaObj = rewritten.jsonObject["data"]!!.jsonObject["Media"]!!.jsonObject
        val titleObj = mediaObj["title"]!!.jsonObject
        assertEquals("进击的巨人", titleObj["userPreferred"]?.jsonPrimitive?.content)

        // Check description simplified
        val descStr = mediaObj["description"]!!.jsonPrimitive.content
        assertTrue("Description should contain simplified Chinese", descStr.contains("发售") && descStr.contains("该游戏"))

        // Check tags translation
        val tagsArray = mediaObj["tags"] as JsonArray
        assertEquals("三角恋", tagsArray[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("校园", tagsArray[1].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("年龄差", tagsArray[2].jsonObject["name"]?.jsonPrimitive?.content)

        // Check character & voice actor translation
        val charEdge = (mediaObj["characters"]!!.jsonObject["edges"] as JsonArray)[0].jsonObject
        val charNode = charEdge["node"]!!.jsonObject
        assertEquals("绪方理奈", charNode["name"]!!.jsonObject["userPreferred"]?.jsonPrimitive?.content)

        val vaNode = (charEdge["voiceActors"] as JsonArray)[0].jsonObject
        assertEquals("水树奈奈", vaNode["name"]!!.jsonObject["userPreferred"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test cached Chinese summary replaces rather than appends original description`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"name_cn":"测试作品","summary":"中文简介"}"""
                            .toResponseBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()
            }
            .build()
        val cachedDescriptionProvider = ChineseDescriptionProvider(converter, bundleManager, client)
        cachedDescriptionProvider.getOrFetchInfo(
            mediaId = 456,
            bangumiId = 789,
            nativeTitle = "Test",
            mediaType = com.axiel7.anihyou.core.network.type.MediaType.ANIME,
            releaseYear = 2024
        )
        val parsed = Json.parseToJsonElement(
            """{"id":456,"description":"English synopsis."}"""
        )

        val rewritten = ChineseTitleInterceptor.rewriteMediaTitles(
            element = parsed,
            provider = titleProvider,
            descriptionProvider = cachedDescriptionProvider,
            chineseConverter = converter
        )

        assertEquals(
            "<p><strong>【剧情简介】</strong></p><p>中文简介</p>",
            rewritten.jsonObject["description"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `test ChineseTagProvider reverse mapping from Chinese to English`() {
        assertEquals("Shounen", tagProvider.getEnglishTag("少年向"))
        assertEquals("Love Triangle", tagProvider.getEnglishTag("三角恋"))
        assertEquals("Female Protagonist", tagProvider.getEnglishTag("女主角"))
        assertEquals("School", tagProvider.getEnglishTag("校园"))
        assertEquals("Age Gap", tagProvider.getEnglishTag("年龄差"))

        // Already English should stay English
        assertEquals("Shounen", tagProvider.getEnglishTag("Shounen"))
        assertEquals("Love Triangle", tagProvider.getEnglishTag("Love Triangle"))
    }

    @Test
    fun `test rewriteRequestVariables translates tag_in tag_not_in and genre_in back to English`() {
        val requestJson = """
            {
              "operationName": "SearchMedia",
              "variables": {
                "page": 1,
                "perPage": 25,
                "type": "ANIME",
                "tag_in": ["少年向", "女主角"],
                "tag_not_in": ["年龄差"],
                "genre_in": ["动作", "奇幻"],
                "genre_not_in": ["恐怖"]
              },
              "query": "query SearchMedia { ... }"
            }
        """.trimIndent()

        val parsed = Json.parseToJsonElement(requestJson)
        val rewritten = ChineseTitleInterceptor.rewriteRequestVariables(parsed, tagProvider)

        val vars = rewritten.jsonObject["variables"]!!.jsonObject

        val tagIn = (vars["tag_in"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("Shounen", "Female Protagonist"), tagIn)

        val tagNotIn = (vars["tag_not_in"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("Age Gap"), tagNotIn)

        val genreIn = (vars["genre_in"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("Action", "Fantasy"), genreIn)

        val genreNotIn = (vars["genre_not_in"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("Horror"), genreNotIn)

        // Verify other fields remain intact
        assertEquals(1, vars["page"]?.jsonPrimitive?.content?.toInt())
        assertEquals("ANIME", vars["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test rewriteMediaTitles translates MediaTagCollection and UserStats tags`() {
        val tagCollectionJson = """
            {
              "data": {
                "GenreCollection": ["Action", "Romance"],
                "MediaTagCollection": [
                  { "id": 1, "name": "Shounen" },
                  { "id": 2, "name": "Love Triangle" }
                ]
              }
            }
        """.trimIndent()

        val parsed = Json.parseToJsonElement(tagCollectionJson)
        val rewritten = ChineseTitleInterceptor.rewriteMediaTitles(
            element = parsed,
            provider = titleProvider,
            tagProvider = tagProvider
        )

        val mediaTags = rewritten.jsonObject["data"]!!.jsonObject["MediaTagCollection"] as JsonArray
        assertEquals("少年向", mediaTags[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("三角恋", mediaTags[1].jsonObject["name"]?.jsonPrimitive?.content)

        // Test UserStats nested tag structure
        val userStatsJson = """
            {
              "data": {
                "User": {
                  "statistics": {
                    "anime": {
                      "tags": [
                        {
                          "tag": {
                            "id": 1,
                            "name": "Shounen"
                          },
                          "count": 10
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val parsedStats = Json.parseToJsonElement(userStatsJson)
        val rewrittenStats = ChineseTitleInterceptor.rewriteMediaTitles(
            element = parsedStats,
            provider = titleProvider,
            tagProvider = tagProvider
        )

        val statTags = rewrittenStats.jsonObject["data"]!!.jsonObject["User"]!!.jsonObject["statistics"]!!.jsonObject["anime"]!!.jsonObject["tags"] as JsonArray
        val statTag = statTags[0].jsonObject["tag"]!!.jsonObject
        assertEquals("少年向", statTag["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test interceptor rewrites valid GraphQL response`() {
        val interceptor = ChineseTitleInterceptor(
            titleProvider = titleProvider,
            tagProvider = tagProvider,
            characterProvider = characterProvider,
            descriptionProvider = descProvider,
            chineseConverter = converter
        )

        val jsonResponse = """
            {
              "data": {
                "Media": {
                  "id": 16498,
                  "type": "ANIME",
                  "title": { "userPreferred": "Shingeki no Kyojin", "native": "進撃の巨人" }
                }
              }
            }
        """.trimIndent()

        val request = Request.Builder().url(ANILIST_GRAPHQL_URL).build()
        val originalResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(jsonResponse.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val chain = FakeChain(request, originalResponse)
        val resultResponse = interceptor.intercept(chain)

        val bodyString = resultResponse.body.string()
        assertTrue("Rewritten response should contain Chinese title", bodyString.contains("进击的巨人"))
    }

    @Test
    fun `test interceptor returns readable unconsumed body when json is malformed`() {
        val interceptor = ChineseTitleInterceptor(
            titleProvider = titleProvider,
            tagProvider = tagProvider,
            characterProvider = characterProvider,
            descriptionProvider = descProvider,
            chineseConverter = converter
        )

        // Contains "userPreferred" to pass fast skip filter, but invalid JSON structure
        val malformedJson = """{"userPreferred": [unclosed array"""

        val request = Request.Builder().url(ANILIST_GRAPHQL_URL).build()
        val originalResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(malformedJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val chain = FakeChain(request, originalResponse)
        val resultResponse = interceptor.intercept(chain)

        // Must be able to read the response body without IllegalStateException
        val bodyString = resultResponse.body.string()
        assertEquals(malformedJson, bodyString)
    }

    @Test
    fun `test interceptor passes non-GraphQL URLs untouched`() {
        val interceptor = ChineseTitleInterceptor(
            titleProvider = titleProvider,
            tagProvider = tagProvider
        )

        val sampleText = "some raw text"
        val request = Request.Builder().url("https://api.example.com/other").build()
        val originalResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(sampleText.toResponseBody("text/plain".toMediaType()))
            .build()

        val chain = FakeChain(request, originalResponse)
        val resultResponse = interceptor.intercept(chain)

        assertEquals(sampleText, resultResponse.body.string())
    }

    private class FakeChain(
        private val req: Request,
        private val resp: Response
    ) : Interceptor.Chain {
        override fun request(): Request = req
        override fun proceed(request: Request): Response = resp
        override fun connection() = null
        override fun call() = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 10000
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis() = 10000
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis() = 10000
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }
}
