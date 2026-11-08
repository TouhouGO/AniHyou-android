package com.axiel7.anihyou.core.ui.localization

import com.axiel7.anihyou.core.localization.CatalogTag
import com.axiel7.anihyou.core.localization.CatalogRecord
import com.axiel7.anihyou.core.localization.CatalogSource
import com.axiel7.anihyou.core.localization.EntityKey
import com.axiel7.anihyou.core.localization.EntityKind
import com.axiel7.anihyou.core.localization.LocalizationSnapshot
import com.axiel7.anihyou.core.localization.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleDisplayTest {
    @Test fun resolvesOnlyEnabledKnownMediaWithoutChangingRawTitle() {
        val source = CatalogSource("s", "r", "https://example.org", "CC0", "a".repeat(64))
        val snapshot = LocalizationSnapshot.create("v1", listOf(source), listOf(
            CatalogRecord(EntityKey(EntityKind.MEDIA, 20), "火影忍者", emptyList(), "s", MediaKind.ANIME)
        ))
        val raw = "Naruto"
        assertEquals("火影忍者", LocalizationDisplayContext(snapshot, true, false, false).mediaTitle(20, raw))
        assertEquals(raw, LocalizationDisplayContext(snapshot, false, false, false).mediaTitle(20, raw))
        assertEquals(raw, LocalizationDisplayContext(snapshot, true, false, false).mediaTitle(null, raw))
        assertEquals(raw, LocalizationDisplayContext(LocalizationSnapshot.EMPTY, true, false, false).mediaTitle(20, raw))
        assertEquals("Naruto", raw)
    }
    @Test fun independentDisplaySwitchesKeepTypedIdentitiesAndRawTagKeys() {
        val source = CatalogSource("s", "r", "https://example.org", "CC0", "a".repeat(64))
        val snapshot = LocalizationSnapshot.create("v", listOf(source), listOf(
            CatalogRecord(EntityKey(EntityKind.MEDIA, 7), "作品", emptyList(), "s", MediaKind.ANIME),
            CatalogRecord(EntityKey(EntityKind.CHARACTER, 7), "角色", emptyList(), "s"),
            CatalogRecord(EntityKey(EntityKind.STAFF, 7), "职员", emptyList(), "s")
        ), listOf(CatalogTag("Action", "动作", "s"), CatalogTag("Adventure", "动作", "s")))
        val context = LocalizationDisplayContext(snapshot, false, true, true)
        assertEquals("raw", context.mediaTitle(7, "raw"))
        assertEquals("角色", context.characterName(7, "raw"))
        assertEquals("职员", context.staffName(7, "raw"))
        assertEquals("raw", context.characterName(null, "raw"))
        assertEquals("raw", context.staffName(-1, "raw"))
        assertEquals(listOf("Action", "Adventure"), listOf("Action", "Adventure").filter { context.tagMatches(it, "动作") })
        assertEquals(true, context.tagMatches("Action", "act"))
        val off = LocalizationDisplayContext(snapshot, true, false, false)
        assertEquals("作品", off.mediaTitle(7, "raw"))
        assertEquals("raw", off.characterName(7, "raw"))
        assertEquals("Action", off.tagName("Action"))
        assertEquals(false, off.tagMatches("Action", "动作"))
    }

}
