package com.axiel7.anihyou.core.network.localization

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koin.core.annotation.Single

data class BangumiSearchResult(
    val bangumiId: Int,
    val name: String,
    val nameCn: String?,
    val type: Int,
    val airDate: String? = null,
)

@Single
class BangumiSearchProvider(
    private val chineseConverter: ChineseConverter? = null,
    private val customHttpClient: OkHttpClient? = null,
) {

    private val connectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    private val httpClient: OkHttpClient by lazy {
        customHttpClient ?: OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(2000, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val cache = BoundedLruCache<String, List<BangumiSearchResult>>(maxSize = 400)
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(4)

    @Volatile
    var isEnabled: Boolean = true

    fun clearCache() {
        cache.clear()
    }

    /**
     * Pre-warms the HTTP connection to api.bgm.tv in the background so that
     * subsequent searches don't spend 1-2s on cold TLS handshakes.
     */
    fun prewarmConnection() {
        if (!isEnabled) return
        try {
            val request = Request.Builder()
                .url("https://api.bgm.tv/search/subject/test?type=2")
                .header("User-Agent", "TouhouGO/AniHyou-android (https://github.com/TouhouGO)")
                .header("Accept", "application/json")
                .head()
                .build()
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        } catch (_: Exception) {}
    }

    /**
     * Searches a specific page of Bangumi subjects by offset.
     *
     * @param query The search keyword.
     * @param isAnime true for Anime (type 2), false for Manga/Book (type 1).
     * @param start The offset index (0, 25, 50...).
     * @param maxResults Number of items per batch (up to 25).
     */
    fun searchBangumiPage(
        query: String,
        isAnime: Boolean = true,
        start: Int = 0,
        maxResults: Int = 25
    ): List<BangumiSearchResult> {
        if (!isEnabled || query.isBlank()) return emptyList()

        val trimmed = query.trim()
        val targetType = if (isAnime) 2 else 1
        val cacheKey = "$targetType:$trimmed:$start"

        val cached = cache.get(cacheKey)
        if (cached != null) {
            return cached
        }

        return try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://api.bgm.tv/search/subject/$encoded?type=$targetType&start=$start&max_results=$maxResults"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TouhouGO/AniHyou-android (https://github.com/TouhouGO)")
                .header("Accept", "application/json")
                .build()

            val t0 = System.currentTimeMillis()
            httpClient.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - t0
                if (!response.isSuccessful) {
                    println("BANGUMI_SEARCH: HTTP error ${response.code} after ${elapsed}ms for query: $trimmed (start=$start)")
                    return emptyList()
                }

                val bodyString = response.body.string()
                if (bodyString.isBlank()) {
                    return emptyList()
                }

                val parsed = json.parseToJsonElement(bodyString).jsonObject
                val list = parsed["list"] as? JsonArray ?: return emptyList()

                val results = mutableListOf<BangumiSearchResult>()
                for (elem in list) {
                    val item = elem.jsonObject
                    val id = item["id"]?.jsonPrimitive?.intOrNull
                        ?: item["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                    val type = item["type"]?.jsonPrimitive?.intOrNull
                        ?: item["type"]?.jsonPrimitive?.content?.toIntOrNull() ?: targetType
                    // Strict type check: anime must be 2, manga/book must be 1
                    if (type != targetType) {
                        continue
                    }
                    val name = item["name"]?.jsonPrimitive?.content.orEmpty()
                    val rawNameCn = item["name_cn"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    val simplifiedNameCn = rawNameCn?.let {
                        chineseConverter?.toSimplified(it) ?: it
                    }
                    val airDate = item["air_date"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

                    results.add(
                        BangumiSearchResult(
                            bangumiId = id,
                            name = name,
                            nameCn = simplifiedNameCn,
                            type = type,
                            airDate = airDate,
                        )
                    )
                }

                println("BANGUMI_SEARCH: Found ${results.size} candidates (start=$start) in ${elapsed}ms for query: $trimmed")
                cache.put(cacheKey, results)
                results
            }
        } catch (e: Exception) {
            println("BANGUMI_SEARCH: Gracefully degraded (failed: ${e.message}) for query: $trimmed (start=$start)")
            emptyList()
        }
    }

    /**
     * Searches Bangumi subjects by keyword and media type with adaptive multi-batch fetching.
     * If the first batch has fewer than 25 items, subsequent batches are skipped to conserve network bandwidth.
     *
     * @param query The Chinese or Japanese title keyword.
     * @param isAnime true for Anime (type 2), false for Manga/Book (type 1).
     * @param maxBatches Number of 25-item batches to query (default 2, yielding up to 50 candidates).
     * @return List of matched subjects, or empty if timed out or failed.
     */
    fun searchBangumi(
        query: String,
        isAnime: Boolean = true,
        maxBatches: Int = 2
    ): List<BangumiSearchResult> {
        if (!isEnabled || query.isBlank()) return emptyList()

        val firstBatch = searchBangumiPage(query, isAnime, start = 0, maxResults = 25)
        if (maxBatches <= 1 || firstBatch.size < 25) {
            return firstBatch
        }

        val remainingFutures = (1 until maxBatches).map { batchIndex ->
            java.util.concurrent.CompletableFuture.supplyAsync({
                searchBangumiPage(query, isAnime, start = batchIndex * 25, maxResults = 25)
            }, executor)
        }

        val combined = ArrayList<BangumiSearchResult>(firstBatch.size + 25 * (maxBatches - 1))
        combined.addAll(firstBatch)
        for (f in remainingFutures) {
            try {
                combined.addAll(f.get(2000, TimeUnit.MILLISECONDS))
            } catch (_: Exception) {}
        }
        return combined.distinctBy { it.bangumiId }
    }

    private class BoundedLruCache<K, V>(private val maxSize: Int = 200) {
        private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > maxSize
            }
        }

        @Synchronized
        fun get(key: K): V? = map[key]

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = value
        }

        @Synchronized
        fun clear() {
            map.clear()
        }
    }
}
