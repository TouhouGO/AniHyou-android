package com.axiel7.anihyou.core.network

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.axiel7.anihyou.core.network.api.MediaApi
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaSort
import com.axiel7.anihyou.core.network.type.MediaType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCandidateQueryTest {
    @Test
    fun candidateIdsReplaceOnlySearchTextAndPreserveServerFilters() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        client(requests).use { client ->
            search(MediaApi(client), "\u706b\u5f71", listOf(20, 1735, 20)).execute()
        }
        val variables = requests.single()["variables"]!!.jsonObject
        assertEquals(listOf("20", "1735"), variables["id_in"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(variables.containsKey("search"))
        assertEquals("2", variables["page"]!!.jsonPrimitive.content)
        assertEquals("25", variables["perPage"]!!.jsonPrimitive.content)
        assertEquals("ANIME", variables["type"]!!.jsonPrimitive.content)
        assertEquals("POPULARITY_DESC", variables["sort"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("Action", variables["genre_in"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("Time Manipulation", variables["tag_in"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("TV", variables["format_in"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("false", variables["isAdult"]!!.jsonPrimitive.content)
    }

    @Test
    fun nativeQueryPreservesTextAndDoesNotInjectIds() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        client(requests).use { client -> search(MediaApi(client), "Cowboy Bebop", null).execute() }
        val variables = requests.single()["variables"]!!.jsonObject
        assertEquals("Cowboy Bebop", variables["search"]!!.jsonPrimitive.content)
        assertFalse(variables.containsKey("id_in"))
    }

    @Test
    fun fullCandidateArraysUseTheDocumentedLimit() = runBlocking {
        for (count in listOf(501, 10_000)) {
            val requests = mutableListOf<JsonObject>()
            client(requests).use { client -> search(MediaApi(client), "query", (1..count).toList()).execute() }
            val ids = requests.single()["variables"]!!.jsonObject["id_in"]!!.jsonArray
            assertEquals(count, ids.size)
            assertEquals(count.toString(), ids.last().jsonPrimitive.content)
        }
    }

    @Test
    fun invalidCandidateInputsCannotBecomeUnrestrictedQueries() {
        val requests = mutableListOf<JsonObject>()
        client(requests).use { client ->
            for (ids in listOf(emptyList(), listOf(0), (1..10_001).toList())) {
                val failed = runCatching { search(MediaApi(client), "query", ids) }.isFailure
                assertTrue("Invalid IDs must fail before a request is made", failed)
            }
        }
        assertTrue(requests.isEmpty())
    }

    private fun search(api: MediaApi, text: String, ids: List<Int>?) = api.searchMediaQuery(
        mediaType = MediaType.ANIME,
        query = text,
        sort = listOf(MediaSort.POPULARITY_DESC),
        genreIn = listOf("Action"),
        genreNotIn = null,
        tagIn = listOf("Time Manipulation"),
        tagNotIn = null,
        minimumTagPercentage = null,
        formatIn = listOf(MediaFormat.TV),
        statusIn = null,
        episodesLesser = null,
        episodesGreater = null,
        durationLesser = null,
        durationGreater = null,
        chaptersLesser = null,
        chaptersGreater = null,
        volumesLesser = null,
        volumesGreater = null,
        weightedScoreLesser = null,
        weightedScoreGreater = null,
        startYear = null,
        endYear = null,
        season = null,
        onList = null,
        isLicensed = null,
        isAdult = false,
        country = null,
        sourceIn = null,
        page = 2,
        perPage = 25,
        mediaIds = ids,
    )

    private fun client(requests: MutableList<JsonObject>): ApolloClient {
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests.add(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"data":{"Page":{"media":[],"pageInfo":{"currentPage":2,"hasNextPage":false}}}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return ApolloClient.Builder().serverUrl("https://graphql.anilist.co")
            .okHttpClient(httpClient).build()
    }
}
