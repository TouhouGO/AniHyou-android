package com.axiel7.anihyou.feature.settings.localization

import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.network.localization.BundleUpdateCheckResult
import com.axiel7.anihyou.core.network.localization.BundleUpdateManager
import com.axiel7.anihyou.core.network.localization.LocalizationBundleManager
import com.axiel7.anihyou.core.network.localization.RemoteBundleUpdateInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

import com.axiel7.anihyou.core.network.localization.BundleInstallResult

@OptIn(ExperimentalCoroutinesApi::class)
class LocalizationUpdateCheckTest {

    private val preferencesRepository: DefaultPreferencesRepository = mockk(relaxed = true)
    private val bundleManager: LocalizationBundleManager = mockk(relaxed = true)
    private val updateManager: BundleUpdateManager = mockk()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { preferencesRepository.chineseTitleLocalization } returns flowOf(true)
        every { preferencesRepository.chineseTagLocalization } returns flowOf(true)
        every { preferencesRepository.chineseCharacterLocalization } returns flowOf(true)
        every { preferencesRepository.chineseDescriptionLocalization } returns flowOf(true)
        every { bundleManager.getCurrentVersion() } returns "2026.09.08"
        every { bundleManager.isOverlayActive() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testNetworkUnavailableShowsErrorMessage() = runTest {
        coEvery { updateManager.checkUpdate(any()) } returns BundleUpdateCheckResult.NetworkUnavailable("Network timeout")

        val viewModel = LocalizationSettingsViewModel(preferencesRepository, bundleManager, updateManager)
        viewModel.checkUpdate()

        val state = viewModel.uiState.value
        assertFalse(state.isCheckingUpdate)
        assertNull(state.updateInfo)
        assertEquals("Network timeout", state.snackbarMessage)
    }

    @Test
    fun testManifestInvalidShowsConfigErrorMessage() = runTest {
        coEvery { updateManager.checkUpdate(any()) } returns BundleUpdateCheckResult.ManifestInvalid("Syntax error")

        val viewModel = LocalizationSettingsViewModel(preferencesRepository, bundleManager, updateManager)
        viewModel.checkUpdate()

        val state = viewModel.uiState.value
        assertFalse(state.isCheckingUpdate)
        assertNull(state.updateInfo)
        assertEquals("在线语言包配置无效: Syntax error", state.snackbarMessage)
    }

    @Test
    fun testUnavailableRemoteBundleShowsFriendlyChineseStatus() = runTest {
        coEvery { updateManager.checkUpdate(any()) } returns BundleUpdateCheckResult.RemoteUnavailable

        val viewModel = LocalizationSettingsViewModel(preferencesRepository, bundleManager, updateManager)
        viewModel.checkUpdate()

        val state = viewModel.uiState.value
        assertFalse(state.isCheckingUpdate)
        assertNull(state.updateInfo)
        assertEquals("暂无可用的在线语言包，继续使用内置版本", state.snackbarMessage)
    }

    @Test
    fun testCheckUpdateSuccessHasUpdate() = runTest {
        val updateInfo = RemoteBundleUpdateInfo(
            hasUpdate = true,
            currentVersion = "2026.09.08",
            remoteVersion = "2026.09.09",
            downloadUrl = "https://github.com/TouhouGO/AniHyou-android/releases/download/v2026.09.09/localization_bundle.zip",
            archiveSize = 1000L,
            archiveSha256 = "a".repeat(64),
            description = "New update"
        )
        coEvery { updateManager.checkUpdate(any()) } returns BundleUpdateCheckResult.UpdateAvailable(updateInfo)

        val viewModel = LocalizationSettingsViewModel(preferencesRepository, bundleManager, updateManager)
        viewModel.checkUpdate()

        val state = viewModel.uiState.value
        assertFalse(state.isCheckingUpdate)
        assertEquals(updateInfo, state.updateInfo)
        assertEquals("发现新版本：v2026.09.09", state.snackbarMessage)
    }

    @Test
    fun testCheckUpdateSuccessAlreadyLatest() = runTest {
        val updateInfo = RemoteBundleUpdateInfo(
            hasUpdate = false,
            currentVersion = "2026.09.08",
            remoteVersion = "2026.09.08",
            downloadUrl = "https://github.com/TouhouGO/AniHyou-android/releases/download/v2026.09.08/localization_bundle.zip",
            archiveSize = 1000L,
            archiveSha256 = "a".repeat(64),
            description = "Current version"
        )
        coEvery { updateManager.checkUpdate(any()) } returns BundleUpdateCheckResult.NoUpdate(updateInfo)

        val viewModel = LocalizationSettingsViewModel(preferencesRepository, bundleManager, updateManager)
        viewModel.checkUpdate()

        val state = viewModel.uiState.value
        assertFalse(state.isCheckingUpdate)
        assertEquals(updateInfo, state.updateInfo)
        assertEquals("已是最新版本 (v2026.09.08)", state.snackbarMessage)
    }

    @Test
    fun testDownloadAndInstallSuccessShowsChineseMessage() = runTest {
        val updateInfo = RemoteBundleUpdateInfo(
            hasUpdate = true,
            currentVersion = "2026.09.08",
            remoteVersion = "2026.09.09",
            downloadUrl = "https://github.com/TouhouGO/AniHyou-android/releases/download/v2026.09.09/localization_bundle.zip",
            archiveSize = 1000L,
            archiveSha256 = "a".repeat(64),
            description = "New update"
        )
        coEvery { updateManager.checkUpdate(any()) } returns BundleUpdateCheckResult.UpdateAvailable(updateInfo)
        coEvery { updateManager.downloadAndInstall(updateInfo) } returns BundleInstallResult(
            isSuccess = true,
            message = "已成功安装语言包 v2026.09.09",
            installedVersion = "2026.09.09"
        )

        val viewModel = LocalizationSettingsViewModel(preferencesRepository, bundleManager, updateManager)
        viewModel.checkUpdate()
        viewModel.downloadAndInstallBundle()

        val state = viewModel.uiState.value
        assertFalse(state.isDownloading)
        assertEquals("已成功安装语言包 v2026.09.09", state.snackbarMessage)
    }
}
