package com.axiel7.anihyou.core.localization

import java.io.File
import java.security.MessageDigest
import kotlin.system.measureNanoTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A repeatable host probe, not an Android latency test or a timing-sensitive unit test. */
fun main(args: Array<String>) {
    val bytes = requireNotNull(LocalizationSnapshot::class.java.getResourceAsStream("/localization/catalog.json"))
        .use { it.readBytes() }
    val text = bytes.toString(Charsets.UTF_8)
    lateinit var catalog: LocalizationSnapshot
    val firstLoad = measureNanoTime { catalog = LocalizationCatalog.decode(text) }
    repeat(3) { catalog = LocalizationCatalog.decode(text) }
    var loadConsumed = 0L
    val loadTimes = List(30) {
        val loadTime = measureNanoTime { catalog = LocalizationCatalog.decode(text) }
        loadConsumed = loadConsumed * 31 + consumeLoadMetadata(catalog)
        loadTime
    }
    val queries = listOf(
        MediaKind.ANIME to "\u661f",
        MediaKind.ANIME to "\u4e4b",
        MediaKind.ANIME to "\u706b\u5f71",
        MediaKind.ANIME to "\u7269\u8bed",
        MediaKind.ANIME to "\u706b\u5f71\u5fcd\u8005",
        MediaKind.ANIME to "\u540d\u4fa6\u63a2\u67ef\u5357",
        MediaKind.ANIME to "\u9032\u64ca\u7684\u5de8\u4eba",
        MediaKind.ANIME to "\u3010\u6211\u63a8\u7684\u5b69\u5b50\u3011",
        MediaKind.ANIME to "\u5b64\u72ec\u6447\u6eda",
        MediaKind.ANIME to "\u94fe\u952f\u4eba",
        MediaKind.ANIME to "\u91cd\u542f",
        MediaKind.ANIME to "\u54b2\u826f",
        MediaKind.ANIME to "Re:Zero",
        MediaKind.ANIME to "\u846c\u9001\u7684\u8299\u8389\u83b2",
        MediaKind.MANGA to "\u846c\u9001\u7684\u8299\u8389\u83b2",
        MediaKind.MANGA to "\u706b\u5f71",
        MediaKind.ANIME to "not-a-catalog-title-8247301",
    )
    var consumed = 0L
    repeat(30) {
        queries.forEach { (kind, query) -> consumed += catalog.searchAllMedia(query, kind).ids.sumOf(Int::toLong) }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val queryResults = buildJsonArray {
        for ((kind, query) in queries) {
            val timings = ArrayList<Long>(150)
            repeat(150) {
                timings += measureNanoTime {
                    val result = catalog.searchAllMedia(query, kind)
                    consumed += result.ids.sumOf(Int::toLong) + result.totalMatches
                }
            }
            val result = catalog.searchAllMedia(query, kind)
            digest.update("$kind:$query:${result.ids}:${result.totalMatches}\n".toByteArray())
            add(buildJsonObject {
                put("kind", kind.name)
                put("query", query)
                put("returnedIds", result.ids.size)
                put("totalMatches", result.totalMatches)
                put("truncated", result.isTruncated)
                put("p50Millis", percentile(timings, 0.50))
                put("p95Millis", percentile(timings, 0.95))
            })
        }
    }
    val report = buildJsonObject {
        put("measurement", "host JVM; no network; no result cache; not Android device latency")
        put("candidateMode", "complete local candidates; no 500-result prefix")
        put("javaVersion", System.getProperty("java.version"))
        put("os", System.getProperty("os.name"))
        put("architecture", System.getProperty("os.arch"))
        put("catalogVersion", catalog.version)
        put("catalogBytes", bytes.size)
        put("catalogSha256", sha256(bytes))
        put("firstDecodeAndIndexMillis", firstLoad / 1_000_000.0)
        put("decodeIndexMeasurement", "one initial observation; 3 discarded in-process warmups; 30 measured warm samples; not a cold-start P95")
        put("warmedDecodeAndIndexWarmupIterations", 3)
        put("warmedDecodeAndIndexSamples", loadTimes.size)
        put("warmedDecodeAndIndexP50Millis", percentile(loadTimes, 0.50))
        put("warmedDecodeAndIndexP95Millis", percentile(loadTimes, 0.95))
        put("warmedDecodeAndIndexMillis", buildJsonArray {
            loadTimes.forEach { add(JsonPrimitive(it / 1_000_000.0)) }
        })
        val representativeTitle = catalog.displayName(EntityKey(EntityKind.MEDIA, 1735), "", true)
        put("consumedLoadMetadata", buildJsonObject {
            put("checksum", loadConsumed)
            put("metadataFields", "version; recordCount; sourceCount; representativeTitle")
            put("version", catalog.version)
            put("recordCount", catalog.recordCount)
            put("sourceCount", catalog.sources.size)
            put("representativeMediaId", 1735)
            put("representativeTitleHash", representativeTitle.hashCode())
        })
        put("samplesPerQuery", 150)
        put("resultDigest", digest.digest().joinToString("") { "%02x".format(it) })
        put("consumed", consumed)
        put("queries", queryResults)
    }
    val formatted = Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), report)
    if (args.isNotEmpty()) File(args.single()).apply { parentFile?.mkdirs(); writeText(formatted + "\n") }
    println(formatted)
}

private fun percentile(values: List<Long>, percentile: Double): Double {
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * percentile).toInt()] / 1_000_000.0
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun consumeLoadMetadata(snapshot: LocalizationSnapshot): Long {
    var value = snapshot.version.hashCode().toLong()
    value = value * 31 + snapshot.recordCount
    value = value * 31 + snapshot.sources.size
    value = value * 31 + snapshot.displayName(EntityKey(EntityKind.MEDIA, 1735), "", true).hashCode()
    return value
}
