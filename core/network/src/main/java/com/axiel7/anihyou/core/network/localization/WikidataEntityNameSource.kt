package com.axiel7.anihyou.core.network.localization

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.annotation.Single

data class EntitySourceResult(
    val names: Map<Int, String> = emptyMap(),
    val missIds: Set<Int> = emptySet(),
    val hasError: Boolean = false
)

@Single
class WikidataEntityNameSource(
    private val customHttpClient: OkHttpClient? = null,
    private val chineseConverter: ChineseConverter? = null
) {
    private val httpClient by lazy {
        customHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val CHUNK_SIZE = 30
        const val SPARQL_ENDPOINT = "https://query.wikidata.org/sparql"
    }

    suspend fun load(kind: BangumiEntityKind, ids: Collection<Int>): EntitySourceResult = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext EntitySourceResult()

        val prop = when (kind) {
            BangumiEntityKind.CHARACTER -> "P11736"
            BangumiEntityKind.PERSON -> "P11227"
        }

        val requestedIds = ids.toSet()
        val resolvedNames = mutableMapOf<Int, String>()
        var encounteredError = false

        for (chunk in requestedIds.chunked(CHUNK_SIZE)) {
            val valuesClause = chunk.joinToString(" ") { "\"$it\"" }
            val query = """
                SELECT ?id ?itemLabel WHERE {
                  VALUES ?id { $valuesClause }
                  ?item wdt:$prop ?id .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "zh,zh-cn,zh-tw,zh-hk,zh-sg". }
                }
            """.trimIndent()

            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "$SPARQL_ENDPOINT?query=$encodedQuery&format=json"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AniHyou/1.6.0 (https://github.com/TouhouGO/AniHyou-android; anihyou@touhougo.com)")
                    .header("Accept", "application/sparql-results+json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        encounteredError = true
                        return@use
                    }
                    val bodyString = response.body.string()
                    val parsed = json.parseToJsonElement(bodyString).jsonObject
                    val results = parsed["results"]?.jsonObject
                    val bindings = results?.get("bindings") as? JsonArray ?: return@use

                    for (bindingElem in bindings) {
                        val binding = bindingElem.jsonObject
                        val idStr = binding["id"]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: continue
                        val id = idStr.toIntOrNull() ?: continue
                        if (!requestedIds.contains(id)) {
                            // Discard non-requested IDs
                            continue
                        }

                        val labelObj = binding["itemLabel"]?.jsonObject
                        val label = labelObj?.get("value")?.jsonPrimitive?.content?.trim() ?: continue
                        val lang = labelObj["xml:lang"]?.jsonPrimitive?.content.orEmpty()

                        // Only accept Chinese language labels or labels containing CJK characters
                        if (!lang.startsWith("zh") && !hasCjk(label)) {
                            continue
                        }

                        val simplified = chineseConverter?.toSimplified(label) ?: label
                        if (simplified.isNotBlank()) {
                            resolvedNames[id] = simplified
                        }
                    }
                }
            } catch (_: Exception) {
                encounteredError = true
            }
        }

        val missIds = if (!encounteredError) {
            requestedIds - resolvedNames.keys
        } else {
            emptySet()
        }

        EntitySourceResult(
            names = resolvedNames,
            missIds = missIds,
            hasError = encounteredError
        )
    }

    private fun hasCjk(text: String): Boolean {
        return text.any { c ->
            val code = c.code
            (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF)
        }
    }
}
