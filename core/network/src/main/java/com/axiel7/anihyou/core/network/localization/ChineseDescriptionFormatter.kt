package com.axiel7.anihyou.core.network.localization

object ChineseDescriptionFormatter {

    fun format(
        bangumiSummary: String?,
        originalDescription: String?,
        chineseConverter: ChineseConverter?
    ): String? {
        if (!bangumiSummary.isNullOrBlank()) {
            return "<p><strong>【剧情简介】</strong></p>$bangumiSummary"
        }
        if (originalDescription.isNullOrBlank()) return originalDescription
        return chineseConverter?.toSimplified(originalDescription) ?: originalDescription
    }
}
