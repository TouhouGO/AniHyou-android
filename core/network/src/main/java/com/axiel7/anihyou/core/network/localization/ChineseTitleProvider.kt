package com.axiel7.anihyou.core.network.localization

import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import org.koin.core.annotation.Single

@Single
class ChineseTitleProvider(
    private val bundleManager: LocalizationBundleManager? = null
) {

    private val titlesById = ConcurrentHashMap<Int, String>()
    private val titlesByName = ConcurrentHashMap<String, String>()
    private val bangumiIdsById = ConcurrentHashMap<Int, Int>()

    @Volatile
    var isEnabled: Boolean = true

    init {
        loadDefaultTitles()
        bundleManager?.registerReloadListener { reload() }
    }

    fun reload() {
        titlesById.clear()
        titlesByName.clear()
        bangumiIdsById.clear()
        loadDefaultTitles()
    }

    private fun loadDefaultTitles() {
        try {
            val stream: InputStream? = bundleManager?.openResource("titles_zh_cn.json")
                ?: javaClass.classLoader?.getResourceAsStream("titles_zh_cn.json")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("titles_zh_cn.json")

            if (stream != null) {
                stream.bufferedReader().use { reader ->
                    val text = reader.readText()
                    if (text.isNotBlank()) {
                        val map: Map<String, String> = Json.decodeFromString(text)
                        for ((k, v) in map) {
                            val id = k.toIntOrNull()
                            val pipeIndex = v.indexOf('|')
                            val cleanTitle = if (pipeIndex >= 0) v.substring(0, pipeIndex) else v
                            val bgmId = if (pipeIndex >= 0) v.substring(pipeIndex + 1).toIntOrNull() else null

                            if (id != null) {
                                titlesById[id] = cleanTitle
                                if (bgmId != null) {
                                    bangumiIdsById[id] = bgmId
                                }
                            } else {
                                titlesByName[k] = cleanTitle
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getTitle(id: Int, nativeTitle: String? = null, romajiTitle: String? = null): String? {
        if (id > 0) {
            titlesById[id]?.let { return it }
        }
        if (!nativeTitle.isNullOrBlank()) {
            titlesByName[nativeTitle.trim()]?.let { return it }
        }
        if (!romajiTitle.isNullOrBlank()) {
            titlesByName[romajiTitle.trim()]?.let { return it }
        }
        return null
    }

    fun getTitle(id: Int): String? = getTitle(id, null, null)

    fun getBangumiId(id: Int): Int? = bangumiIdsById[id]

    fun updateTitle(id: Int, title: String, bangumiId: Int? = null) {
        titlesById[id] = title
        if (bangumiId != null) {
            bangumiIdsById[id] = bangumiId
        }
    }

    fun loadAdditionalTitles(newTitles: Map<Int, String>) {
        titlesById.putAll(newTitles)
    }

    fun loadAdditionalTitlesByName(newTitles: Map<String, String>) {
        titlesByName.putAll(newTitles)
    }

    val size: Int
        get() = titlesById.size + titlesByName.size

    val idMapSize: Int
        get() = titlesById.size

    val nameMapSize: Int
        get() = titlesByName.size
}
