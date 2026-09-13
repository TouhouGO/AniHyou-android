package com.axiel7.anihyou.core.network.localization

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

@Serializable
internal data class CachedEntityEntry(
    val value: String,
    val expiresAt: Long
)

@Serializable
internal data class EntityNameCachePayload(
    val formatVersion: Int = 1,
    val entries: Map<String, CachedEntityEntry> = emptyMap()
)

@Single
class EntityNameCache {

    private val json = Json { ignoreUnknownKeys = true }
    private val memoryMap = ConcurrentHashMap<String, CachedEntityEntry>()

    @Volatile
    private var storageFile: File? = null

    companion object {
        const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
        const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000       // 7 days
    }

    private fun key(kind: BangumiEntityKind, id: Int): String {
        val prefix = when (kind) {
            BangumiEntityKind.CHARACTER -> "char"
            BangumiEntityKind.PERSON -> "person"
        }
        return "${prefix}_$id"
    }

    fun setStorageFile(file: File, legacyFile: File? = null) {
        synchronized(this) {
            storageFile = file
            memoryMap.clear()

            // Migration from legacy location if needed
            if (legacyFile != null && legacyFile.exists()) {
                if (!file.exists() || file.length() == 0L) {
                    try {
                        val parent = file.parentFile
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs()
                        }
                        val legacyText = legacyFile.readText()
                        val tempTarget = File(file.parentFile, "${file.name}.migrating")
                        tempTarget.writeText(legacyText)
                        if (file.exists()) {
                            file.delete()
                        }
                        tempTarget.renameTo(file)
                        legacyFile.delete()
                    } catch (_: Exception) {
                        // In case migration file write fails, don't crash
                    }
                } else {
                    // Target already exists, clean up legacy file to complete migration
                    try {
                        legacyFile.delete()
                    } catch (_: Exception) {}
                }
            }

            if (file.exists() && file.length() > 0) {
                try {
                    val text = file.readText()
                    val payload = json.decodeFromString<EntityNameCachePayload>(text)
                    memoryMap.putAll(payload.entries)
                } catch (_: Exception) {
                    // Recover gracefully from corrupt file
                    memoryMap.clear()
                }
            }
        }
    }

    fun get(kind: BangumiEntityKind, id: Int, now: Long = System.currentTimeMillis()): String? {
        val k = key(kind, id)
        val entry = memoryMap[k] ?: return null
        return if (entry.expiresAt > now) {
            entry.value
        } else {
            memoryMap.remove(k)
            null
        }
    }

    fun putPositive(kind: BangumiEntityKind, id: Int, name: String, now: Long = System.currentTimeMillis()) {
        val entry = CachedEntityEntry(
            value = name,
            expiresAt = now + POSITIVE_TTL_MS
        )
        memoryMap[key(kind, id)] = entry
        persistAsync()
    }

    fun putMiss(kind: BangumiEntityKind, id: Int, now: Long = System.currentTimeMillis()) {
        val entry = CachedEntityEntry(
            value = "", // Sentinel for negative / miss cache
            expiresAt = now + MISS_TTL_MS
        )
        memoryMap[key(kind, id)] = entry
        persistAsync()
    }

    private fun persistAsync() {
        val file = storageFile ?: return
        synchronized(this) {
            try {
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val payload = EntityNameCachePayload(
                    formatVersion = 1,
                    entries = memoryMap
                )
                val text = json.encodeToString(EntityNameCachePayload.serializer(), payload)
                val tempFile = File(file.parentFile, "${file.name}.tmp")
                tempFile.writeText(text)
                if (file.exists()) {
                    file.delete()
                }
                tempFile.renameTo(file)
            } catch (_: Exception) {
                // Ignore IO errors in cache persistence
            }
        }
    }
}
