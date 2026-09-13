package com.axiel7.anihyou.feature.settings.localization

import androidx.compose.runtime.Immutable
import com.axiel7.anihyou.core.base.event.UiEvent

@Immutable
interface LocalizationSettingsEvent : UiEvent {
    fun setChineseTitleLocalization(value: Boolean)
    fun setChineseTagLocalization(value: Boolean)
    fun setChineseCharacterLocalization(value: Boolean)
    fun setChineseDescriptionLocalization(value: Boolean)
    fun checkUpdate()
    fun downloadAndInstallBundle()
    fun resetToBuiltIn()
    fun onSnackbarDismissed()
}
