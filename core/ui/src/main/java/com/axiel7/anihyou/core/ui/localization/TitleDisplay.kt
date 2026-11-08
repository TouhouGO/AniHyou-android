package com.axiel7.anihyou.core.ui.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.axiel7.anihyou.core.localization.EntityKey
import com.axiel7.anihyou.core.localization.EntityKind
import com.axiel7.anihyou.core.localization.LocalizationSnapshot

data class LocalizationDisplayContext(
    val snapshot: LocalizationSnapshot,
    val titlesEnabled: Boolean,
    val peopleEnabled: Boolean,
    val tagsEnabled: Boolean,
) {
    private fun name(kind: EntityKind, id: Int?, original: String, enabled: Boolean): String =
        if (id == null || id <= 0) original else snapshot.displayName(EntityKey(kind, id), original, enabled)
    fun mediaTitle(id: Int?, original: String) = name(EntityKind.MEDIA, id, original, titlesEnabled)
    fun characterName(id: Int?, original: String) = name(EntityKind.CHARACTER, id, original, peopleEnabled)
    fun staffName(id: Int?, original: String) = name(EntityKind.STAFF, id, original, peopleEnabled)
    fun tagName(rawName: String) = snapshot.tagName(rawName, tagsEnabled)
    fun tagMatches(rawName: String, query: String) =
        rawName.contains(query, ignoreCase = true) || tagName(rawName).contains(query, ignoreCase = true)
}

val LocalLocalizationDisplay = compositionLocalOf { LocalizationDisplayContext(LocalizationSnapshot.EMPTY, false, false, false) }

@Composable fun displayMediaTitle(id: Int?, original: String) = LocalLocalizationDisplay.current.mediaTitle(id, original)
@Composable fun displayCharacterName(id: Int?, original: String) = LocalLocalizationDisplay.current.characterName(id, original)
@Composable fun displayStaffName(id: Int?, original: String) = LocalLocalizationDisplay.current.staffName(id, original)
@Composable fun displayTagName(rawName: String) = LocalLocalizationDisplay.current.tagName(rawName)
