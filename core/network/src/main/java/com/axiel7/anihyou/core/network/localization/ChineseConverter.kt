package com.axiel7.anihyou.core.network.localization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChineseConverter(
    private val bundleManager: LocalizationBundleManager
) {
    @Volatile
    private var cachedMap: Map<Char, Char>? = null

    init {
        bundleManager.registerReloadListener {
            reload()
        }
    }

    fun reload() {
        synchronized(this) {
            cachedMap = null
        }
    }

    private fun loadMap(): Map<Char, Char> {
        return try {
            val jsonContent = bundleManager.readResourceText("t2s_char_map.json") ?: "{}"
            val root = Json.parseToJsonElement(jsonContent).jsonObject
            val map = HashMap<Char, Char>(root.size)
            for ((key, value) in root) {
                val target = value.jsonPrimitive.content
                // Keys and values are validated upstream as exactly one code point
                // (see LocalizationBundleManager.validateExtractedDirectory). Reading them as
                // a single Char keeps this loader consistent with that contract for every
                // BMP character, which is all the map contains today. Non-BMP keys would be
                // surrogate pairs and are intentionally dropped rather than half-stored.
                if (key.isNotEmpty() && target.isNotEmpty()) {
                    map[key[0]] = target[0]
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private val t2sMap: Map<Char, Char>
        get() = cachedMap ?: synchronized(this) {
            cachedMap ?: loadMap().also { cachedMap = it }
        }

    fun toSimplified(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        val map = t2sMap
        if (map.isEmpty()) return text

        val sb = StringBuilder(text.length)
        for (i in 0 until text.length) {
            val ch = text[i]
            sb.append(map[ch] ?: ch)
        }
        return sb.toString()
    }
}
