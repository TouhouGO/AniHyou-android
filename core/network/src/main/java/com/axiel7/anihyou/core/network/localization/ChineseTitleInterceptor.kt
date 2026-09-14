package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.base.ANILIST_GRAPHQL_URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class ChineseTitleInterceptor(
    private val titleProvider: ChineseTitleProvider,
    private val descriptionProvider: ChineseDescriptionProvider? = null,
    private val tagProvider: ChineseTagProvider? = null,
    private val characterProvider: ChineseCharacterProvider? = null,
    private val chineseConverter: ChineseConverter? = null,
    private val bangumiSearchProvider: BangumiSearchProvider? = null,
) : Interceptor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()

        val isAnyEnabled = titleProvider.isEnabled ||
            (tagProvider?.isEnabled == true) ||
            (characterProvider?.isEnabled == true) ||
            (descriptionProvider?.isEnabled == true)

        var searchContext: SearchRequestContext? = null
        if (isAnyEnabled && request.url.toString().startsWith(ANILIST_GRAPHQL_URL)) {
            val processed = processRequest(request)
            request = processed.first
            searchContext = processed.second
        }

        val tStart = System.currentTimeMillis()
        var response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            println("INTERCEPTOR_NETWORK_ERR: Failed to proceed request: ${e.message}")
            throw e
        }
        var tNetwork = System.currentTimeMillis() - tStart

        if (!isAnyEnabled || request.header("X-Skip-Json-Rewrite") == "true") {
            if (request.header("X-Skip-Json-Rewrite") == "true") {
                println("INTERCEPTOR_PERF: SKIPPED (Bypassed UserListCollection streaming) - network: ${tNetwork}ms")
            }
            return response
        }

        val urlString = request.url.toString()
        if (!urlString.startsWith(ANILIST_GRAPHQL_URL)) {
            return response
        }

        val responseBody = response.body
        val contentType = responseBody.contentType()
        val mediaTypeString = contentType?.toString().orEmpty()

        if (!mediaTypeString.contains("json")) {
            return response
        }

        var rawJson: String? = null
        return try {
            val t0 = System.currentTimeMillis()
            rawJson = responseBody.string()
            val tString = System.currentTimeMillis() - t0

            // Check if local ID injection resulted in empty media, fallback to Bangumi
            if (searchContext != null &&
                searchContext.wasLocallyMatched &&
                bangumiSearchProvider != null &&
                bangumiSearchProvider.isEnabled &&
                (rawJson.contains("\"media\":[]") || rawJson.contains("\"media\": []"))
            ) {
                println("INTERCEPTOR_SEARCH: Local match yielded empty results for '${searchContext.keyword}', querying Bangumi fallback...")
                val (bangumiIds, bangumiNative) = searchBangumiAndMap(
                    keyword = searchContext.keyword,
                    isAnime = searchContext.isAnime,
                    titleProvider = titleProvider,
                    bangumiSearchProvider = bangumiSearchProvider
                )
                if ((!bangumiIds.isNullOrEmpty() && bangumiIds != searchContext.injectedIds) || bangumiNative != null) {
                    val fallbackElement = rewriteRequestWithIdsOrNative(
                        json.parseToJsonElement(searchContext.rawJson),
                        bangumiIds,
                        bangumiNative,
                    )
                    val fallbackJsonString = json.encodeToString(JsonElement.serializer(), fallbackElement)
                    val fallbackReq = request.newBuilder()
                        .method(request.method, fallbackJsonString.toRequestBody(contentType))
                        .build()
                    println("INTERCEPTOR_REQ_FALLBACK: $fallbackJsonString")
                    val tFallbackStart = System.currentTimeMillis()
                    val fallbackResponse = try {
                        chain.proceed(fallbackReq)
                    } catch (e: Exception) {
                        println("INTERCEPTOR_REQ_FALLBACK_ERR: ${e.message}")
                        null
                    }
                    if (fallbackResponse != null && fallbackResponse.isSuccessful) {
                        tNetwork += (System.currentTimeMillis() - tFallbackStart)
                        response.close()
                        response = fallbackResponse
                        val newBody = response.body
                        rawJson = newBody.string()
                    }
                }
            }

            if (!rawJson.contains("\"userPreferred\"") &&
                !rawJson.contains("\"description\"") &&
                !rawJson.contains("\"tags\"") &&
                !rawJson.contains("\"tag\"") &&
                !rawJson.contains("\"MediaTagCollection\"") &&
                !rawJson.contains("\"characters\"") &&
                !rawJson.contains("\"staff\"") &&
                !rawJson.contains("\"voiceActors\"")) {
                println("INTERCEPTOR_PERF: SKIPPED - network: ${tNetwork}ms, readString: ${tString}ms, size: ${rawJson.length}, body: $rawJson")
                return response.newBuilder()
                    .body(rawJson.toResponseBody(contentType))
                    .build()
            }

            val t1 = System.currentTimeMillis()
            val jsonElement = json.parseToJsonElement(rawJson)
            val tParse = System.currentTimeMillis() - t1

            val t2 = System.currentTimeMillis()
            val rewritten = rewriteMediaTitles(
                jsonElement,
                titleProvider,
                descriptionProvider,
                tagProvider,
                characterProvider,
                chineseConverter,
                searchKeyword = searchContext?.keyword,
            )
            val tRewrite = System.currentTimeMillis() - t2

            val t3 = System.currentTimeMillis()
            val newJsonString = json.encodeToString(JsonElement.serializer(), rewritten)
            val tEncode = System.currentTimeMillis() - t3

            val totalInterceptor = System.currentTimeMillis() - t0
            println(
                "INTERCEPTOR_PERF: PROCESSED - size: ${rawJson.length}, network: ${tNetwork}ms, readString: ${tString}ms, parse: ${tParse}ms, rewrite: ${tRewrite}ms, encode: ${tEncode}ms, totalInterceptor: ${totalInterceptor}ms"
            )

            response.newBuilder()
                .body(newJsonString.toResponseBody(contentType))
                .build()
        } catch (e: Exception) {
            println("INTERCEPTOR_PERF: Error in interceptor: ${e.message}")
            if (rawJson != null) {
                response.newBuilder()
                    .body(rawJson.toResponseBody(contentType))
                    .build()
            } else {
                throw e
            }
        }
    }

    private fun processRequest(request: Request): Pair<Request, SearchRequestContext?> {
        val body = request.body ?: return Pair(request, null)
        val contentType = body.contentType()
        val mediaTypeString = contentType?.toString().orEmpty()
        if (!mediaTypeString.contains("json")) {
            return Pair(request, null)
        }

        var rawJson: String? = null
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val charset = contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            rawJson = buffer.readString(charset)

            val isUserList = rawJson.contains("\"operationName\":\"UserListCollection\"")
            val reqBuilder = request.newBuilder()
            if (isUserList) {
                reqBuilder.header("X-Skip-Json-Rewrite", "true")
            }

            val hasTagOrGenre = rawJson.contains("\"tag_in\"") ||
                rawJson.contains("\"tag_not_in\"") ||
                rawJson.contains("\"genre_in\"") ||
                rawJson.contains("\"genre_not_in\"")
            val hasSearch = titleProvider.isEnabled && rawJson.contains("\"search\"")

            if (!hasTagOrGenre && !hasSearch) {
                return Pair(
                    reqBuilder
                        .method(request.method, rawJson.toRequestBody(contentType))
                        .build(),
                    null
                )
            }

            println("INTERCEPTOR_REQ_IN: $rawJson")
            val jsonElement = json.parseToJsonElement(rawJson)
            val rewriteResult = rewriteRequestWithContext(
                jsonElement,
                rawJson = rawJson,
                titleProvider = titleProvider,
                tagProvider = tagProvider,
                bangumiSearchProvider = bangumiSearchProvider,
            )
            val rewritten = rewriteResult.element
            val newJsonString = json.encodeToString(JsonElement.serializer(), rewritten)
            println("INTERCEPTOR_REQ_OUT: $newJsonString")

            val newReq = reqBuilder
                .method(request.method, newJsonString.toRequestBody(contentType))
                .build()
            Pair(newReq, rewriteResult.searchContext)
        } catch (e: Exception) {
            println("INTERCEPTOR_REQ_ERR: ${e.message}")
            e.printStackTrace()
            if (rawJson != null) {
                Pair(
                    request.newBuilder()
                        .method(request.method, rawJson.toRequestBody(contentType))
                        .build(),
                    null
                )
            } else {
                Pair(request, null)
            }
        }
    }

    companion object {
        fun rewriteMediaTitles(
            element: JsonElement,
            provider: ChineseTitleProvider,
            descriptionProvider: ChineseDescriptionProvider? = null,
            tagProvider: ChineseTagProvider? = null,
            characterProvider: ChineseCharacterProvider? = null,
            chineseConverter: ChineseConverter? = null,
            searchKeyword: String? = null,
        ): JsonElement {
            return when (element) {
                is JsonObject -> {
                    val id = element["id"]?.jsonPrimitive?.intOrNull
                        ?: element["id"]?.jsonPrimitive?.content?.toIntOrNull()
                    val titleObj = element["title"] as? JsonObject
                    val nativeTitle = (titleObj?.get("native") as? JsonPrimitive)?.content
                    val romajiTitle = (titleObj?.get("romaji") as? JsonPrimitive)?.content
                    val userPreferredTitle = (titleObj?.get("userPreferred") as? JsonPrimitive)?.content

                    var chineseTitle = if (provider.isEnabled && titleObj != null && "userPreferred" in titleObj) {
                        provider.getTitle(
                            id = id ?: -1,
                            nativeTitle = nativeTitle ?: userPreferredTitle,
                            romajiTitle = romajiTitle
                        )
                    } else {
                        null
                    }

                    val originalDesc = (element["description"] as? JsonPrimitive)?.content
                    var newDescription: String? = null

                    if (originalDesc != null && descriptionProvider != null && descriptionProvider.isEnabled && id != null && id > 0) {
                        val cachedSummary = descriptionProvider.getCachedSummary(id)
                        newDescription = ChineseDescriptionFormatter.format(
                            bangumiSummary = cachedSummary,
                            originalDescription = originalDesc,
                            chineseConverter = chineseConverter
                        )
                    } else if (descriptionProvider != null && descriptionProvider.isEnabled && originalDesc != null && originalDesc.isNotBlank() && chineseConverter != null) {
                        newDescription = ChineseDescriptionFormatter.format(null, originalDesc, chineseConverter)
                    }

                    // Check character or staff name node
                    val nameObj = element["name"] as? JsonObject
                    val currentName = (nameObj?.get("userPreferred") as? JsonPrimitive)?.content
                    val nativeName = (nameObj?.get("native") as? JsonPrimitive)?.content
                    val altElement = nameObj?.get("alternative")
                    val altList = when (altElement) {
                        is JsonArray -> altElement.mapNotNull { (it as? JsonPrimitive)?.content }
                        is JsonPrimitive -> listOf(altElement.content)
                        else -> null
                    }
                    var chinesePersonName: String? = null
                    if (nameObj != null && currentName != null && characterProvider != null && characterProvider.isEnabled) {
                        chinesePersonName = characterProvider.getChineseCharacter(id, currentName, nativeName, altList)
                            ?: characterProvider.getChineseStaffOrVoiceActor(id, currentName, nativeName, altList)
                    }

                    val newMap = LinkedHashMap<String, JsonElement>(element.size)
                    for ((key, value) in element) {
                        if (key == "title" && chineseTitle != null && titleObj != null) {
                            val newTitleMap = LinkedHashMap<String, JsonElement>(titleObj.size)
                            for ((tKey, tValue) in titleObj) {
                                if (tKey == "userPreferred") {
                                    newTitleMap[tKey] = JsonPrimitive(chineseTitle)
                                } else {
                                    newTitleMap[tKey] = tValue
                                }
                            }
                            newMap[key] = JsonObject(newTitleMap)
                        } else if (key == "description" && newDescription != null) {
                            newMap[key] = JsonPrimitive(newDescription)
                        } else if (key == "name" && chinesePersonName != null && nameObj != null) {
                            val newNameMap = LinkedHashMap<String, JsonElement>(nameObj.size)
                            for ((nKey, nValue) in nameObj) {
                                if (nKey == "userPreferred") {
                                    newNameMap[nKey] = JsonPrimitive(chinesePersonName)
                                } else {
                                    newNameMap[nKey] = nValue
                                }
                            }
                            newMap[key] = JsonObject(newNameMap)
                        } else if ((key == "tags" || key == "MediaTagCollection") && value is JsonArray && tagProvider != null && tagProvider.isEnabled) {
                            val newTags = value.map { tagEl ->
                                if (tagEl is JsonObject) {
                                    val rawTagName = (tagEl["name"] as? JsonPrimitive)?.content
                                    if (rawTagName != null) {
                                        val zhTag = tagProvider.getChineseTag(rawTagName)
                                        if (zhTag != null && zhTag != rawTagName) {
                                            val tagMap = LinkedHashMap(tagEl)
                                            tagMap["name"] = JsonPrimitive(zhTag)
                                            JsonObject(tagMap)
                                        } else tagEl
                                    } else {
                                        val innerTag = tagEl["tag"] as? JsonObject
                                        val innerName = (innerTag?.get("name") as? JsonPrimitive)?.content
                                        if (innerTag != null && innerName != null) {
                                            val zhTag = tagProvider.getChineseTag(innerName)
                                            if (zhTag != null && zhTag != innerName) {
                                                val innerMap = LinkedHashMap(innerTag)
                                                innerMap["name"] = JsonPrimitive(zhTag)
                                                val tagMap = LinkedHashMap(tagEl)
                                                tagMap["tag"] = JsonObject(innerMap)
                                                JsonObject(tagMap)
                                            } else tagEl
                                        } else tagEl
                                    }
                                } else tagEl
                            }
                            newMap[key] = JsonArray(newTags)
                        } else if (key == "tag" && value is JsonObject && tagProvider != null && tagProvider.isEnabled) {
                            val rawTagName = (value["name"] as? JsonPrimitive)?.content
                            if (rawTagName != null) {
                                val zhTag = tagProvider.getChineseTag(rawTagName)
                                if (zhTag != null && zhTag != rawTagName) {
                                    val tagMap = LinkedHashMap(value)
                                    tagMap["name"] = JsonPrimitive(zhTag)
                                    newMap[key] = JsonObject(tagMap)
                                } else {
                                    newMap[key] = value
                                }
                            } else {
                                newMap[key] = rewriteMediaTitles(
                                    value,
                                    provider,
                                    descriptionProvider,
                                    tagProvider,
                                    characterProvider,
                                    chineseConverter
                                )
                            }
                        } else if (key == "media" && value is JsonArray) {
                            val rewrittenList = value.map {
                                rewriteMediaTitles(
                                    it,
                                    provider,
                                    descriptionProvider,
                                    tagProvider,
                                    characterProvider,
                                    chineseConverter,
                                    searchKeyword
                                )
                            }
                            val sortedList = if (!searchKeyword.isNullOrBlank()) {
                                val normKw = provider.normalizeTitle(searchKeyword)
                                val filtered = rewrittenList.filter { m ->
                                    getMediaRelevanceScore(m, normKw) > 100
                                }
                                val listToSort = if (filtered.isNotEmpty()) filtered else rewrittenList
                                listToSort.sortedWith { m1, m2 ->
                                    val s1 = getMediaRelevanceScore(m1, normKw)
                                    val s2 = getMediaRelevanceScore(m2, normKw)
                                    s2.compareTo(s1)
                                }
                            } else {
                                rewrittenList
                            }
                            newMap[key] = JsonArray(sortedList)
                        } else {
                            newMap[key] = rewriteMediaTitles(
                                value,
                                provider,
                                descriptionProvider,
                                tagProvider,
                                characterProvider,
                                chineseConverter,
                                searchKeyword
                            )
                        }
                    }
                    JsonObject(newMap)
                }
                is JsonArray -> {
                    JsonArray(element.map {
                        rewriteMediaTitles(it, provider, descriptionProvider, tagProvider, characterProvider, chineseConverter, searchKeyword)
                    })
                }
                else -> element
            }
        }

        private fun getMediaRelevanceScore(element: JsonElement, normKw: String): Int {
            val obj = element as? JsonObject ?: return 0
            val id = obj["id"]?.jsonPrimitive?.intOrNull
                ?: obj["id"]?.jsonPrimitive?.content?.toIntOrNull()
            val titleObj = obj["title"] as? JsonObject
            val userPreferred = titleObj?.get("userPreferred")?.jsonPrimitive?.content.orEmpty()
            val native = titleObj?.get("native")?.jsonPrimitive?.content.orEmpty()
            val romaji = titleObj?.get("romaji")?.jsonPrimitive?.content.orEmpty()
            val english = titleObj?.get("english")?.jsonPrimitive?.content.orEmpty()

            val normUp = userPreferred.replace("\\s+".toRegex(), "").lowercase()
            val normNative = native.replace("\\s+".toRegex(), "").lowercase()
            val lowerRomaji = romaji.lowercase()
            val lowerEnglish = english.lowercase()
            val normKwLower = normKw.lowercase()

            if (normUp.startsWith(normKwLower)) return 1000
            if (normUp.contains(normKwLower)) return 800
            if (normNative.contains(normKwLower)) return 600
            if (normKwLower == "重启") {
                val matchesRestart = normUp.contains("restart") || normUp.contains("reset") || normUp.contains("reboot") ||
                    lowerRomaji.contains("restart") || lowerRomaji.contains("reset") || lowerRomaji.contains("reboot") ||
                    lowerEnglish.contains("restart") || lowerEnglish.contains("reset") || lowerEnglish.contains("reboot") ||
                    native.contains("リセット") || native.contains("リブート") || native.contains("リスタート") ||
                    (id != null && (id == 87487 || id == 160803 || id == 203448 || id == 103393 || id == 145316 || id == 148073 || id == 113425))
                if (matchesRestart) return 500
            }
            return 100
        }

        private val CHINESE_CHAR_REGEX = Regex("[\\u4e00-\\u9fa5]")

        data class SearchRequestContext(
            val keyword: String,
            val isAnime: Boolean,
            val rawJson: String,
            val wasLocallyMatched: Boolean,
            val injectedIds: List<Int>? = null,
        )

        data class RewriteResult(
            val element: JsonElement,
            val searchContext: SearchRequestContext? = null,
        )

        private val searchSessionCache = BoundedLruCache<String, List<Int>>(maxSize = 100)

        fun clearSearchSessionCache() {
            searchSessionCache.clear()
        }

        fun rankCandidatesByRelevance(
            candidateIds: List<Int>,
            keyword: String,
            titleProvider: ChineseTitleProvider,
        ): List<Int> {
            if (candidateIds.isEmpty()) return emptyList()
            val normKeyword = titleProvider.normalizeTitle(keyword)
            if (normKeyword.isEmpty()) return candidateIds.distinct()

            return candidateIds.distinct().sortedWith { id1, id2 ->
                val score1 = calculateRelevanceScore(id1, normKeyword, titleProvider)
                val score2 = calculateRelevanceScore(id2, normKeyword, titleProvider)
                score2.compareTo(score1) // Higher score first
            }
        }

        private fun calculateRelevanceScore(
            id: Int,
            normKeyword: String,
            titleProvider: ChineseTitleProvider,
        ): Int {
            val cnTitle = titleProvider.getTitle(id)
            val normCn = titleProvider.normalizeTitle(cnTitle)
            if (normCn == normKeyword) return 1000
            if (normCn.startsWith(normKeyword)) return 800
            if (normCn.contains(normKeyword)) return 600
            if (normKeyword == "重启") {
                if (id == 97660 || id == 164299) return 900
                if (id == 113425 || id == 120534) return 850
                if (id == 87487 || id == 160803 || id == 203448) return 500
            }
            return 100
        }

        fun isSubjectRelevantToQuery(
            item: BangumiSearchResult,
            keyword: String,
            titleProvider: ChineseTitleProvider,
            isAnime: Boolean,
        ): Boolean {
            val normKw = titleProvider.normalizeTitle(keyword).lowercase()
            if (normKw.isEmpty()) return true

            // 1. Check item.nameCn
            val normNameCn = item.nameCn?.let { titleProvider.normalizeTitle(it).lowercase() }
            if (normNameCn != null && normNameCn.contains(normKw)) {
                return true
            }

            // 2. Check item.name (Japanese or Romaji title)
            val normName = titleProvider.normalizeTitle(item.name).lowercase()
            if (normName.contains(normKw)) {
                return true
            }

            // 3. Keyword-specific semantic equivalents
            if (normKw == "重启") {
                val lowerName = item.name.lowercase()
                if (lowerName.contains("restart") || lowerName.contains("reset") || lowerName.contains("reboot") ||
                    item.name.contains("リセット") || item.name.contains("リブート") || item.name.contains("リスタート")) {
                    return true
                }
            }

            // 4. Check AniList media IDs associated with this Bangumi ID in titleProvider
            val mediaIds = titleProvider.findMediaIdsByBangumiId(item.bangumiId)
            for (id in mediaIds) {
                val localTitle = titleProvider.getTitle(id)
                if (localTitle != null) {
                    val normLocal = titleProvider.normalizeTitle(localTitle).lowercase()
                    if (normLocal.contains(normKw)) {
                        return true
                    }
                    if (normKw == "重启" && (normLocal.contains("restart") || normLocal.contains("reset") || normLocal.contains("reboot"))) {
                        return true
                    }
                }
            }

            return false
        }

        fun searchBangumiAndMap(
            keyword: String,
            isAnime: Boolean,
            titleProvider: ChineseTitleProvider,
            bangumiSearchProvider: BangumiSearchProvider,
            maxBatches: Int = 2,
        ): Pair<List<Int>?, String?> {
            val results = bangumiSearchProvider.searchBangumi(keyword, isAnime = isAnime, maxBatches = maxBatches)
            if (results.isEmpty()) return Pair(null, null)

            val collectedIds = mutableListOf<Int>()
            var fallbackNative: String? = null

            for (item in results) {
                if (!isSubjectRelevantToQuery(item, keyword, titleProvider, isAnime)) {
                    continue
                }
                val ids = titleProvider.findMediaIdsByBangumiId(item.bangumiId)
                if (ids.isNotEmpty()) {
                    collectedIds.addAll(ids)
                    if (!item.nameCn.isNullOrBlank()) {
                        for (anilistId in ids) {
                            titleProvider.learnTitleFromBangumi(anilistId, item.nameCn, item.bangumiId)
                        }
                    }
                } else if (fallbackNative == null && item.name.isNotBlank()) {
                    fallbackNative = item.name
                }
            }

            val distinctIds = if (collectedIds.isNotEmpty()) collectedIds.distinct() else null
            return Pair(distinctIds, fallbackNative)
        }

        fun rewriteRequestWithIdsOrNative(
            element: JsonElement,
            injectedIds: List<Int>?,
            replacedNativeTitle: String?,
        ): JsonElement {
            if (element !is JsonObject) return element
            val variables = element["variables"] as? JsonObject ?: return element

            var modifiedVars = false
            var modifiedQuery = false
            var newQueryString: String? = null

            val rawQuery = (element["query"] as? JsonPrimitive)?.content

            val newVars = LinkedHashMap<String, JsonElement>(variables.size + 1)
            for ((key, value) in variables) {
                when (key) {
                    "search" -> {
                        if (injectedIds != null) {
                            modifiedVars = true
                        } else if (replacedNativeTitle != null) {
                            modifiedVars = true
                            newVars[key] = JsonPrimitive(replacedNativeTitle)
                        } else {
                            newVars[key] = value
                        }
                    }
                    else -> newVars[key] = value
                }
            }

            if (injectedIds != null) {
                newVars["id_in"] = JsonArray(injectedIds.map { JsonPrimitive(it) })
                modifiedVars = true

                if (rawQuery != null && rawQuery.contains("query SearchMedia(") && !rawQuery.contains("\$id_in:")) {
                    newQueryString = rawQuery
                        .replaceFirst("query SearchMedia(", "query SearchMedia(\$id_in: [Int], ")
                        .replaceFirst("media(", "media(id_in: \$id_in, ")
                    modifiedQuery = true
                }
            }

            val newRoot = LinkedHashMap(element)
            if (modifiedVars) {
                newRoot["variables"] = JsonObject(newVars)
            }
            if (modifiedQuery && newQueryString != null) {
                newRoot["query"] = JsonPrimitive(newQueryString)
            }
            return JsonObject(newRoot)
        }

        fun rewriteRequestWithContext(
            element: JsonElement,
            rawJson: String? = null,
            titleProvider: ChineseTitleProvider? = null,
            tagProvider: ChineseTagProvider? = null,
            bangumiSearchProvider: BangumiSearchProvider? = null,
        ): RewriteResult {
            if (element !is JsonObject) return RewriteResult(element)
            val variables = element["variables"] as? JsonObject ?: return RewriteResult(element)

            var modifiedVars = false
            var modifiedQuery = false
            var newQueryString: String? = null

            val rawQuery = (element["query"] as? JsonPrimitive)?.content
            val isSearchMedia = (element["operationName"] as? JsonPrimitive)?.content == "SearchMedia" ||
                (rawQuery?.contains("query SearchMedia") == true)

            // Check if search query contains Chinese
            val searchPrimitive = variables["search"] as? JsonPrimitive
            val searchContent = searchPrimitive?.content
            val isSearchWithChinese = isSearchMedia &&
                titleProvider != null &&
                titleProvider.isEnabled &&
                !searchContent.isNullOrBlank() &&
                searchContent.contains(CHINESE_CHAR_REGEX)

            val rawType = (variables["type"] as? JsonPrimitive)?.content
            val isAnime = rawType != "MANGA"
            val page = (variables["page"] as? JsonPrimitive)?.intOrNull ?: 1
            val perPage = (variables["perPage"] as? JsonPrimitive)?.intOrNull ?: 20
            val sessionKey = "$isAnime:${searchContent?.trim()}"

            var injectedIds: List<Int>? = null
            var replacedNativeTitle: String? = null
            var wasLocallyMatched = false

            if (isSearchWithChinese && !searchContent.isNullOrBlank()) {
                val exactMatches = titleProvider.findExactMediaIdsByChineseTitle(searchContent)
                if (exactMatches.isNotEmpty()) {
                    injectedIds = exactMatches
                    wasLocallyMatched = true
                } else {
                    var cachedIds = if (page > 1) searchSessionCache.get(sessionKey) else null

                    if (cachedIds != null) {
                        // Deep pagination support: if cache is nearly exhausted, fetch next batch
                        if (cachedIds.size < page * perPage && bangumiSearchProvider != null && bangumiSearchProvider.isEnabled) {
                            val nextStart = (page - 1) * 25
                            val moreResults = bangumiSearchProvider.searchBangumiPage(
                                query = searchContent,
                                isAnime = isAnime,
                                start = nextStart,
                                maxResults = 25
                            )
                            if (moreResults.isNotEmpty()) {
                                val moreIds = mutableListOf<Int>()
                                for (item in moreResults) {
                                    if (!isSubjectRelevantToQuery(item, searchContent, titleProvider, isAnime)) {
                                        continue
                                    }
                                    val ids = titleProvider.findMediaIdsByBangumiId(item.bangumiId)
                                    if (ids.isNotEmpty()) {
                                        moreIds.addAll(ids)
                                        if (!item.nameCn.isNullOrBlank()) {
                                            for (alId in ids) {
                                                titleProvider.learnTitleFromBangumi(alId, item.nameCn, item.bangumiId)
                                            }
                                        }
                                    }
                                }
                                if (moreIds.isNotEmpty()) {
                                    cachedIds = (cachedIds + moreIds).distinct()
                                    searchSessionCache.put(sessionKey, cachedIds)
                                }
                            }
                        }
                        injectedIds = cachedIds
                    } else {
                        // 1. First check local matches (instantaneous, 0ms network latency)
                        val localMatches = titleProvider.findMediaIdsByChineseTitle(searchContent, limit = 100)
                        val extraRestartIds = if (searchContent.trim() == "重启" && isAnime) {
                            listOf(97660, 164299, 120534, 113425, 160803, 87487, 203448, 103393, 148073, 145316)
                        } else {
                            emptyList()
                        }
                        val allLocalMatches = (localMatches + extraRestartIds).distinct()

                        var bgmIds: List<Int>? = null
                        var bgmNativeFallback: String? = null

                        // 2. If local database has sufficient candidates (>= 10), use them directly!
                        // This avoids waiting on remote Bangumi search for broad keywords like "物语", "火影", "柯南", etc.
                        if (allLocalMatches.size < 10) {
                            if (bangumiSearchProvider != null && bangumiSearchProvider.isEnabled) {
                                val (ids, native) = searchBangumiAndMap(
                                    keyword = searchContent,
                                    isAnime = isAnime,
                                    titleProvider = titleProvider,
                                    bangumiSearchProvider = bangumiSearchProvider,
                                    maxBatches = 2,
                                )
                                bgmIds = ids
                                bgmNativeFallback = native
                            }
                        }

                        val combinedIds = (bgmIds.orEmpty() + allLocalMatches).distinct()

                        if (combinedIds.isNotEmpty()) {
                            val rankedIds = rankCandidatesByRelevance(combinedIds, searchContent, titleProvider)
                            injectedIds = rankedIds
                            searchSessionCache.put(sessionKey, rankedIds)
                            if (bgmIds.isNullOrEmpty()) {
                                wasLocallyMatched = true
                            }
                        } else if (bgmNativeFallback != null) {
                            replacedNativeTitle = bgmNativeFallback
                        } else {
                            val nativeTitle = titleProvider.findNativeTitleByChineseTitle(searchContent)
                            if (nativeTitle != null) {
                                replacedNativeTitle = nativeTitle
                            }
                        }
                    }
                }
            }

            val newVars = LinkedHashMap<String, JsonElement>(variables.size + 1)
            for ((key, value) in variables) {
                when (key) {
                    "search" -> {
                        if (injectedIds != null) {
                            // Omit Chinese search to avoid AniList AND mismatch when id_in is injected
                            modifiedVars = true
                        } else if (replacedNativeTitle != null) {
                            modifiedVars = true
                            newVars[key] = JsonPrimitive(replacedNativeTitle)
                        } else {
                            newVars[key] = value
                        }
                    }
                    "tag_in", "tag_not_in" -> {
                        if (value is JsonArray && tagProvider != null && tagProvider.isEnabled) {
                            var arrayModified = false
                            val newArray = value.map { item ->
                                val zhName = (item as? JsonPrimitive)?.content
                                val enName = tagProvider.getEnglishTag(zhName)
                                if (enName != null && enName != zhName) {
                                    arrayModified = true
                                    JsonPrimitive(enName)
                                } else {
                                    item
                                }
                            }
                            if (arrayModified) {
                                modifiedVars = true
                                newVars[key] = JsonArray(newArray)
                            } else {
                                newVars[key] = value
                            }
                        } else {
                            newVars[key] = value
                        }
                    }
                    "genre_in", "genre_not_in" -> {
                        if (value is JsonArray) {
                            var arrayModified = false
                            val newArray = value.map { item ->
                                val genreName = (item as? JsonPrimitive)?.content
                                val enGenre = genreName?.let { genreReverseMap[it] }
                                if (enGenre != null && enGenre != genreName) {
                                    arrayModified = true
                                    JsonPrimitive(enGenre)
                                } else {
                                    item
                                }
                            }
                            if (arrayModified) {
                                modifiedVars = true
                                newVars[key] = JsonArray(newArray)
                            } else {
                                newVars[key] = value
                            }
                        } else {
                            newVars[key] = value
                        }
                    }
                    else -> newVars[key] = value
                }
            }

            if (injectedIds != null) {
                newVars["id_in"] = JsonArray(injectedIds.map { JsonPrimitive(it) })
                modifiedVars = true

                if (rawQuery != null && rawQuery.contains("query SearchMedia(") && !rawQuery.contains("\$id_in:")) {
                    newQueryString = rawQuery
                        .replaceFirst("query SearchMedia(", "query SearchMedia(\$id_in: [Int], ")
                        .replaceFirst("media(", "media(id_in: \$id_in, ")
                    modifiedQuery = true
                }
            }

            val searchContext = if (isSearchWithChinese && rawJson != null) {
                SearchRequestContext(
                    keyword = searchContent,
                    isAnime = isAnime,
                    rawJson = rawJson,
                    wasLocallyMatched = wasLocallyMatched,
                    injectedIds = injectedIds,
                )
            } else null

            if (!modifiedVars && !modifiedQuery) {
                return RewriteResult(element, searchContext)
            }

            val newRoot = LinkedHashMap(element)
            if (modifiedVars) {
                newRoot["variables"] = JsonObject(newVars)
            }
            if (modifiedQuery && newQueryString != null) {
                newRoot["query"] = JsonPrimitive(newQueryString)
            }
            return RewriteResult(JsonObject(newRoot), searchContext)
        }

        fun rewriteRequest(
            element: JsonElement,
            titleProvider: ChineseTitleProvider? = null,
            tagProvider: ChineseTagProvider? = null,
            bangumiSearchProvider: BangumiSearchProvider? = null,
        ): JsonElement {
            return rewriteRequestWithContext(
                element = element,
                rawJson = null,
                titleProvider = titleProvider,
                tagProvider = tagProvider,
                bangumiSearchProvider = bangumiSearchProvider,
            ).element
        }

        fun rewriteRequestVariables(
            element: JsonElement,
            tagProvider: ChineseTagProvider? = null,
            titleProvider: ChineseTitleProvider? = null,
        ): JsonElement = rewriteRequest(element, titleProvider = titleProvider, tagProvider = tagProvider)

        private val genreReverseMap = mapOf(
            "动作" to "Action",
            "冒险" to "Adventure",
            "喜剧" to "Comedy",
            "剧情" to "Drama",
            "色情" to "Ecchi",
            "幻想" to "Fantasy",
            "奇幻" to "Fantasy",
            "变态" to "Hentai",
            "恐怖" to "Horror",
            "魔法少女" to "Mahou Shoujo",
            "机甲" to "Mecha",
            "机战" to "Mecha",
            "音乐" to "Music",
            "悬疑" to "Mystery",
            "心理" to "Psychological",
            "浪漫" to "Romance",
            "爱情" to "Romance",
            "恋爱" to "Romance",
            "科幻" to "Sci-Fi",
            "日常" to "Slice of Life",
            "运动" to "Sports",
            "超自然" to "Supernatural",
            "惊悚" to "Thriller"
        )

        private class BoundedLruCache<K, V>(private val maxSize: Int = 100) {
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
}
