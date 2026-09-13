package com.axiel7.anihyou.feature.settings.localization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.rememberSnackbarManager
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithLargeTopAppBar
import com.axiel7.anihyou.core.ui.composables.PlainPreference
import com.axiel7.anihyou.core.ui.composables.PreferencesTitle
import com.axiel7.anihyou.core.ui.composables.SwitchPreference
import com.axiel7.anihyou.core.ui.composables.bottomShape
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.core.ui.composables.middleShape
import com.axiel7.anihyou.core.ui.composables.topShape
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LocalizationSettingsView() {
    val viewModel: LocalizationSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LocalizationSettingsContent(
        uiState = uiState,
        event = viewModel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalizationSettingsContent(
    uiState: LocalizationSettingsUiState,
    event: LocalizationSettingsEvent?,
) {
    val navActionManager = LocalNavActionManager.current
    val snackbarManager = rememberSnackbarManager()
    val topAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarManager.showMessage(msg)
            event?.onSnackbarDismissed()
        }
    }

    DefaultScaffoldWithLargeTopAppBar(
        title = stringResource(R.string.localization_settings_title),
        snackbarHost = snackbarManager::SnackbarHost,
        navigationIcon = { BackIconButton(onClick = navActionManager::goBack) },
        scrollBehavior = topAppBarScrollBehavior
    ) { padding ->
        Column(
            modifier = Modifier
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(padding)
        ) {
            PreferencesTitle(text = stringResource(R.string.localization_features_title))

            SwitchPreference(
                title = stringResource(R.string.localization_title_title),
                subtitle = stringResource(R.string.localization_title_desc),
                preferenceValue = uiState.isTitleEnabled,
                icon = R.drawable.translate_24,
                onValueChange = { event?.setChineseTitleLocalization(it) },
                shape = topShape
            )

            SwitchPreference(
                title = stringResource(R.string.localization_tag_title),
                subtitle = stringResource(R.string.localization_tag_desc),
                preferenceValue = uiState.isTagEnabled,
                icon = R.drawable.bookmark_24,
                onValueChange = { event?.setChineseTagLocalization(it) },
                shape = middleShape
            )

            SwitchPreference(
                title = stringResource(R.string.localization_character_title),
                subtitle = stringResource(R.string.localization_character_desc),
                preferenceValue = uiState.isCharacterEnabled,
                icon = R.drawable.group_24,
                onValueChange = { event?.setChineseCharacterLocalization(it) },
                shape = middleShape
            )

            SwitchPreference(
                title = stringResource(R.string.localization_description_title),
                subtitle = stringResource(R.string.localization_description_desc),
                preferenceValue = uiState.isDescriptionEnabled,
                icon = R.drawable.info_24,
                onValueChange = { event?.setChineseDescriptionLocalization(it) },
                shape = bottomShape
            )

            PreferencesTitle(text = stringResource(R.string.localization_bundle_card_title))

            val bundleSubtitle = if (uiState.isOverlayActive) {
                "${stringResource(R.string.localization_bundle_overlay)} (v${uiState.currentVersion})"
            } else {
                "${stringResource(R.string.localization_bundle_builtin)} (v${uiState.currentVersion})"
            }

            PlainPreference(
                title = "简体中文 (zh-CN)",
                subtitle = bundleSubtitle,
                icon = R.drawable.language_24,
                onClick = {},
                shape = topShape
            )

            val updateButtonTitle = if (uiState.updateInfo?.hasUpdate == true) {
                stringResource(R.string.localization_download_bundle)
            } else {
                stringResource(R.string.localization_check_update)
            }

            val updateButtonSubtitle = when {
                uiState.isCheckingUpdate -> "正在连接服务器检查更新..."
                uiState.isDownloading -> "正在安全下载并解包安装..."
                uiState.updateInfo?.hasUpdate == true ->
                    "${stringResource(R.string.localization_update_available)}: v${uiState.updateInfo.remoteVersion}"
                else -> "在线比对远端字典版本并支持热更新"
            }

            PlainPreference(
                title = updateButtonTitle,
                subtitle = updateButtonSubtitle,
                icon = R.drawable.refresh_24,
                isLoading = uiState.isCheckingUpdate || uiState.isDownloading,
                onClick = {
                    if (uiState.updateInfo?.hasUpdate == true) {
                        event?.downloadAndInstallBundle()
                    } else {
                        event?.checkUpdate()
                    }
                },
                shape = if (uiState.isOverlayActive) middleShape else bottomShape
            )

            if (uiState.isOverlayActive) {
                PlainPreference(
                    title = stringResource(R.string.localization_reset_builtin),
                    subtitle = "清除外部扩展包缓存，还原至 APK 内置基础版本",
                    icon = R.drawable.delete_24,
                    onClick = { event?.resetToBuiltIn() },
                    shape = bottomShape
                )
            }

            Text(
                text = stringResource(R.string.localization_bundle_desc),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}
