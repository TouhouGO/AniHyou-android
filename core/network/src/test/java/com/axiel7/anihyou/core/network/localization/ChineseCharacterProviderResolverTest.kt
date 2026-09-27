package com.axiel7.anihyou.core.network.localization

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseCharacterProviderResolverTest {

    private fun providerWith(
        staffJson: String,
        client: OkHttpClient? = null,
    ): ChineseCharacterProvider {
        val dir = Files.createTempDirectory("char_provider_test").toFile()
        dir.deleteOnExit()
        File(dir, "staff_characters_zh_cn.json").writeText(staffJson)
        File(dir, "t2s_char_map.json").writeText("{}")
        File(dir, "titles_zh_cn.json").writeText("{}")
        val bm = LocalizationBundleManager().apply { setStorageDirectory(dir) }
        return ChineseCharacterProvider(bm, ChineseConverter(bm), customHttpClient = client)
    }

    private val bangumiClient = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        val body = when (req.url.encodedPath) {
            "/v0/subjects/1/characters" ->
                """[{"id":77,"name":"テスト","actors":[]}]"""
            "/v0/characters/77" ->
                """{"infobox":[{"key":"简体中文名","value":"测试角色"}]}"""
            else -> "{}"
        }
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }.build()

    private val emptyBangumiClient = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("[]".toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }.build()

    @Test
    fun builtInStaffMappingHitsWithoutNetwork() {
        val failing = OkHttpClient.Builder().addInterceptor {
            throw AssertionError("built-in hit must not touch the network")
        }.build()
        val provider = providerWith("""{"person_123":"花泽香菜","char_456":"安乐冈花火"}""", failing)

        assertEquals("花泽香菜", provider.getChineseStaffOrVoiceActor(123, "Hanazawa Kana"))
        assertEquals("安乐冈花火", provider.getChineseCharacter(456, "Hanabi"))
    }

    @Test
    fun runtimeCacheIsReadBySynchronousGetters() = runBlocking {
        val provider = providerWith("{}", bangumiClient)
        val resolved = provider.resolveBangumiNames(
            bangumiSubjectId = 1,
            kind = BangumiEntityKind.CHARACTER,
            entities = listOf(BangumiEntityRef(id = 999, nativeName = "テスト", aliases = emptyList())),
        )
        resolved[999]?.let { assertEquals(it, provider.getChineseCharacter(999, "テスト")) }
        assertEquals("测试角色", provider.getChineseCharacter(999, "テスト"))
    }

    @Test
    fun unresolvableEntityReturnsNullWithoutThrowing() = runBlocking {
        val provider = providerWith("{}", emptyBangumiClient)
        val resolved = provider.resolveBangumiNames(
            1,
            BangumiEntityKind.CHARACTER,
            listOf(BangumiEntityRef(999, "テスト", emptyList()))
        )
        assertTrue(resolved.isEmpty())
        assertNull(provider.getChineseCharacter(999, "テスト"))
    }

    @Test
    fun bangumiHttpErrorDegradesGracefully() = runBlocking {
        val errorClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(500).message("Server Error")
                .body("boom".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = providerWith("{}", errorClient)
        val resolved = provider.resolveBangumiNames(
            1,
            BangumiEntityKind.PERSON,
            listOf(BangumiEntityRef(999, "テスト", emptyList()))
        )
        assertTrue("HTTP 500 must not throw and must not resolve", resolved.isEmpty())
        assertNull(provider.getChineseStaffOrVoiceActor(999, "テスト"))
    }

    @Test
    fun reloadClearsBothCaches() = runBlocking {
        val dir = Files.createTempDirectory("char_provider_reload").toFile()
        dir.deleteOnExit()
        val staffFile = File(dir, "staff_characters_zh_cn.json")
        staffFile.writeText("""{"person_1":"初始名"}""")
        File(dir, "t2s_char_map.json").writeText("{}")
        File(dir, "titles_zh_cn.json").writeText("{}")
        val bm = LocalizationBundleManager().apply { setStorageDirectory(dir) }
        val provider = ChineseCharacterProvider(bm, ChineseConverter(bm), customHttpClient = bangumiClient)

        assertEquals("初始名", provider.getChineseStaffOrVoiceActor(1, "x"))

        // Also populate runtime cache
        provider.resolveBangumiNames(
            bangumiSubjectId = 1,
            kind = BangumiEntityKind.CHARACTER,
            entities = listOf(BangumiEntityRef(id = 999, nativeName = "テスト", aliases = emptyList())),
        )
        assertEquals("测试角色", provider.getChineseCharacter(999, "テスト"))

        // Modify file and notify reload
        staffFile.writeText("""{"person_1":"更新名"}""")
        bm.notifyReload()

        // Both staff mapping cache and runtime cache should be cleared/reloaded
        assertEquals("更新名", provider.getChineseStaffOrVoiceActor(1, "x"))
        assertNull(provider.getChineseCharacter(999, "テスト"))
    }

    @Test
    fun japaneseKanaIsNotConvertedToChinese() {
        val provider = providerWith("{}")
        // 纯假名 → isStrictCjkOnly 拒绝 → 返回 null
        assertNull(provider.getChineseCharacter(1, "ラブライブ"))
    }

    @Test
    fun nullAndBlankInputsAreSafe() {
        val provider = providerWith("{}")
        assertNull(provider.getChineseCharacter(null, null))
        assertNull(provider.getChineseStaffOrVoiceActor(null, null))
        assertNull(provider.getChineseCharacter(1, null, null, emptyList()))
    }
}
