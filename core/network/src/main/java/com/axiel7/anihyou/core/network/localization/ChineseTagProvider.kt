package com.axiel7.anihyou.core.network.localization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Single

@Single
class ChineseTagProvider(
    private val bundleManager: LocalizationBundleManager
) {
    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    private var cachedMaps: Pair<Map<String, String>, Map<String, String>>? = null

    init {
        bundleManager.registerReloadListener { reload() }
    }

    fun reload() {
        cachedMaps = null
    }

    private val maps: Pair<Map<String, String>, Map<String, String>>
        get() {
            cachedMaps?.let { return it }
            val loaded = try {
                val jsonContent = bundleManager.readResourceText("tags_zh_cn.json") ?: "{}"
                val root = Json.parseToJsonElement(jsonContent).jsonObject
                val forward = HashMap<String, String>(root.size * 2)
                val reverse = HashMap<String, String>(root.size * 2)
                for ((key, value) in root) {
                    val zh = value.jsonPrimitive.content.trim()
                    val en = key.trim()
                    forward[en] = zh
                    forward[en.lowercase()] = zh

                    // Reverse map for request parameter translation back to English
                    reverse[zh] = en
                    reverse[zh.lowercase()] = en
                }
                forward to reverse
            } catch (_: Exception) {
                emptyMap<String, String>() to emptyMap<String, String>()
            }
            cachedMaps = loaded
            return loaded
        }

    val tagMap: Map<String, String> get() = maps.first
    val reverseTagMap: Map<String, String> get() = maps.second

    fun getChineseTag(englishTag: String?): String? {
        if (englishTag.isNullOrBlank()) return englishTag
        val trimmed = englishTag.trim()
        return tagMap[trimmed] ?: tagMap[trimmed.lowercase()] ?: englishTag
    }

    fun getEnglishTag(chineseTag: String?): String? {
        if (chineseTag.isNullOrBlank()) return chineseTag
        val trimmed = chineseTag.trim()
        return reverseTagMap[trimmed]
            ?: reverseTagMap[trimmed.lowercase()]
            ?: if (tagMap.containsKey(trimmed) || tagMap.containsKey(trimmed.lowercase())) trimmed
            else chineseTag
    }
}
