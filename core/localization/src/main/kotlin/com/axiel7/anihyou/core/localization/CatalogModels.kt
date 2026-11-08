package com.axiel7.anihyou.core.localization

import java.util.Collections
import kotlinx.serialization.Serializable

@Serializable enum class EntityKind { MEDIA, CHARACTER, STAFF }
@Serializable enum class Platform { ANILIST }
@Serializable enum class MediaKind { ANIME, MANGA }

data class MediaIdentity(val kind: MediaKind, val id: Int) {
    init { require(id > 0) { "Media ID must be positive" } }
}

data class BangumiLink(
    val media: MediaIdentity,
    val subjectId: Int,
    val sourceId: String,
) {
    init {
        require(subjectId > 0) { "Bangumi subject ID must be positive" }
        require(sourceId.isNotBlank()) { "Link source must not be blank" }
    }
}

@Serializable
data class EntityKey(val kind: EntityKind, val id: Int, val platform: Platform = Platform.ANILIST) {
    init { require(id > 0) { "Entity ID must be positive" } }
}

data class CatalogSource(val id: String, val revision: String, val url: String, val license: String, val sha256: String) {
    init {
        require(listOf(id, revision, url, license).all { it.isNotBlank() }) { "Source metadata must not be blank" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SHA-256" }
    }
}

class CatalogRecord(val key: EntityKey, val displayName: String, aliases: List<String>, val sourceId: String, val mediaKind: MediaKind? = null) {
    val aliases: List<String> = Collections.unmodifiableList(aliases.toList())
    init {
        require(displayName.isNotBlank() && sourceId.isNotBlank() && this.aliases.all { it.isNotBlank() }) { "Record names and source must not be blank" }
        require((key.kind == EntityKind.MEDIA) == (mediaKind != null)) { "Only media records require mediaKind" }
    }
}

class MediaCandidates internal constructor(ids: List<Int>, val totalMatches: Int, val isTruncated: Boolean, val catalogVersion: String) {
    val ids: List<Int> = Collections.unmodifiableList(ids.toList())
}

data class CatalogTag(val rawName: String, val displayName: String, val sourceId: String) {
    init {
        require(listOf(rawName, displayName, sourceId).all { it.isNotBlank() }) { "Tag names and source must not be blank" }
        require("{{" !in rawName && "}}" !in rawName) { "Placeholder is not a raw tag key" }
    }
}

class SearchAlias(val aniListId: Int, names: List<String>, val sourceId: String, val mediaKind: MediaKind? = null) {
    val names: List<String> = Collections.unmodifiableList(names.toList())
    init {
        require(aniListId > 0 && sourceId.isNotBlank() && this.names.isNotEmpty() && this.names.all { it.isNotBlank() }) { "Invalid search alias" }
    }
}
