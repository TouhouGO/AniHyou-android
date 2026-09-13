package com.axiel7.anihyou.core.network.localization

import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

data class BundleStatus(
    val currentVersion: String,
    val isOverlayActive: Boolean
)

@Single
class LocalizationBundleService(
    private val bundleManager: LocalizationBundleManager,
    private val invalidationCoordinator: LocalizationInvalidationCoordinator
) {
    private val _bundleStatus = MutableStateFlow(
        BundleStatus(
            currentVersion = bundleManager.getCurrentVersion(),
            isOverlayActive = bundleManager.isOverlayActive()
        )
    )
    val bundleStatus: StateFlow<BundleStatus> = _bundleStatus.asStateFlow()

    init {
        bundleManager.registerReloadListener {
            _bundleStatus.value = BundleStatus(
                currentVersion = bundleManager.getCurrentVersion(),
                isOverlayActive = bundleManager.isOverlayActive()
            )
        }
    }

    suspend fun installBundleFromZip(
        zipInputStream: InputStream,
        expectedVersion: String? = null
    ): BundleInstallResult {
        val result = bundleManager.installBundleFromZip(zipInputStream, expectedVersion)
        if (result.isSuccess) {
            invalidationCoordinator.publishResourcesChanged(LocalizationChangeReason.BUNDLE_INSTALL)
        }
        return result
    }

    suspend fun resetToBuiltIn(): Boolean {
        val success = bundleManager.resetToBuiltIn()
        if (success) {
            invalidationCoordinator.publishResourcesChanged(LocalizationChangeReason.BUNDLE_RESET)
        }
        return success
    }

    fun getCurrentVersion(): String = bundleManager.getCurrentVersion()
    fun isOverlayActive(): Boolean = bundleManager.isOverlayActive()
}
