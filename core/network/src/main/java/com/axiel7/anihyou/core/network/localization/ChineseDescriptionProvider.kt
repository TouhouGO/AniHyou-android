package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.type.MediaType
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.annotation.Single

enum class BangumiMatchSource {
    EXPLICIT_ID,           // From explicit ID in title dictionary (trusted)
    UNIQUE_SEARCH_MATCH,   // From runtime strict search matching exact native title & year & unique
    NONE                   // Failed or ambiguous match
}

@Single
class ChineseDescriptionProvider(
    private val chineseConverter: ChineseConverter? = null,
    private val bundleManager: LocalizationBundleManager? = null,
    private val customHttpClient: OkHttpClient? = null
) {

    @Volatile
    var isEnabled: Boolean = true

    private val infoCache = ConcurrentHashMap<BangumiCacheKey, BangumiMediaInfo>()

    init {
        bundleManager?.registerReloadListener {
            clearCache()
        }
    }

    private val httpClient: OkHttpClient by lazy {
        customHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    data class BangumiMediaInfo(
        val nameCn: String? = null,
        val summary: String? = null,
        val bangumiId: Int? = null,
        val source: BangumiMatchSource = BangumiMatchSource.NONE
    )

    fun clearCache() {
        infoCache.clear()
    }

    fun getCachedSummary(mediaId: Int): String? {
        return infoCache.entries.firstOrNull { it.key.mediaId == mediaId }?.value?.summary
    }

    fun getOrFetchInfo(
        mediaId: Int,
        bangumiId: Int?,
        nativeTitle: String?,
        mediaType: MediaType,
        releaseYear: Int? = null
    ): BangumiMediaInfo {
        val normTitle = nativeTitle?.let { normalizeTitle(it) }
        val cacheKey = BangumiCacheKey(
            mediaId = mediaId,
            explicitBangumiId = bangumiId,
            normalizedTitle = normTitle,
            mediaType = mediaType,
            releaseYear = releaseYear
        )

        val cached = infoCache[cacheKey]
        if (cached != null) {
            return cached
        }

        try {
            var targetBgmId: Int? = null
            var source = BangumiMatchSource.NONE

            if (bangumiId != null && bangumiId > 0) {
                targetBgmId = bangumiId
                source = BangumiMatchSource.EXPLICIT_ID
            } else if (!nativeTitle.isNullOrBlank()) {
                val candidateId = searchBangumiSubjectStrict(nativeTitle, mediaType, releaseYear)
                if (candidateId != null) {
                    targetBgmId = candidateId
                    source = BangumiMatchSource.UNIQUE_SEARCH_MATCH
                }
            }

            if (targetBgmId != null && targetBgmId > 0) {
                val url = "https://api.bgm.tv/v0/subjects/$targetBgmId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "TouhouGO/AniHyou-android (https://github.com/TouhouGO)")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body.string()
                        if (!bodyString.isNullOrBlank()) {
                            val parsed = json.parseToJsonElement(bodyString).jsonObject
                            val rawNameCn = parsed["name_cn"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                            val simplifiedNameCn = rawNameCn?.let { chineseConverter?.toSimplified(it) ?: it }

                            val rawSummary = parsed["summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                            val simplifiedSummary = rawSummary?.let { chineseConverter?.toSimplified(it) ?: it }

                            val formattedSummary = simplifiedSummary?.let { raw ->
                                raw.replace("\r\n", "\n")
                                    .substringBefore("[简介原文]")
                                    .split("\n")
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .joinToString("") { "<p>$it</p>" }
                            }

                            val result = BangumiMediaInfo(
                                nameCn = simplifiedNameCn,
                                summary = formattedSummary,
                                bangumiId = targetBgmId,
                                source = source
                            )
                            infoCache[cacheKey] = result
                            return result
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fail silently on timeout or network errors
        }

        val emptyResult = BangumiMediaInfo(source = BangumiMatchSource.NONE)
        infoCache[cacheKey] = emptyResult
        return emptyResult
    }

    /**
     * Backward compatibility overload for callers passing isAnime: Boolean.
     */
    fun getOrFetchInfo(
        mediaId: Int,
        bangumiId: Int?,
        nativeTitle: String?,
        isAnime: Boolean,
        releaseYear: Int? = null
    ): BangumiMediaInfo {
        val mediaType = if (isAnime) MediaType.ANIME else MediaType.MANGA
        return getOrFetchInfo(
            mediaId = mediaId,
            bangumiId = bangumiId,
            nativeTitle = nativeTitle,
            mediaType = mediaType,
            releaseYear = releaseYear
        )
    }

    internal fun normalizeTitle(title: String): String {
        return BangumiSubjectMatcher.normalizeTitle(title)
    }

    internal fun searchBangumiSubjectStrict(keyword: String, mediaType: MediaType, releaseYear: Int?): Int? {
        return try {
            val encoded = URLEncoder.encode(keyword.trim(), "UTF-8")
            val type = when (mediaType) {
                MediaType.ANIME -> 2
                MediaType.MANGA -> 1
                else -> return null
            }
            val url = "https://api.bgm.tv/search/subject/$encoded?type=$type"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TouhouGO/AniHyou-android (https://github.com/TouhouGO)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body.string()
                    if (!bodyString.isNullOrBlank()) {
                        val candidates = parseCandidates(bodyString)
                        BangumiSubjectMatcher.findUnique(
                            queryTitle = keyword,
                            mediaType = mediaType,
                            releaseYear = releaseYear,
                            candidates = candidates
                        )
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseCandidates(jsonString: String): List<BangumiSubjectCandidate> {
        val parsed = json.parseToJsonElement(jsonString).jsonObject
        val list = parsed["list"] as? JsonArray ?: return emptyList()

        val candidates = mutableListOf<BangumiSubjectCandidate>()
        for (elem in list) {
            val item = elem.jsonObject
            val candType = item["type"]?.jsonPrimitive?.intOrNull
                ?: item["type"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            val id = item["id"]?.jsonPrimitive?.intOrNull
                ?: item["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            val name = item["name"]?.jsonPrimitive?.content.orEmpty()
            val nameCn = item["name_cn"]?.jsonPrimitive?.content.orEmpty()
            val airDate = item["air_date"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

            candidates.add(
                BangumiSubjectCandidate(
                    id = id,
                    type = candType,
                    name = name,
                    nameCn = nameCn,
                    airDate = airDate
                )
            )
        }
        return candidates
    }
}
