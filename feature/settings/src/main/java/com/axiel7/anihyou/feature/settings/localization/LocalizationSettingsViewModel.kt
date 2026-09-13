package com.axiel7.anihyou.feature.settings.localization

import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.common.viewmodel.UiStateViewModel
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.network.localization.BundleUpdateManager
import com.axiel7.anihyou.core.network.localization.LocalizationBundleManager
import com.axiel7.anihyou.core.network.localization.LocalizationBundleService
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LocalizationSettingsViewModel(
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val bundleManager: LocalizationBundleManager,
    private val updateManager: BundleUpdateManager,
    private val bundleService: LocalizationBundleService? = null,
) : UiStateViewModel<LocalizationSettingsUiState>(), LocalizationSettingsEvent {

    override val initialState = LocalizationSettingsUiState(
        currentVersion = bundleService?.getCurrentVersion() ?: bundleManager.getCurrentVersion(),
        isOverlayActive = bundleService?.isOverlayActive() ?: bundleManager.isOverlayActive()
    )

    private val defaultManifestUrl = BundleUpdateManager.DEFAULT_REMOTE_MANIFEST_URL

    init {
        defaultPreferencesRepository.chineseTitleLocalization
            .onEach { value -> mutableUiState.update { it.copy(isTitleEnabled = value) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.chineseTagLocalization
            .onEach { value -> mutableUiState.update { it.copy(isTagEnabled = value) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.chineseCharacterLocalization
            .onEach { value -> mutableUiState.update { it.copy(isCharacterEnabled = value) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.chineseDescriptionLocalization
            .onEach { value -> mutableUiState.update { it.copy(isDescriptionEnabled = value) } }
            .launchIn(viewModelScope)

        bundleService?.bundleStatus?.onEach { status ->
            mutableUiState.update {
                it.copy(
                    currentVersion = status.currentVersion,
                    isOverlayActive = status.isOverlayActive
                )
            }
        }?.launchIn(viewModelScope)
    }

    private fun refreshBundleStatus() {
        mutableUiState.update {
            it.copy(
                currentVersion = bundleService?.getCurrentVersion() ?: bundleManager.getCurrentVersion(),
                isOverlayActive = bundleService?.isOverlayActive() ?: bundleManager.isOverlayActive()
            )
        }
    }

    override fun setChineseTitleLocalization(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setChineseTitleLocalization(value)
        }
    }

    override fun setChineseTagLocalization(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setChineseTagLocalization(value)
        }
    }

    override fun setChineseCharacterLocalization(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setChineseCharacterLocalization(value)
        }
    }

    override fun setChineseDescriptionLocalization(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setChineseDescriptionLocalization(value)
        }
    }

    override fun checkUpdate() {
        if (uiState.value.isCheckingUpdate || uiState.value.isDownloading) return
        viewModelScope.launch {
            mutableUiState.update { it.copy(isCheckingUpdate = true) }
            when (val checkResult = updateManager.checkUpdate(defaultManifestUrl)) {
                com.axiel7.anihyou.core.network.localization.BundleUpdateCheckResult.RemoteUnavailable -> {
                    mutableUiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateInfo = null,
                            snackbarMessage = "暂无可用的在线语言包，继续使用内置版本"
                        )
                    }
                }
                is com.axiel7.anihyou.core.network.localization.BundleUpdateCheckResult.ManifestInvalid -> {
                    mutableUiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateInfo = null,
                            snackbarMessage = "在线语言包配置无效: ${checkResult.message}"
                        )
                    }
                }
                is com.axiel7.anihyou.core.network.localization.BundleUpdateCheckResult.NetworkUnavailable -> {
                    mutableUiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateInfo = null,
                            snackbarMessage = checkResult.message
                        )
                    }
                }
                is com.axiel7.anihyou.core.network.localization.BundleUpdateCheckResult.UpdateAvailable -> {
                    val info = checkResult.info
                    mutableUiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateInfo = info,
                            snackbarMessage = "发现新版本：v${info.remoteVersion}"
                        )
                    }
                }
                is com.axiel7.anihyou.core.network.localization.BundleUpdateCheckResult.NoUpdate -> {
                    val info = checkResult.info
                    mutableUiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateInfo = info,
                            snackbarMessage = "已是最新版本 (v${info.currentVersion})"
                        )
                    }
                }
            }
        }
    }

    override fun downloadAndInstallBundle() {
        val info = uiState.value.updateInfo
        if (uiState.value.isDownloading) return
        if (info == null) {
            mutableUiState.update { it.copy(snackbarMessage = "请先检查更新") }
            return
        }

        viewModelScope.launch {
            mutableUiState.update { it.copy(isDownloading = true) }
            val result = updateManager.downloadAndInstall(info)
            val message = if (result.isSuccess) {
                "已成功安装语言包 v${result.installedVersion ?: info.remoteVersion}"
            } else {
                result.message
            }
            mutableUiState.update {
                it.copy(
                    isDownloading = false,
                    snackbarMessage = message
                )
            }
            if (result.isSuccess) {
                refreshBundleStatus()
            }
        }
    }

    override fun resetToBuiltIn() {
        viewModelScope.launch {
            val success = bundleService?.resetToBuiltIn() ?: bundleManager.resetToBuiltIn()
            refreshBundleStatus()
            mutableUiState.update {
                it.copy(
                    snackbarMessage = if (success) "已恢复为内置基础版" else "重置失败"
                )
            }
        }
    }

    override fun onSnackbarDismissed() {
        mutableUiState.update { it.copy(snackbarMessage = null) }
    }
}
