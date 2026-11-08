package com.axiel7.anihyou.core.localization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object LocalizationCatalog {
    fun decode(text: String): LocalizationSnapshot {
        val root = Json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("Catalog must be an object")
        requireJsonInt(root["schemaVersion"])
        val records = root["records"] as? JsonArray
            ?: throw IllegalArgumentException("Records must be an array")
        for (record in records) {
            val key = (record as? JsonObject)?.get("key") as? JsonObject
                ?: throw IllegalArgumentException("Record key must be an object")
            requireJsonInt(key["id"])
        }
        root["tags"]?.let { tags ->
            require(tags is JsonArray) { "Tags must be an array" }
            tags.forEach { tag ->
                require(tag is JsonObject) { "Tag must be an object" }
                listOf("rawName", "displayName", "sourceId").forEach { field ->
                    require((tag[field] as? JsonPrimitive)?.isString == true) { "Tag fields must be strings" }
                }
            }
        }
        root["links"]?.let { links ->
            require(links is JsonArray) { "Links must be an array" }
            links.forEach { link ->
                require(link is JsonObject) { "Link must be an object" }
                requireJsonInt(link["aniListId"])
                requireJsonInt(link["bangumiSubjectId"])
                listOf("mediaKind", "sourceId").forEach { field ->
                    require((link[field] as? JsonPrimitive)?.isString == true) { "Link fields must have the expected types" }
                }
            }
        }
        root["searchAliases"]?.let { aliases ->
            require(aliases is JsonArray) { "Search aliases must be an array" }
            aliases.forEach { alias ->
                require(alias is JsonObject) { "Search alias must be an object" }
                requireJsonInt(alias["aniListId"])
                require((alias["sourceId"] as? JsonPrimitive)?.isString == true) { "Invalid alias source" }
                require(alias["names"] is JsonArray && (alias["names"] as JsonArray).all { (it as? JsonPrimitive)?.isString == true }) { "Invalid alias names" }
            }
        }
        DuplicateKeyChecker(text).check()
        val dto = Json.decodeFromString<CatalogDto>(text)
        require(dto.schemaVersion == 1) { "Unsupported catalog schema" }
        return LocalizationSnapshot.create(
            version = dto.version,
            sources = dto.sources.map { CatalogSource(it.id, it.revision, it.url, it.license, it.sha256) },
            records = dto.records.map { CatalogRecord(it.key, it.displayName, it.aliases, it.sourceId, it.mediaKind) },
            tags = dto.tags.map { CatalogTag(it.rawName, it.displayName, it.sourceId) },
            searchAliases = dto.searchAliases.map { SearchAlias(it.aniListId, it.names, it.sourceId, it.mediaKind) },
            links = dto.links.map { BangumiLink(MediaIdentity(it.mediaKind, it.aniListId), it.bangumiSubjectId, it.sourceId) },
        )
    }
}

private fun requireJsonInt(value: kotlinx.serialization.json.JsonElement?) {
    val primitive = value as? JsonPrimitive
    require(primitive != null && !primitive.isString && primitive.content.toIntOrNull() != null) {
        "Expected JSON integer"
    }
}

@Serializable private data class CatalogDto(
    val schemaVersion: Int,
    val version: String,
    val sources: List<SourceDto>,
    val records: List<RecordDto>,
    val tags: List<TagDto> = emptyList(),
    val links: List<LinkDto> = emptyList(),
    val searchAliases: List<SearchAliasDto> = emptyList(),
)
@Serializable private data class SearchAliasDto(val aniListId: Int, val names: List<String>, val sourceId: String, val mediaKind: MediaKind? = null)
@Serializable private data class LinkDto(
    val mediaKind: MediaKind,
    val aniListId: Int,
    val bangumiSubjectId: Int,
    val sourceId: String,
)
@Serializable private data class TagDto(val rawName: String, val displayName: String, val sourceId: String)
@Serializable private data class SourceDto(val id: String, val revision: String, val url: String, val license: String, val sha256: String)
@Serializable private data class RecordDto(val key: EntityKey, val displayName: String, val aliases: List<String>, val sourceId: String, val mediaKind: MediaKind? = null)

// kotlinx.serialization accepts duplicate object keys, so reject them before decoding.
private class DuplicateKeyChecker(private val text: String) {
    private var position = 0
    fun check() { value() }
    private fun whitespace() { while (position < text.length && text[position].isWhitespace()) position++ }
    private fun string(): String {
        val start = position++
        while (true) {
            when (text[position++]) {
                '\\' -> position++
                '"' -> return Json.decodeFromString(text.substring(start, position))
            }
        }
    }
    private fun value() {
        whitespace()
        when (text[position]) {
            '{' -> {
                position++
                val keys = mutableSetOf<String>()
                whitespace()
                if (text[position] == '}') { position++; return }
                while (true) {
                    whitespace()
                    require(keys.add(string())) { "Duplicate JSON key" }
                    whitespace(); position++ // colon
                    value(); whitespace()
                    if (text[position++] == '}') return
                }
            }
            '[' -> {
                position++; whitespace()
                if (text[position] == ']') { position++; return }
                while (true) {
                    value(); whitespace()
                    if (text[position++] == ']') return
                }
            }
            '"' -> { string() }
            else -> while (position < text.length && text[position] !in ",]} \t\r\n") position++
        }
    }
}
