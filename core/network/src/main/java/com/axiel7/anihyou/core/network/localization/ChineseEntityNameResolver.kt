package com.axiel7.anihyou.core.network.localization

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.annotation.Single

@Single
class ChineseEntityNameResolver(
    private val bundleManager: LocalizationBundleManager,
    private val entityNameCache: EntityNameCache,
    private val wikidataSource: WikidataEntityNameSource,
    private val chineseConverter: ChineseConverter,
    private val customHttpClient: OkHttpClient? = null
) {
    private val httpClient by lazy {
        customHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedStaffCharMap: Map<String, String>? = null

    private val bangumiCharacterCandidates = ConcurrentHashMap<Int, List<BangumiEntityCandidate>>()
    private val bangumiPersonCandidates = ConcurrentHashMap<Int, List<BangumiEntityCandidate>>()
    private val bangumiSubjectCharacterRows = ConcurrentHashMap<Int, List<JsonObject>>()
    private val bangumiSubjectPersonRows = ConcurrentHashMap<Int, List<JsonObject>>()

    init {
        bundleManager.registerReloadListener {
            clearRuntime()
        }
    }

    fun clearRuntime() {
        synchronized(this) {
            cachedStaffCharMap = null
            bangumiCharacterCandidates.clear()
            bangumiPersonCandidates.clear()
            bangumiSubjectCharacterRows.clear()
            bangumiSubjectPersonRows.clear()
        }
    }

    private fun loadStaffCharMap(): Map<String, String> {
        return try {
            val jsonContent = bundleManager.readResourceText("staff_characters_zh_cn.json") ?: "{}"
            val root = json.parseToJsonElement(jsonContent).jsonObject
            val map = HashMap<String, String>(root.size)
            for ((key, value) in root) {
                map[key] = value.jsonPrimitive.content
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    val staffCharMap: Map<String, String>
        get() = cachedStaffCharMap ?: synchronized(this) {
            cachedStaffCharMap ?: loadStaffCharMap().also { cachedStaffCharMap = it }
        }

    fun normalize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name.lowercase().filter { it.isLetterOrDigit() }
    }

    /**
     * Strict CJK predicate:
     * Must contain at least one CJK Unified Ideograph, AND must NOT contain Japanese Kana (Hiragana/Katakana).
     * This prevents mixed text like "藍華・S・グランチェスタ" from being treated as full Chinese.
     */
    fun isStrictCjkOnly(text: String): Boolean {
        var hasHan = false
        for (ch in text) {
            val ub = Character.UnicodeBlock.of(ch)
            if (ub == Character.UnicodeBlock.HIRAGANA || ub == Character.UnicodeBlock.KATAKANA) {
                return false // Contains Japanese Kana!
            }
            if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                hasHan = true
            }
        }
        return hasHan
    }

    /**
     * Core resolution pipeline:
     * 1. Offline bundle map (exact ID & normalized name)
     * 2. Persistent entity cache (positive & negative hits)
     * 3. Wikidata batch query (exact AniList ID)
     * 4. Bangumi co-work character/person detail resolution
     * 5. Strict CJK fallback (no Kana allowed)
     */
    suspend fun resolve(
        subjectId: Int?,
        kind: BangumiEntityKind,
        refs: List<BangumiEntityRef>
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        if (refs.isEmpty()) return@withContext emptyMap()

        val results = HashMap<Int, String>()
        val pendingRefs = mutableListOf<BangumiEntityRef>()

        val prefix = if (kind == BangumiEntityKind.CHARACTER) "char_" else "person_"

        // 1. Bundle & Cache resolution
        for (ref in refs) {
            // Bundle ID check
            val bundleIdMatch = staffCharMap["$prefix${ref.id}"]
            if (!bundleIdMatch.isNullOrBlank()) {
                results[ref.id] = bundleIdMatch
                continue
            }

            // Cache check
            val cached = entityNameCache.get(kind, ref.id)
            if (cached != null) {
                if (cached.isNotEmpty()) {
                    results[ref.id] = cached
                }
                // If cached is empty string "", it is a known miss within 7 days, so skip Wikidata
                continue
            }

            // Bundle Name check
            val normNative = normalize(ref.nativeName)
            if (normNative.isNotEmpty()) {
                val bundleNameMatch = staffCharMap["name_$normNative"]
                if (!bundleNameMatch.isNullOrBlank()) {
                    results[ref.id] = bundleNameMatch
                    entityNameCache.putPositive(kind, ref.id, bundleNameMatch)
                    continue
                }
            }

            pendingRefs.add(ref)
        }

        if (pendingRefs.isEmpty()) return@withContext results

        // 2. Wikidata Batch Query
        val wikidataIds = pendingRefs.map { it.id }
        val wikidataResult = wikidataSource.load(kind, wikidataIds)
        for ((id, name) in wikidataResult.names) {
            results[id] = name
            entityNameCache.putPositive(kind, id, name)
        }

        val remainingAfterWikidata = pendingRefs.filterNot { it.id in results }

        // 3. Bangumi resolution (if subjectId provided)
        if (subjectId != null && subjectId > 0 && remainingAfterWikidata.isNotEmpty()) {
            val bangumiMatches = resolveBangumiNames(subjectId, kind, remainingAfterWikidata)
            for ((id, name) in bangumiMatches) {
                results[id] = name
                entityNameCache.putPositive(kind, id, name)
            }
        }

        val remainingAfterBangumi = remainingAfterWikidata.filterNot { it.id in results }

        // 4. Strict CJK fallback & Negative cache recording
        for (ref in remainingAfterBangumi) {
            var cjkResolved: String? = null

            // Try native name
            if (!ref.nativeName.isNullOrBlank() && isStrictCjkOnly(ref.nativeName)) {
                val simplified = chineseConverter.toSimplified(ref.nativeName)
                if (!simplified.isNullOrBlank() && isStrictCjkOnly(simplified) && simplified != ref.nativeName) {
                    cjkResolved = simplified
                }
            }

            // Try aliases
            if (cjkResolved == null) {
                for (alt in ref.aliases) {
                    if (isStrictCjkOnly(alt)) {
                        val simplified = chineseConverter.toSimplified(alt)
                        if (!simplified.isNullOrBlank() && isStrictCjkOnly(simplified)) {
                            cjkResolved = simplified
                            break
                        }
                    }
                }
            }

            if (cjkResolved != null) {
                results[ref.id] = cjkResolved
                entityNameCache.putPositive(kind, ref.id, cjkResolved)
            } else if (!wikidataResult.hasError) {
                // If neither Wikidata nor Bangumi nor strict CJK found a name, record miss cache
                entityNameCache.putMiss(kind, ref.id)
            }
        }

        results
    }

    private suspend fun resolveBangumiNames(
        bangumiSubjectId: Int,
        kind: BangumiEntityKind,
        entities: List<BangumiEntityRef>
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        if (bangumiSubjectId <= 0 || entities.isEmpty()) return@withContext emptyMap()

        val result = HashMap<Int, String>()
        val candidates = loadCandidates(bangumiSubjectId, kind)
        val candidateIndex = candidates
            .distinctBy { it.id }
            .groupBy { normalize(it.nativeName) }

        val directMatches = entities.mapNotNull { entity ->
            val matched = (listOfNotNull(entity.nativeName) + entity.aliases)
                .map(::normalize)
                .filter(String::isNotEmpty)
                .distinct()
                .flatMap { candidateIndex[it].orEmpty() }
                .distinctBy { it.id }
            if (matched.size == 1) entity to matched.single() else null
        }
        val directlyMatchedIds = directMatches.mapTo(HashSet()) { it.first.id }
        val actorMatches = if (kind == BangumiEntityKind.CHARACTER) {
            entities.filterNot { it.id in directlyMatchedIds }.mapNotNull { entity ->
                val actorKeys = entity.actorAliases
                    .map(::normalize)
                    .filter(String::isNotEmpty)
                    .toSet()
                if (actorKeys.isEmpty()) return@mapNotNull null
                val matched = candidates.filter { candidate ->
                    candidate.actorNames.any { normalize(it) in actorKeys }
                }.distinctBy { it.id }
                if (matched.size == 1) entity to matched.single() else null
            }
        } else emptyList()
        val matches = directMatches + actorMatches

        val semaphore = Semaphore(2)
        coroutineScope {
            matches.map { (entity, candidate) ->
                async {
                    semaphore.withPermit {
                        fetchSimplifiedName(kind, candidate.id)?.let { entity.id to it }
                    }
                }
            }.awaitAll().filterNotNull()
        }.forEach { (aniListId, name) ->
            result[aniListId] = name
        }
        result
    }

    private fun loadCandidates(subjectId: Int, kind: BangumiEntityKind): List<BangumiEntityCandidate> {
        return when (kind) {
            BangumiEntityKind.CHARACTER -> bangumiCharacterCandidates.computeIfAbsent(subjectId) {
                loadSubjectRows(subjectId, "characters").mapNotNull(::parseCandidate)
            }
            BangumiEntityKind.PERSON -> bangumiPersonCandidates.computeIfAbsent(subjectId) {
                val creditedPeople = loadSubjectRows(subjectId, "persons").mapNotNull(::parseCandidate)
                val actors = loadSubjectRows(subjectId, "characters").flatMap { character ->
                    (character["actors"] as? JsonArray).orEmpty().mapNotNull { actor ->
                        (actor as? JsonObject)?.let(::parseCandidate)
                    }
                }
                creditedPeople + actors
            }
        }
    }

    private fun loadSubjectRows(subjectId: Int, path: String): List<JsonObject> {
        val cache = if (path == "characters") bangumiSubjectCharacterRows else bangumiSubjectPersonRows
        return cache.computeIfAbsent(subjectId) { loadSubjectArray(subjectId, path) }
    }

    private fun loadSubjectArray(subjectId: Int, path: String): List<JsonObject> {
        val request = Request.Builder()
            .url("https://api.bgm.tv/v0/subjects/$subjectId/$path")
            .header("User-Agent", "TouhouGO/AniHyou-android (https://github.com/TouhouGO)")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val array = json.parseToJsonElement(response.body.string()) as? JsonArray ?: return emptyList()
                array.mapNotNull { it as? JsonObject }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCandidate(value: JsonObject): BangumiEntityCandidate? {
        val id = value["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val nativeName = value["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        val actorNames = (value["actors"] as? JsonArray).orEmpty().mapNotNull { actor ->
            (actor as? JsonObject)?.get("name")?.jsonPrimitive?.content?.trim()
        }.filter(String::isNotEmpty)
        return if (nativeName.isEmpty()) null else BangumiEntityCandidate(id, nativeName, actorNames)
    }

    private fun fetchSimplifiedName(kind: BangumiEntityKind, bangumiEntityId: Int): String? {
        val path = if (kind == BangumiEntityKind.CHARACTER) "characters" else "persons"
        val request = Request.Builder()
            .url("https://api.bgm.tv/v0/$path/$bangumiEntityId")
            .header("User-Agent", "TouhouGO/AniHyou-android (https://github.com/TouhouGO)")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val detail = json.parseToJsonElement(response.body.string()).jsonObject
                val infobox = detail["infobox"] as? JsonArray ?: return null
                val rawName = infobox.firstNotNullOfOrNull { item ->
                    val field = item as? JsonObject ?: return@firstNotNullOfOrNull null
                    if (field["key"]?.jsonPrimitive?.content?.trim() == "简体中文名") {
                        field["value"]?.jsonPrimitive?.content?.trim()
                    } else null
                }
                rawName?.let(chineseConverter::toSimplified)?.takeIf(::isStrictCjkOnly)
            }
        } catch (_: Exception) {
            null
        }
    }
}
