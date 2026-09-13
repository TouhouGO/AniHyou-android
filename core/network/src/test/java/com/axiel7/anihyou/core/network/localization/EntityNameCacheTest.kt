package com.axiel7.anihyou.core.network.localization

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityNameCacheTest {

    @Test
    fun testPositiveCacheHitWithin30DaysAndExpiredAfter30Days() {
        val tempDir = Files.createTempDirectory("entity_cache_test").toFile()
        val cacheFile = File(tempDir, "entity_names_cache.json")
        try {
            val cache = EntityNameCache()
            cache.setStorageFile(cacheFile)

            val t0 = 1_000_000_000_000L
            cache.putPositive(BangumiEntityKind.CHARACTER, 101, "安乐冈花火", now = t0)

            // Within 30 days (e.g. 29 days later)
            val tWithin = t0 + (29L * 24 * 60 * 60 * 1000)
            assertEquals("安乐冈花火", cache.get(BangumiEntityKind.CHARACTER, 101, now = tWithin))

            // After 30 days + 1 second
            val tAfter = t0 + (30L * 24 * 60 * 60 * 1000) + 1000
            assertNull(cache.get(BangumiEntityKind.CHARACTER, 101, now = tAfter))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testMissCacheHitWithin7DaysAndExpiredAfter7Days() {
        val tempDir = Files.createTempDirectory("entity_cache_test").toFile()
        val cacheFile = File(tempDir, "entity_names_cache.json")
        try {
            val cache = EntityNameCache()
            cache.setStorageFile(cacheFile)

            val t0 = 1_000_000_000_000L
            cache.putMiss(BangumiEntityKind.PERSON, 202, now = t0)

            // Miss cache returns empty string "" as a sentinel for cached miss
            val tWithin = t0 + (6L * 24 * 60 * 60 * 1000)
            assertEquals("", cache.get(BangumiEntityKind.PERSON, 202, now = tWithin))

            // Expired miss (after 7 days)
            val tAfter = t0 + (7L * 24 * 60 * 60 * 1000) + 1000
            assertNull(cache.get(BangumiEntityKind.PERSON, 202, now = tAfter))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPersistenceAndReloadAcrossInstances() {
        val tempDir = Files.createTempDirectory("entity_cache_test").toFile()
        val cacheFile = File(tempDir, "entity_names_cache.json")
        try {
            val cache1 = EntityNameCache()
            cache1.setStorageFile(cacheFile)

            val t0 = 1_000_000_000_000L
            cache1.putPositive(BangumiEntityKind.CHARACTER, 303, "水星领航员", now = t0)
            cache1.putMiss(BangumiEntityKind.PERSON, 404, now = t0)

            val cache2 = EntityNameCache()
            cache2.setStorageFile(cacheFile)

            assertEquals("水星领航员", cache2.get(BangumiEntityKind.CHARACTER, 303, now = t0 + 1000))
            assertEquals("", cache2.get(BangumiEntityKind.PERSON, 404, now = t0 + 1000))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRecoversGracefullyFromCorruptedCacheFile() {
        val tempDir = Files.createTempDirectory("entity_cache_test").toFile()
        val cacheFile = File(tempDir, "entity_names_cache.json")
        try {
            cacheFile.writeText("{ corrupted json ... [}")

            val cache = EntityNameCache()
            cache.setStorageFile(cacheFile)

            assertNull(cache.get(BangumiEntityKind.CHARACTER, 505, now = 1000L))

            // Writing to it should restore a clean state
            cache.putPositive(BangumiEntityKind.CHARACTER, 505, "恢复正常", now = 1000L)
            assertEquals("恢复正常", cache.get(BangumiEntityKind.CHARACTER, 505, now = 1000L))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testMigratesFromLegacyFileWhenTargetDoesNotExist() {
        val tempDir = Files.createTempDirectory("entity_cache_migrate_test").toFile()
        try {
            val legacyFile = File(tempDir, "localization/entity_names_cache.json")
            legacyFile.parentFile.mkdirs()
            val stateDir = File(tempDir, "localization-state")
            val targetFile = File(stateDir, "entity_names_cache.json")

            // Populate legacy cache
            val legacyCache = EntityNameCache()
            legacyCache.setStorageFile(legacyFile)
            legacyCache.putPositive(BangumiEntityKind.CHARACTER, 606, "旧位置角色", now = 1000L)
            assertEquals("旧位置角色", legacyCache.get(BangumiEntityKind.CHARACTER, 606, now = 1000L))

            // Now initialize new cache pointing to targetFile with legacyFile migration
            val newCache = EntityNameCache()
            newCache.setStorageFile(targetFile, legacyFile = legacyFile)

            // Value should be preserved in newCache
            assertEquals("旧位置角色", newCache.get(BangumiEntityKind.CHARACTER, 606, now = 1000L))
            // Target file should now exist
            org.junit.Assert.assertTrue("Target file must exist after migration", targetFile.exists())
            // Legacy file should be deleted after migration
            org.junit.Assert.assertFalse("Legacy file must be removed after migration", legacyFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPreservesTargetFileWhenBothTargetAndLegacyExist() {
        val tempDir = Files.createTempDirectory("entity_cache_preserve_test").toFile()
        try {
            val legacyFile = File(tempDir, "localization/entity_names_cache.json")
            legacyFile.parentFile.mkdirs()
            val stateDir = File(tempDir, "localization-state")
            val targetFile = File(stateDir, "entity_names_cache.json")

            // Populate legacy cache
            val legacyCache = EntityNameCache()
            legacyCache.setStorageFile(legacyFile)
            legacyCache.putPositive(BangumiEntityKind.CHARACTER, 707, "旧数据", now = 1000L)

            // Populate target cache
            val targetCache = EntityNameCache()
            targetCache.setStorageFile(targetFile)
            targetCache.putPositive(BangumiEntityKind.CHARACTER, 707, "新数据", now = 1000L)

            // Re-initialize with both files present
            val cache = EntityNameCache()
            cache.setStorageFile(targetFile, legacyFile = legacyFile)

            // Target data must win, not overwritten by legacy
            assertEquals("新数据", cache.get(BangumiEntityKind.CHARACTER, 707, now = 1000L))
            // Legacy file should be cleaned up
            org.junit.Assert.assertFalse("Legacy file should be cleaned up", legacyFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
