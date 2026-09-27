package com.axiel7.anihyou.core.network.localization

import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// NOTE(2026-09-28): This provider deliberately holds no hand-maintained title overrides.
// Titles come solely from titles_zh_cn.json, which the userscript pipeline regenerates.
// A previous TITLE_OVERRIDES map hardcoded 7 AniList IDs here and silently overrode the
// pipeline for every release; it was removed so the data source is the single truth.
// Search behaviour for keywords like "重启" is unaffected: ChineseTitleInterceptor
// injects those IDs via extraRestartIds, independent of any title dictionary.
class ChineseTitleProvider(
    private val bundleManager: LocalizationBundleManager,
    private val chineseConverter: ChineseConverter,
) {

    private val titlesById = ConcurrentHashMap<Int, String>()
    private val titlesByName = ConcurrentHashMap<String, String>()
    private val bangumiIdsById = ConcurrentHashMap<Int, Int>()
    private val anilistIdsByBangumiId = ConcurrentHashMap<Int, MutableList<Int>>()

    // Reverse inverted indices for Chinese search
    private val chineseToIds = ConcurrentHashMap<String, MutableList<Int>>()
    private val chineseToNative = ConcurrentHashMap<String, String>()

    @Volatile
    private var normalizedIdEntries: List<TitleIdEntry> = emptyList()

    @Volatile
    var isEnabled: Boolean = true

    init {
        loadDefaultTitles()
        bundleManager.registerReloadListener { reload() }
    }

    fun reload() {
        titlesById.clear()
        titlesByName.clear()
        bangumiIdsById.clear()
        anilistIdsByBangumiId.clear()
        chineseToIds.clear()
        chineseToNative.clear()
        normalizedIdEntries = emptyList()
        loadDefaultTitles()
    }

    fun normalizeTitle(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val simplified = chineseConverter.toSimplified(text) ?: text
        val sb = StringBuilder(simplified.length)
        for (i in 0 until simplified.length) {
            val codePoint = simplified.codePointAt(i)
            if (Character.isLetterOrDigit(codePoint)) {
                sb.appendCodePoint(Character.toLowerCase(codePoint))
            }
        }
        return sb.toString()
    }

    private fun loadDefaultTitles() {
        try {
            val stream: InputStream? = bundleManager.openResource("titles_zh_cn.json")
                ?: javaClass.classLoader?.getResourceAsStream("titles_zh_cn.json")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("titles_zh_cn.json")

            if (stream != null) {
                stream.bufferedReader().use { reader ->
                    val text = reader.readText()
                    if (text.isNotBlank()) {
                        val map: Map<String, String> = Json.decodeFromString(text)
                        val idListMap = HashMap<String, MutableList<Int>>()
                        for ((k, v) in map) {
                            val id = k.toIntOrNull()
                            val pipeIndex = v.indexOf('|')
                            val cleanTitle = if (pipeIndex >= 0) v.substring(0, pipeIndex) else v
                            val bgmId = if (pipeIndex >= 0) v.substring(pipeIndex + 1).toIntOrNull() else null
                            val normTitle = normalizeTitle(cleanTitle)

                            if (id != null) {
                                titlesById[id] = cleanTitle
                                if (bgmId != null) {
                                    bangumiIdsById[id] = bgmId
                                    anilistIdsByBangumiId.computeIfAbsent(bgmId) { CopyOnWriteArrayList() }.add(id)
                                }
                                if (normTitle.isNotEmpty()) {
                                    idListMap.getOrPut(normTitle) { mutableListOf() }.add(id)
                                }
                            } else {
                                titlesByName[k] = cleanTitle
                                if (normTitle.isNotEmpty()) {
                                    chineseToNative.putIfAbsent(normTitle, k)
                                }
                            }
                        }
                        for ((norm, ids) in idListMap) {
                            chineseToIds[norm] = CopyOnWriteArrayList(ids)
                        }
                        normalizedIdEntries = idListMap.map { (title, ids) ->
                            TitleIdEntry(title, ids)
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

    fun findMediaIdsByBangumiId(bgmId: Int): List<Int> {
        return anilistIdsByBangumiId[bgmId]?.toList() ?: emptyList()
    }

    fun updateTitle(id: Int, title: String, bangumiId: Int? = null) {
        titlesById[id] = title
        if (bangumiId != null) {
            bangumiIdsById[id] = bangumiId
            anilistIdsByBangumiId.computeIfAbsent(bangumiId) { CopyOnWriteArrayList() }.add(id)
        }
        val normTitle = normalizeTitle(title)
        if (normTitle.isNotEmpty()) {
            chineseToIds.computeIfAbsent(normTitle) { CopyOnWriteArrayList() }.add(id)
            rebuildNormalizedIdEntries()
        }
    }

    fun learnTitleFromBangumi(anilistId: Int, nameCn: String, bangumiId: Int? = null) {
        if (anilistId <= 0 || nameCn.isBlank()) return
        if (bangumiId != null && bangumiId > 0) {
            bangumiIdsById[anilistId] = bangumiId
            anilistIdsByBangumiId.computeIfAbsent(bangumiId) { CopyOnWriteArrayList() }.add(anilistId)
        }
        val existing = titlesById[anilistId]
        if (existing != null && existing.contains("重启") && !nameCn.contains("重启")) {
            return
        }
        val simplified = chineseConverter.toSimplified(nameCn) ?: nameCn
        titlesById[anilistId] = simplified
        val norm = normalizeTitle(simplified)
        if (norm.isNotEmpty()) {
            chineseToIds.computeIfAbsent(norm) { CopyOnWriteArrayList() }.add(anilistId)
            rebuildNormalizedIdEntries()
        }
    }

    fun loadAdditionalTitles(newTitles: Map<Int, String>) {
        titlesById.putAll(newTitles)
        for ((id, title) in newTitles) {
            val pipeIndex = title.indexOf('|')
            val cleanTitle = if (pipeIndex >= 0) title.substring(0, pipeIndex) else title
            val normTitle = normalizeTitle(cleanTitle)
            if (normTitle.isNotEmpty()) {
                chineseToIds.computeIfAbsent(normTitle) { CopyOnWriteArrayList() }.add(id)
            }
        }
        rebuildNormalizedIdEntries()
    }

    fun loadAdditionalTitlesByName(newTitles: Map<String, String>) {
        titlesByName.putAll(newTitles)
        for ((k, v) in newTitles) {
            val pipeIndex = v.indexOf('|')
            val cleanTitle = if (pipeIndex >= 0) v.substring(0, pipeIndex) else v
            val normTitle = normalizeTitle(cleanTitle)
            if (normTitle.isNotEmpty()) {
                chineseToNative.putIfAbsent(normTitle, k)
            }
        }
    }

    private fun rebuildNormalizedIdEntries() {
        normalizedIdEntries = chineseToIds.map { (title, ids) ->
            TitleIdEntry(title, ids)
        }
    }

    fun findExactMediaIdsByChineseTitle(query: String): List<Int> {
        if (!isEnabled || query.isBlank()) return emptyList()
        val normQuery = normalizeTitle(query)
        if (normQuery.isEmpty()) return emptyList()
        return chineseToIds[normQuery]?.toList() ?: emptyList()
    }

    fun findMediaIdsByChineseTitle(query: String, limit: Int = 100): List<Int> {
        if (!isEnabled || query.isBlank()) return emptyList()
        val normQuery = normalizeTitle(query)
        if (normQuery.isEmpty()) return emptyList()

        val exactMatches = mutableListOf<Int>()
        val prefixMatches = mutableListOf<Int>()
        val containsMatches = mutableListOf<Int>()

        // 1. Exact match
        chineseToIds[normQuery]?.let {
            exactMatches.addAll(it)
        }

        // 2. Scan normalized entries for prefix and substring matches
        val entries = normalizedIdEntries
        for (entry in entries) {
            val title = entry.normalizedTitle
            if (title == normQuery) {
                continue
            } else if (title.startsWith(normQuery)) {
                prefixMatches.addAll(entry.ids)
            } else if (title.contains(normQuery)) {
                containsMatches.addAll(entry.ids)
            }
        }

        val result = LinkedHashSet<Int>(exactMatches.size + prefixMatches.size + containsMatches.size)
        result.addAll(exactMatches)
        result.addAll(prefixMatches)
        result.addAll(containsMatches)

        return if (limit > 0 && result.size > limit) {
            result.take(limit)
        } else {
            result.toList()
        }
    }

    fun findNativeTitleByChineseTitle(query: String): String? {
        if (!isEnabled || query.isBlank()) return null
        val normQuery = normalizeTitle(query)
        if (normQuery.isEmpty()) return null

        // 1. Exact match
        chineseToNative[normQuery]?.let { return it }

        // 2. Prefix match
        for ((title, native) in chineseToNative) {
            if (title.startsWith(normQuery)) {
                return native
            }
        }

        // 3. Substring match
        for ((title, native) in chineseToNative) {
            if (title.contains(normQuery)) {
                return native
            }
        }

        return null
    }

    val size: Int
        get() = titlesById.size + titlesByName.size

    val idMapSize: Int
        get() = titlesById.size

    val nameMapSize: Int
        get() = titlesByName.size

    val chineseToIdsSize: Int
        get() = chineseToIds.size

    val chineseToNativeSize: Int
        get() = chineseToNative.size

    private data class TitleIdEntry(
        val normalizedTitle: String,
        val ids: List<Int>
    )
}

