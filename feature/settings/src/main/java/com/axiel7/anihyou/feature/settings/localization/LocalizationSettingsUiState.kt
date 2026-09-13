package com.axiel7.anihyou.feature.settings.localization

import androidx.compose.runtime.Immutable
import com.axiel7.anihyou.core.base.state.UiState
import com.axiel7.anihyou.core.network.localization.RemoteBundleUpdateInfo

@Immutable
data class LocalizationSettingsUiState(
    val isTitleEnabled: Boolean = true,
    val isTagEnabled: Boolean = true,
    val isCharacterEnabled: Boolean = true,
    val isDescriptionEnabled: Boolean = true,
    val currentVersion: String = "2026.09.08",
    val isOverlayActive: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val isDownloading: Boolean = false,
    val updateInfo: RemoteBundleUpdateInfo? = null,
    val snackbarMessage: String? = null,
    override val isLoading: Boolean = false,
    override val error: String? = null,
) : UiState() {
    override fun setLoading(value: Boolean) = copy(isLoading = value)
    override fun setError(value: String?) = copy(error = value)
}
