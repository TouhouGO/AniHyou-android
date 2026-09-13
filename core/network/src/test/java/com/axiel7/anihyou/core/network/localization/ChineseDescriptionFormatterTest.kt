package com.axiel7.anihyou.core.network.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseDescriptionFormatterTest {

    private val converter = ChineseConverter(LocalizationBundleManager())

    @Test
    fun testChineseSummaryCompletelyReplacesEnglishOriginal() {
        assertEquals(
            "<p><strong>【剧情简介】</strong></p><p>只有中文简介。</p>",
            ChineseDescriptionFormatter.format(
                bangumiSummary = "<p>只有中文简介。</p>",
                originalDescription = "English synopsis that should disappear.",
                chineseConverter = converter
            )
        )
    }

    @Test
    fun testOriginalDescriptionIsRetainedOnlyWhenChineseSummaryIsMissing() {
        assertEquals(
            "這是原始簡介。".let { converter.toSimplified(it) },
            ChineseDescriptionFormatter.format(
                bangumiSummary = null,
                originalDescription = "這是原始簡介。",
                chineseConverter = converter
            )
        )
    }
}
