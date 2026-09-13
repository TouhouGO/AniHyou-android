package com.axiel7.anihyou.core.network.localization

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikidataEntityNameSourceTest {

    private fun mockClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
    }

    private fun jsonResponse(request: Request, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/sparql-results+json; charset=utf-8".toMediaType()))
            .build()
    }

    @Test
    fun testResolvesCharacterWithP11736AndChineseLabels() = runBlocking {
        val sparqlResponse = """
            {
              "results": {
                "bindings": [
                  {
                    "id": { "type": "literal", "value": "19878" },
                    "itemLabel": { "type": "literal", "value": "绪方理奈", "xml:lang": "zh-cn" }
                  }
                ]
              }
            }
        """.trimIndent()

        var queriedUrl: String? = null
        val client = mockClient { req ->
            queriedUrl = req.url.toString()
            jsonResponse(req, sparqlResponse)
        }

        val source = WikidataEntityNameSource(client)
        val result = source.load(BangumiEntityKind.CHARACTER, listOf(19878))

        assertEquals(mapOf(19878 to "绪方理奈"), result.names)
        assertEquals(emptySet<Int>(), result.missIds)
        assertTrue(queriedUrl?.contains("P11736") == true)
        assertTrue(queriedUrl?.contains("19878") == true)
    }

    @Test
    fun testResolvesPersonWithP11227AndConvertsTraditionalToSimplified() = runBlocking {
        val sparqlResponse = """
            {
              "results": {
                "bindings": [
                  {
                    "id": { "type": "literal", "value": "95081" },
                    "itemLabel": { "type": "literal", "value": "水樹奈奈", "xml:lang": "zh-tw" }
                  }
                ]
              }
            }
        """.trimIndent()

        var queriedUrl: String? = null
        val client = mockClient { req ->
            queriedUrl = req.url.toString()
            jsonResponse(req, sparqlResponse)
        }

        val converter = ChineseConverter(LocalizationBundleManager())
        val source = WikidataEntityNameSource(client, converter)
        val result = source.load(BangumiEntityKind.PERSON, listOf(95081))

        assertEquals(mapOf(95081 to "水树奈奈"), result.names)
        assertTrue(queriedUrl?.contains("P11227") == true)
    }

    @Test
    fun testDiscardsUnrequestedIdsAndIdentifiesMissIds() = runBlocking {
        val sparqlResponse = """
            {
              "results": {
                "bindings": [
                  {
                    "id": { "type": "literal", "value": "100" },
                    "itemLabel": { "type": "literal", "value": "测试名字", "xml:lang": "zh" }
                  },
                  {
                    "id": { "type": "literal", "value": "999" },
                    "itemLabel": { "type": "literal", "value": "非法额外ID", "xml:lang": "zh" }
                  }
                ]
              }
            }
        """.trimIndent()

        val client = mockClient { req -> jsonResponse(req, sparqlResponse) }
        val source = WikidataEntityNameSource(client)

        // Requesting 100 and 200. 100 is found; 200 is miss; 999 was not requested so must be discarded.
        val result = source.load(BangumiEntityKind.CHARACTER, listOf(100, 200))

        assertEquals(mapOf(100 to "测试名字"), result.names)
        assertEquals(setOf(200), result.missIds)
    }

    @Test
    fun testChunksBatchRequestsAt30Ids() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = mockClient { req ->
            requestedUrls.add(req.url.toString())
            jsonResponse(req, """{"results":{"bindings":[]}}""")
        }

        val source = WikidataEntityNameSource(client)
        val ids = (1..65).toList()
        val result = source.load(BangumiEntityKind.CHARACTER, ids)

        // 65 IDs with chunk size 30 should produce 3 batches: 30, 30, 5
        assertEquals(3, requestedUrls.size)
        assertEquals(ids.toSet(), result.missIds)
    }

    @Test
    fun testNetworkFailureDoesNotReportMissIds() = runBlocking {
        val client = mockClient { throw IOException("Connection reset") }
        val source = WikidataEntityNameSource(client)

        val result = source.load(BangumiEntityKind.CHARACTER, listOf(123))

        assertTrue(result.names.isEmpty())
        assertTrue(result.missIds.isEmpty()) // Network failure should NOT write negative cache
        assertTrue(result.hasError)
    }
}
