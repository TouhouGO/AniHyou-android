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

        if (isAnyEnabled && request.url.toString().startsWith(ANILIST_GRAPHQL_URL)) {
            request = processRequest(request)
        }

        val tStart = System.currentTimeMillis()
        val response = chain.proceed(request)
        val tNetwork = System.currentTimeMillis() - tStart

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

            if (!rawJson.contains("\"userPreferred\"") &&
                !rawJson.contains("\"description\"") &&
                !rawJson.contains("\"tags\"") &&
                !rawJson.contains("\"tag\"") &&
                !rawJson.contains("\"MediaTagCollection\"") &&
                !rawJson.contains("\"characters\"") &&
                !rawJson.contains("\"staff\"") &&
                !rawJson.contains("\"voiceActors\"")) {
                println("INTERCEPTOR_PERF: SKIPPED - network: ${tNetwork}ms, readString: ${tString}ms, size: ${rawJson.length}")
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
                chineseConverter
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

    private fun processRequest(request: Request): Request {
        val body = request.body ?: return request
        val contentType = body.contentType()
        val mediaTypeString = contentType?.toString().orEmpty()
        if (!mediaTypeString.contains("json")) {
            return request
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

            if (!rawJson.contains("\"tag_in\"") &&
                !rawJson.contains("\"tag_not_in\"") &&
                !rawJson.contains("\"genre_in\"") &&
                !rawJson.contains("\"genre_not_in\"")) {
                return reqBuilder
                    .method(request.method, rawJson.toRequestBody(contentType))
                    .build()
            }

            val jsonElement = json.parseToJsonElement(rawJson)
            val rewritten = rewriteRequestVariables(jsonElement, tagProvider)
            val newJsonString = json.encodeToString(JsonElement.serializer(), rewritten)

            reqBuilder
                .method(request.method, newJsonString.toRequestBody(contentType))
                .build()
        } catch (_: Exception) {
            if (rawJson != null) {
                request.newBuilder()
                    .method(request.method, rawJson.toRequestBody(contentType))
                    .build()
            } else {
                request
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
                    }
                    JsonObject(newMap)
                }
                is JsonArray -> {
                    JsonArray(element.map {
                        rewriteMediaTitles(it, provider, descriptionProvider, tagProvider, characterProvider, chineseConverter)
                    })
                }
                else -> element
            }
        }

        fun rewriteRequestVariables(
            element: JsonElement,
            tagProvider: ChineseTagProvider?
        ): JsonElement {
            if (element !is JsonObject) return element
            val variables = element["variables"] as? JsonObject ?: return element

            var modified = false
            val newVars = LinkedHashMap<String, JsonElement>(variables.size)
            for ((key, value) in variables) {
                when (key) {
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
                                modified = true
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
                                modified = true
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

            return if (modified) {
                val newRoot = LinkedHashMap(element)
                newRoot["variables"] = JsonObject(newVars)
                JsonObject(newRoot)
            } else {
                element
            }
        }

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
    }
}
