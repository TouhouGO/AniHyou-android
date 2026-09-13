package com.axiel7.anihyou.core.network.localization

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseEntityNameResolverTest {

    private fun jsonResponse(request: Request, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    @Test
    fun testKanaMixedNameNotTruncatedEarlyByCjkFallback() = runBlocking {
        // Reproduce "藍華・S・グランチェスタ" (Aika S. Granzchesta):
        // It contains Kanji "藍華" mixed with Katakana "グランチェスタ".
        // Old buggy behavior: hasCjk("藍華・S・グランチェスタ") returned true, so it prematurely returned "蓝华・S・グランチェスタ".
        // Expected behavior: strict CJK check rejects it; resolver queries Wikidata/Bangumi to find proper Chinese name "爱ika..." or Bangumi translation.
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val body = when (req.url.encodedPath) {
                    "/v0/subjects/123/characters" ->
                        """[{"id":456,"name":"藍華・S・グランチェスタ","actors":[]}]"""
                    "/v0/characters/456" ->
                        """{"infobox":[{"key":"简体中文名","value":"蓝华·S·葛兰契斯塔"}]}"""
                    else -> "{}"
                }
                jsonResponse(req, body)
            }
            .build()

        val tempDir = Files.createTempDirectory("resolver_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            val converter = ChineseConverter(bundleManager)
            val cache = EntityNameCache()
            cache.setStorageFile(File(tempDir, "cache.json"))
            val wikidataSource = WikidataEntityNameSource(client, converter)

            val resolver = ChineseEntityNameResolver(
                bundleManager = bundleManager,
                entityNameCache = cache,
                wikidataSource = wikidataSource,
                chineseConverter = converter,
                customHttpClient = client
            )

            val resolved = resolver.resolve(
                subjectId = 123,
                kind = BangumiEntityKind.CHARACTER,
                refs = listOf(
                    BangumiEntityRef(
                        id = 9999,
                        nativeName = "藍華・S・グランチェスタ"
                    )
                )
            )

            assertEquals("蓝华·S·葛兰契斯塔", resolved[9999])
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testResolverPipelinePriorityBundleThenCacheThenWikidataThenBangumiThenStrictCjk() = runBlocking {
        val tempDir = Files.createTempDirectory("priority_test").toFile()
        try {
            val bundleManager = LocalizationBundleManager()
            val converter = ChineseConverter(bundleManager)
            val cache = EntityNameCache()
            cache.setStorageFile(File(tempDir, "cache.json"))

            // Seed cache with ID 9000010
            cache.putPositive(BangumiEntityKind.CHARACTER, 9000010, "缓存命中名字")

            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    val body = when {
                        req.url.host.contains("wikidata.org") ->
                            """{"results":{"bindings":[{"id":{"type":"literal","value":"9000020"},"itemLabel":{"type":"literal","value":"维基数据名字","xml:lang":"zh-cn"}}]}}"""
                        req.url.encodedPath == "/v0/subjects/1/characters" ->
                            """[{"id":300,"name":"番组原名","actors":[]}]"""
                        req.url.encodedPath == "/v0/characters/300" ->
                            """{"infobox":[{"key":"简体中文名","value":"番组数据名字"}]}"""
                        else -> "{}"
                    }
                    jsonResponse(req, body)
                }
                .build()

            val wikidataSource = WikidataEntityNameSource(client, converter)
            val resolver = ChineseEntityNameResolver(
                bundleManager = bundleManager,
                entityNameCache = cache,
                wikidataSource = wikidataSource,
                chineseConverter = converter,
                customHttpClient = client
            )

            // 1. Bundle lookup: ID 19878 in staff_characters_zh_cn.json is "绪方理奈"
            // 2. Cache lookup: ID 9000010 is "缓存命中名字"
            // 3. Wikidata lookup: ID 9000020 is "维基数据名字"
            // 4. Bangumi lookup: ID 9000030 with nativeName "番组原名" is "番组数据名字"
            // 5. Strict CJK fallback: ID 9000040 with nativeName "森川由綺" is converted to "森川由绮"
            // 6. Untranslatable foreign name without CJK: ID 9000050 with "John Doe" returns null
            val refs = listOf(
                BangumiEntityRef(19878, "緒方理奈"),
                BangumiEntityRef(9000010, "任意名字"),
                BangumiEntityRef(9000020, "Wikidata Target"),
                BangumiEntityRef(9000030, "番组原名"),
                BangumiEntityRef(9000040, "森川由綺"),
                BangumiEntityRef(9000050, "John Doe")
            )

            val resolved = resolver.resolve(subjectId = 1, kind = BangumiEntityKind.CHARACTER, refs = refs)

            assertEquals("绪方理奈", resolved[19878])
            assertEquals("缓存命中名字", resolved[9000010])
            assertEquals("维基数据名字", resolved[9000020])
            assertEquals("番组数据名字", resolved[9000030])
            assertEquals("森川由绮", resolved[9000040])
            assertNull(resolved[9000050])
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
