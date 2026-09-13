package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.fragment.MediaCharacter
import com.axiel7.anihyou.core.network.fragment.MediaStaff
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
class ChineseCharacterProvider(
    private val bundleManager: LocalizationBundleManager,
    private val chineseConverter: ChineseConverter,
    private val customHttpClient: OkHttpClient? = null,
    private val entityNameResolver: ChineseEntityNameResolver? = null
) {
    private val httpClient by lazy {
        customHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private val effectiveResolver: ChineseEntityNameResolver by lazy {
        entityNameResolver ?: ChineseEntityNameResolver(
            bundleManager = bundleManager,
            entityNameCache = EntityNameCache(),
            wikidataSource = WikidataEntityNameSource(httpClient, chineseConverter),
            chineseConverter = chineseConverter,
            customHttpClient = httpClient
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    private var cachedStaffCharMap: Map<String, String>? = null

    init {
        bundleManager.registerReloadListener {
            reload()
        }
    }

    fun reload() {
        synchronized(this) {
            cachedStaffCharMap = null
            runtimeCharacterNames.clear()
            runtimePersonNames.clear()
            effectiveResolver.clearRuntime()
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

    // AniList person/character ID mappings: "person_123" -> "花泽香菜", "char_456" -> "安乐冈花火"
    private val staffCharMap: Map<String, String>
        get() = cachedStaffCharMap ?: synchronized(this) {
            cachedStaffCharMap ?: loadStaffCharMap().also { cachedStaffCharMap = it }
        }

    private val runtimeCharacterNames = ConcurrentHashMap<Int, String>()
    private val runtimePersonNames = ConcurrentHashMap<Int, String>()

    private fun normalize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name.lowercase().filter { it.isLetterOrDigit() }
    }

    fun getChineseStaffOrVoiceActor(
        personId: Int?,
        currentName: String?,
        nativeName: String? = null,
        alternative: List<String>? = null
    ): String? {
        if (personId != null) {
            staffCharMap["person_$personId"]?.let { return it }
            runtimePersonNames[personId]?.let { return it }
        }
        val norm = normalize(currentName)
        if (norm.isNotEmpty()) {
            staffCharMap["name_$norm"]?.let { return it }
        }
        // Native Japanese Kanji name to Simplified Chinese (strict CJK: reject Japanese Kana)
        if (!nativeName.isNullOrBlank() && effectiveResolver.isStrictCjkOnly(nativeName)) {
            val simplifiedNative = chineseConverter.toSimplified(nativeName)
            if (simplifiedNative != null && effectiveResolver.isStrictCjkOnly(simplifiedNative)) {
                return simplifiedNative
            }
        }
        // Chinese name in alternatives
        alternative?.forEach { alt ->
            if (effectiveResolver.isStrictCjkOnly(alt)) {
                val simplifiedAlt = chineseConverter.toSimplified(alt)
                if (simplifiedAlt != null && effectiveResolver.isStrictCjkOnly(simplifiedAlt)) return simplifiedAlt
            }
        }
        val simplified = chineseConverter.toSimplified(currentName)
        if (simplified != null && simplified != currentName && effectiveResolver.isStrictCjkOnly(simplified)) {
            return simplified
        }
        return null
    }

    fun getChineseCharacter(
        characterId: Int?,
        currentName: String?,
        nativeName: String? = null,
        alternative: List<String>? = null
    ): String? {
        if (characterId != null) {
            staffCharMap["char_$characterId"]?.let { return it }
            runtimeCharacterNames[characterId]?.let { return it }
        }
        val norm = normalize(currentName)
        if (norm.isNotEmpty()) {
            staffCharMap["name_$norm"]?.let { return it }
        }
        // Native Japanese Kanji name to Simplified Chinese (strict CJK: reject Japanese Kana)
        if (!nativeName.isNullOrBlank() && effectiveResolver.isStrictCjkOnly(nativeName)) {
            val simplifiedNative = chineseConverter.toSimplified(nativeName)
            if (simplifiedNative != null && effectiveResolver.isStrictCjkOnly(simplifiedNative)) {
                return simplifiedNative
            }
        }
        // Chinese name in alternatives
        alternative?.forEach { alt ->
            if (effectiveResolver.isStrictCjkOnly(alt)) {
                val simplifiedAlt = chineseConverter.toSimplified(alt)
                if (simplifiedAlt != null && effectiveResolver.isStrictCjkOnly(simplifiedAlt)) return simplifiedAlt
            }
        }
        val simplified = chineseConverter.toSimplified(currentName)
        if (simplified != null && simplified != currentName && effectiveResolver.isStrictCjkOnly(simplified)) {
            return simplified
        }
        return null
    }

    suspend fun resolveBangumiNames(
        bangumiSubjectId: Int,
        kind: BangumiEntityKind,
        entities: List<BangumiEntityRef>
    ): Map<Int, String> {
        val resolved = effectiveResolver.resolve(bangumiSubjectId, kind, entities)
        val runtimeNames = if (kind == BangumiEntityKind.CHARACTER) runtimeCharacterNames else runtimePersonNames
        for ((id, name) in resolved) {
            runtimeNames[id] = name
        }
        return resolved
    }

    suspend fun localizeMediaCharacters(
        bangumiSubjectId: Int?,
        characters: List<MediaCharacter>
    ): List<MediaCharacter> {
        if (!isEnabled || characters.isEmpty()) return characters

        if (bangumiSubjectId != null) {
            resolveBangumiNames(
                bangumiSubjectId,
                BangumiEntityKind.CHARACTER,
                characters.mapNotNull { character ->
                    character.node?.takeIf { node ->
                        getChineseCharacter(
                            node.id,
                            node.name?.userPreferred,
                            node.name?.native,
                            node.name?.alternative?.filterNotNull()
                        ) == null
                    }?.let {
                        BangumiEntityRef(
                            it.id,
                            it.name?.native,
                            it.name?.alternative?.filterNotNull().orEmpty(),
                            character.voiceActors.orEmpty().flatMap { voiceActor ->
                                val name = voiceActor?.commonVoiceActor?.name
                                listOfNotNull(name?.native, name?.userPreferred) +
                                    name?.alternative?.filterNotNull().orEmpty()
                            }
                        )
                    }
                }
            )
            resolveBangumiNames(
                bangumiSubjectId,
                BangumiEntityKind.PERSON,
                characters.flatMap { character ->
                    character.voiceActors.orEmpty().mapNotNull { voiceActor ->
                        voiceActor?.commonVoiceActor?.takeIf {
                            getChineseStaffOrVoiceActor(
                                it.id,
                                it.name?.userPreferred,
                                it.name?.native,
                                it.name?.alternative?.filterNotNull()
                            ) == null
                        }?.let {
                            BangumiEntityRef(
                                it.id,
                                it.name?.native,
                                it.name?.alternative?.filterNotNull().orEmpty()
                            )
                        }
                    }
                }
            )
        }

        return characters.map { character ->
            val localizedNode = character.node?.let { node ->
                val name = node.name ?: return@let node
                val localized = getChineseCharacter(
                    node.id,
                    name.userPreferred,
                    name.native,
                    name.alternative?.filterNotNull()
                ) ?: return@let node
                node.copy(name = name.copy(userPreferred = localized))
            }
            val localizedVoiceActors = character.voiceActors?.map { voiceActor ->
                voiceActor?.let {
                    val actor = it.commonVoiceActor
                    val name = actor.name ?: return@let it
                    val localized = getChineseStaffOrVoiceActor(
                        actor.id,
                        name.userPreferred,
                        name.native,
                        name.alternative?.filterNotNull()
                    ) ?: return@let it
                    it.copy(commonVoiceActor = actor.copy(name = name.copy(userPreferred = localized)))
                }
            }
            character.copy(node = localizedNode, voiceActors = localizedVoiceActors)
        }
    }

    suspend fun localizeMediaStaff(
        bangumiSubjectId: Int?,
        staff: List<MediaStaff>
    ): List<MediaStaff> {
        if (!isEnabled || staff.isEmpty()) return staff

        if (bangumiSubjectId != null) {
            resolveBangumiNames(
                bangumiSubjectId,
                BangumiEntityKind.PERSON,
                staff.mapNotNull { item ->
                    item.node?.takeIf { node ->
                        getChineseStaffOrVoiceActor(
                            node.id,
                            node.name?.userPreferred,
                            node.name?.native,
                            node.name?.alternative?.filterNotNull()
                        ) == null
                    }?.let {
                        BangumiEntityRef(
                            it.id,
                            it.name?.native,
                            it.name?.alternative?.filterNotNull().orEmpty()
                        )
                    }
                }
            )
        }

        return staff.map { item ->
            val localizedNode = item.node?.let { node ->
                val name = node.name ?: return@let node
                val localized = getChineseStaffOrVoiceActor(
                    node.id,
                    name.userPreferred,
                    name.native,
                    name.alternative?.filterNotNull()
                ) ?: return@let node
                node.copy(name = name.copy(userPreferred = localized))
            }
            item.copy(node = localizedNode)
        }
    }
}

enum class BangumiEntityKind { CHARACTER, PERSON }

data class BangumiEntityRef(
    val id: Int,
    val nativeName: String?,
    val aliases: List<String> = emptyList(),
    val actorAliases: List<String> = emptyList()
)

internal data class BangumiEntityCandidate(
    val id: Int,
    val nativeName: String,
    val actorNames: List<String> = emptyList()
)
