package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.fragment.CommonVoiceActor
import com.axiel7.anihyou.core.network.fragment.MediaCharacter
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

class ChineseCharacterBangumiResolverTest {

    @Test
    fun testResolvesCharacterAndVoiceActorByUniqueNativeName() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = createClient { request ->
            requestedUrls += request.url.toString()
            val body = when (request.url.encodedPath) {
                "/v0/subjects/55770/characters" ->
                    """[{"id":77,"name":"ベルトルト・フーバー","actors":[{"id":88,"name":"橋詰知久"}]}]"""
                "/v0/subjects/55770/persons" -> "[]"
                "/v0/characters/77" ->
                    """{"infobox":[{"key":"简体中文名","value":"贝尔托特·胡佛"}]}"""
                "/v0/persons/88" ->
                    """{"infobox":[{"key":"简体中文名","value":"桥诘知久"}]}"""
                else -> throw IOException("Unexpected URL: ${request.url}")
            }
            response(request, body)
        }
        val provider = ChineseCharacterProvider(
            LocalizationBundleManager(),
            ChineseConverter(LocalizationBundleManager()),
            client
        )

        val characters = provider.resolveBangumiNames(
            bangumiSubjectId = 55770,
            kind = BangumiEntityKind.CHARACTER,
            entities = listOf(BangumiEntityRef(9000001, "ベルトルト・フーバー"))
        )
        val people = provider.resolveBangumiNames(
            bangumiSubjectId = 55770,
            kind = BangumiEntityKind.PERSON,
            entities = listOf(BangumiEntityRef(9000002, "橋詰知久"))
        )

        assertEquals(mapOf(9000001 to "贝尔托特·胡佛"), characters)
        assertEquals(mapOf(9000002 to "桥诘知久"), people)
        assertTrue(requestedUrls.any { it.endsWith("/v0/characters/77") })
        assertTrue(requestedUrls.any { it.endsWith("/v0/persons/88") })
    }

    @Test
    fun testRejectsAmbiguousNativeNameWithoutFetchingEntityDetails() = runBlocking {
        var detailRequests = 0
        val client = createClient { request ->
            when {
                request.url.encodedPath == "/v0/subjects/123/characters" -> response(
                    request,
                    """[{"id":7,"name":"同名"},{"id":8,"name":"同名"}]"""
                )
                request.url.encodedPath.startsWith("/v0/characters/") || request.url.encodedPath.startsWith("/v0/persons/") -> {
                    detailRequests++
                    response(request, "{}")
                }
                else -> response(request, "{}")
            }
        }
        val manager = LocalizationBundleManager()
        val provider = ChineseCharacterProvider(manager, ChineseConverter(manager), client)

        val result = provider.resolveBangumiNames(
            bangumiSubjectId = 123,
            kind = BangumiEntityKind.CHARACTER,
            entities = listOf(BangumiEntityRef(9000009, "同名"))
        )

        assertTrue(result.isEmpty())
        assertEquals(0, detailRequests)
    }

    @Test
    fun testLocalizesGeneratedCharacterAndVoiceActorBeforeRendering() = runBlocking {
        val requestedPaths = mutableListOf<String>()
        val client = createClient { request ->
            requestedPaths += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/v0/subjects/55770/characters" ->
                    """[{"id":77,"name":"ベルトルト・フーバー","actors":[{"id":88,"name":"橋詰知久"}]}]"""
                "/v0/subjects/55770/persons" -> "[]"
                "/v0/characters/77" ->
                    """{"infobox":[{"key":"简体中文名","value":"贝尔托特·胡佛"}]}"""
                "/v0/persons/88" ->
                    """{"infobox":[{"key":"简体中文名","value":"桥诘知久"}]}"""
                else -> throw IOException("Unexpected URL: ${request.url}")
            }
            response(request, body)
        }
        val manager = LocalizationBundleManager()
        val provider = ChineseCharacterProvider(manager, ChineseConverter(manager), client)
        val input = MediaCharacter(
            __typename = "CharacterEdge",
            role = null,
            node = MediaCharacter.Node(
                __typename = "Character",
                id = 9_000_001,
                name = MediaCharacter.Name(
                    __typename = "CharacterName",
                    userPreferred = "Bertolt Hoover",
                    native = "Bertolt Hoover",
                    alternative = emptyList()
                ),
                image = null
            ),
            voiceActors = listOf(
                MediaCharacter.VoiceActor(
                    __typename = "Staff",
                    id = 9_000_002,
                    commonVoiceActor = CommonVoiceActor(
                        __typename = "Staff",
                        id = 9_000_002,
                        name = CommonVoiceActor.Name(
                            __typename = "StaffName",
                            userPreferred = "Tomohisa Hashizume",
                            native = "橋詰知久",
                            alternative = emptyList()
                        ),
                        image = null,
                        languageV2 = "Japanese"
                    )
                )
            )
        )

        val result = provider.localizeMediaCharacters(55770, listOf(input)).single()

        assertEquals("贝尔托特·胡佛", result.node?.name?.userPreferred)
        assertEquals(
            "桥诘知久",
            result.voiceActors?.single()?.commonVoiceActor?.name?.userPreferred
        )
        assertEquals(1, requestedPaths.count { it == "/v0/subjects/55770/characters" })
    }

    private fun createClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build()

    private fun response(request: Request, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
}
