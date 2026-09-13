package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.type.MediaType

data class BangumiSubjectCandidate(
    val id: Int,
    val type: Int, // 1 = book/manga, 2 = anime
    val name: String,
    val nameCn: String,
    val airDate: String? // e.g. "2023-10-05"
)

data class BangumiCacheKey(
    val mediaId: Int,
    val explicitBangumiId: Int?,
    val normalizedTitle: String?,
    val mediaType: MediaType,
    val releaseYear: Int?
)

object BangumiSubjectMatcher {

    fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("[\\s\\p{Punct}〜～・·:：!！?？\\-]+"), "")
            .trim()
    }

    /**
     * Pure, strict matcher for Bangumi candidates.
     * Rules:
     * 1. Query title must not be blank after normalization.
     * 2. Candidate type must match expected type (Anime = 2, Manga = 1).
     * 3. Candidate name or name_cn must match queryTitle exactly after normalization.
     * 4. releaseYear is REQUIRED. If null, match is rejected.
     * 5. Candidate airDate must have a 4-digit year matching releaseYear. If missing or different, candidate is rejected.
     * 6. Unique Bangumi ID requirement: after filtering matching candidates and deduplicating by ID,
     *    there must be EXACTLY ONE unique Bangumi ID. If 0 or >1, return null (ambiguous).
     */
    fun findUnique(
        queryTitle: String,
        mediaType: MediaType,
        releaseYear: Int?,
        candidates: List<BangumiSubjectCandidate>
    ): Int? {
        if (releaseYear == null) return null

        val normQuery = normalizeTitle(queryTitle)
        if (normQuery.isBlank()) return null

        val targetType = when (mediaType) {
            MediaType.ANIME -> 2
            MediaType.MANGA -> 1
            else -> return null
        }

        val matchingIds = mutableSetOf<Int>()

        for (candidate in candidates) {
            if (candidate.type != targetType) continue

            val normName = normalizeTitle(candidate.name)
            val normNameCn = normalizeTitle(candidate.nameCn)

            val nameMatches = (normName.isNotBlank() && normName == normQuery) ||
                    (normNameCn.isNotBlank() && normNameCn == normQuery)

            if (!nameMatches) continue

            // Candidate year check: must exist and match releaseYear
            val candidateYear = candidate.airDate?.trim()?.take(4)?.toIntOrNull()
            if (candidateYear == null || candidateYear != releaseYear) {
                continue
            }

            matchingIds.add(candidate.id)
        }

        return if (matchingIds.size == 1) {
            matchingIds.first()
        } else {
            null
        }
    }
}
