package com.axiel7.anihyou.wear.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.axiel7.anihyou.core.localization.EntityKey
import com.axiel7.anihyou.core.localization.EntityKind
import com.axiel7.anihyou.core.localization.LocalizationSnapshot

internal data class WearTitleDisplay(val snapshot: LocalizationSnapshot, val enabled: Boolean) {
    fun title(id: Int?, original: String): String =
        if (id == null || id <= 0) original else snapshot.displayName(EntityKey(EntityKind.MEDIA, id), original, enabled)
}

internal val LocalWearTitleDisplay = compositionLocalOf { WearTitleDisplay(LocalizationSnapshot.EMPTY, false) }

@Composable
internal fun wearMediaTitle(id: Int?, original: String): String = LocalWearTitleDisplay.current.title(id, original)
