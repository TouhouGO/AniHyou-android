package com.axiel7.anihyou.feature.settings

import android.content.Context
import android.net.Uri
import com.axiel7.anihyou.core.domain.repository.LocalizationCatalogRepository
import com.axiel7.anihyou.core.domain.repository.CatalogUpdateMonitor
import com.axiel7.anihyou.core.domain.repository.CatalogUpdater
import com.axiel7.anihyou.core.domain.repository.CatalogCheck
import com.axiel7.anihyou.core.domain.repository.readBounded
import com.axiel7.anihyou.core.localization.CatalogFailureCode
import com.axiel7.anihyou.core.localization.CatalogFailureException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.common.viewmodel.UiStateViewModel
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LoginRepository
import com.axiel7.anihyou.core.domain.repository.UserRepository
import com.axiel7.anihyou.core.model.AppColorMode
import com.axiel7.anihyou.core.model.DefaultTab
import com.axiel7.anihyou.core.model.ItemsPerRow
import com.axiel7.anihyou.core.model.ListStyle
import com.axiel7.anihyou.core.model.Theme
import com.axiel7.anihyou.core.model.TranslatorApp
import com.axiel7.anihyou.core.model.notification.NotificationInterval
import com.axiel7.anihyou.core.network.type.ScoreFormat
import com.axiel7.anihyou.core.network.type.UserStaffNameLanguage
import com.axiel7.anihyou.core.network.type.UserTitleLanguage
import com.axiel7.anihyou.feature.worker.NotificationWorker.Companion.cancelNotificationWork
import com.axiel7.anihyou.feature.worker.NotificationWorker.Companion.scheduleNotificationWork
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val listPreferencesRepository: ListPreferencesRepository,
    private val userRepository: UserRepository,
    private val loginRepository: LoginRepository,
    private val workManager: WorkManager,
    private val catalog: LocalizationCatalogRepository,
    private val catalogMonitor: CatalogUpdateMonitor,
    private val context: Context,
) : UiStateViewModel<SettingsUiState>(), SettingsEvent {

    override val initialState = SettingsUiState()


    private fun catalogErrorMessage(error: Throwable): String = context.getString(
        when ((error as? CatalogFailureException)?.code) {
            CatalogFailureCode.FILE_TOO_LARGE -> com.axiel7.anihyou.core.resources.R.string.catalog_too_large
            CatalogFailureCode.INVALID_UTF8 -> com.axiel7.anihyou.core.resources.R.string.catalog_invalid_utf8
            CatalogFailureCode.INVALID_FORMAT -> com.axiel7.anihyou.core.resources.R.string.catalog_invalid_format
            CatalogFailureCode.EMPTY_CATALOG -> com.axiel7.anihyou.core.resources.R.string.catalog_empty
            CatalogFailureCode.INVALID_VERSION -> com.axiel7.anihyou.core.resources.R.string.catalog_invalid_version
            CatalogFailureCode.DOWNGRADE -> com.axiel7.anihyou.core.resources.R.string.catalog_downgrade
            CatalogFailureCode.VERSION_COLLISION -> com.axiel7.anihyou.core.resources.R.string.catalog_collision
            CatalogFailureCode.METADATA_MISMATCH -> com.axiel7.anihyou.core.resources.R.string.catalog_metadata_mismatch
            CatalogFailureCode.STALE_OPERATION -> com.axiel7.anihyou.core.resources.R.string.catalog_stale
            CatalogFailureCode.STORAGE_FAILURE -> com.axiel7.anihyou.core.resources.R.string.catalog_storage_error
            CatalogFailureCode.IMPORT_FAILURE -> com.axiel7.anihyou.core.resources.R.string.catalog_import_error
            CatalogFailureCode.NETWORK_FAILURE -> com.axiel7.anihyou.core.resources.R.string.catalog_network_error
            CatalogFailureCode.INVALID_MANIFEST -> com.axiel7.anihyou.core.resources.R.string.catalog_invalid_manifest
            CatalogFailureCode.INTERNAL_ERROR, null -> com.axiel7.anihyou.core.resources.R.string.catalog_unknown_error
        }
    )

    private fun catalogAction(action: suspend () -> Unit) {
        if (mutableUiState.value.catalogBusy) return
        mutableUiState.update { it.copy(catalogBusy = true, catalogMessage = null) }
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableUiState.update { it.copy(catalogMessage = catalogErrorMessage(e)) } }
            finally { mutableUiState.update { it.copy(catalogBusy = false) } }
        }
    }
    override fun checkCatalog() = catalogAction {
        when (catalogMonitor.check(manual = true)) {
            CatalogCheck.Unavailable -> mutableUiState.update { it.copy(catalogMessage = context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_unavailable)) }
            CatalogCheck.UpToDate -> mutableUiState.update { it.copy(catalogMessage = context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_up_to_date)) }
            else -> Unit
        }
    }
    override fun downloadCatalog() = catalogAction {
        catalogMonitor.download()
        mutableUiState.update { it.copy(catalogMessage = context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_complete)) }
    }
    override fun setCatalogAutoCheck(enabled: Boolean) { viewModelScope.launch { catalogMonitor.setEnabled(enabled) } }
    override fun importCatalog(uri: Uri) = catalogAction {
        catalog.load()
        val token = catalog.captureGeneration()
        val bytes = withContext(Dispatchers.IO) {
            try { requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBounded() } }
            catch (e: CatalogFailureException) { throw e }
            catch (e: Exception) { throw CatalogFailureException(CatalogFailureCode.IMPORT_FAILURE, e) }
        }
        catalogMonitor.invalidate()
        catalog.install(bytes, token = token)
        catalogMonitor.invalidate()
        mutableUiState.update { it.copy(catalogOfferVersion = null, catalogMessage = context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_complete)) }
    }
    override fun resetCatalog() = catalogAction {
        catalogMonitor.invalidate()
        catalog.reset()
        catalogMonitor.invalidate()
        mutableUiState.update { it.copy(catalogOfferVersion = null, catalogMessage = context.getString(com.axiel7.anihyou.core.resources.R.string.catalog_complete)) }
    }


    private val profileColor = defaultPreferencesRepository.profileColor

    private val isLoggedIn = defaultPreferencesRepository.isLoggedIn

    override fun setTheme(value: Theme) {
        viewModelScope.launch {
            defaultPreferencesRepository.setTheme(value)
        }
    }

    override fun setUseBlackColors(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setUseBlackColors(value)
        }
    }

    override fun setAppColorMode(value: AppColorMode) {
        viewModelScope.launch {
            defaultPreferencesRepository.setAppColorMode(value)
            when (value) {
                AppColorMode.DEFAULT -> setAppColor(null)
                AppColorMode.PROFILE -> {
                    profileColor.firstOrNull()?.let { setAppColor(it) }
                }

                AppColorMode.CUSTOM -> {}
            }
        }
    }

    override fun setCustomAppColor(color: Color) {
        viewModelScope.launch {
            defaultPreferencesRepository.setAppColor(color)
        }
    }

    private fun setAppColor(value: Color?) = viewModelScope.launch {
        defaultPreferencesRepository.setAppColor(value)
    }

    override fun setColorPalette(value: PaletteStyle) {
        viewModelScope.launch {
            defaultPreferencesRepository.setColorPalette(value.toString())
        }
    }

    override fun setColoredMedia(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setColoredMedia(value)
        }
    }

    override fun setUseGeneralListStyle(value: Boolean) {
        viewModelScope.launch {
            listPreferencesRepository.setUseGeneralListStyle(value)
        }
    }

    override fun setGeneralListStyle(value: ListStyle) {
        viewModelScope.launch {
            listPreferencesRepository.setGeneralListStyle(value)
        }
    }

    override fun setGridItemsPerRow(value: ItemsPerRow) {
        viewModelScope.launch {
            listPreferencesRepository.setGridItemsPerRow(value)
        }
    }

    override fun setAiringOnMyList(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setAiringOnMyList(value)
        }
    }

    override fun setChineseDescriptions(value: Boolean) {
        viewModelScope.launch { defaultPreferencesRepository.setChineseDescriptions(value) }
    }

    override fun setChineseTitles(value: Boolean) {
        viewModelScope.launch { defaultPreferencesRepository.setChineseTitles(value) }
    }

    override fun setChinesePeople(value: Boolean) {
        viewModelScope.launch { defaultPreferencesRepository.setChinesePeople(value) }
    }

    override fun setChineseTags(value: Boolean) {
        viewModelScope.launch { defaultPreferencesRepository.setChineseTags(value) }
    }

    override fun setUseFuzzySearch(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setUseFuzzySearch(value)
        }
    }

    override fun setSeparateNovelsAndManga(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setSeparateNovelsAndManga(value)
        }
    }

    // Notifications
    @OptIn(ExperimentalPermissionsApi::class)
    override fun setNotificationsEnabled(
        isEnabled: Boolean,
        notificationPermission: PermissionState?,
        createNotificationChannels: () -> Unit,
    ) {
        viewModelScope.launch {
            if (isEnabled) {
                if (notificationPermission == null || notificationPermission.status.isGranted) {
                    defaultPreferencesRepository.setNotificationsEnabled(true)
                    createNotificationChannels()
                    scheduleNotificationWork(interval = uiState.value.notificationCheckInterval)
                } else {
                    notificationPermission.launchPermissionRequest()
                }
            } else {
                defaultPreferencesRepository.setNotificationsEnabled(false)
                workManager.cancelNotificationWork()
            }
        }
    }

    override fun setNotificationCheckInterval(value: NotificationInterval) {
        viewModelScope.launch {
            defaultPreferencesRepository.setNotificationCheckInterval(value)
            scheduleNotificationWork(value)
        }
    }

    private fun scheduleNotificationWork(interval: NotificationInterval) {
        workManager.scheduleNotificationWork(interval)
    }

    override fun setDisplayAdultContent(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setDisplayAdult(value)
            updateUser(displayAdultContent = value)
        }
    }

    override fun setBlurAdultContent(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setBlurAdult(value)
        }
    }

    override fun setShowLowPriority(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setShowLowPriority(value)
        }
    }

    override fun setHideScores(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setHideScores(value)
        }
    }

    override fun setTranslatorApp(value: TranslatorApp) {
        viewModelScope.launch {
            defaultPreferencesRepository.setTranslatorApp(value)
        }
    }

    override fun setTitleLanguage(value: UserTitleLanguage) {
        viewModelScope.launch {
            updateUser(titleLanguage = value)
        }
    }

    override fun setStaffNameLanguage(value: UserStaffNameLanguage) {
        viewModelScope.launch {
            updateUser(staffNameLanguage = value)
        }
    }

    override fun setScoreFormat(value: ScoreFormat) {
        viewModelScope.launch {
            defaultPreferencesRepository.setScoreFormat(value)
            defaultPreferencesRepository.setScoreSteps(1.0) // reset to 1 when selecting new score format
            updateUser(scoreFormat = value)
        }
    }

    override fun setScoreStep(value: Double) {
        viewModelScope.launch {
            defaultPreferencesRepository.setScoreSteps(value)
        }
    }

    override fun setDefaultTab(value: DefaultTab) {
        viewModelScope.launch {
            defaultPreferencesRepository.setDefaultTab(value)
        }
    }

    override fun setAiringNotification(value: Boolean) {
        viewModelScope.launch {
            updateUser(airingNotifications = value)
        }
    }

    override fun logOut(recreate: () -> Unit) {
        viewModelScope.launch {
            loginRepository.logOut()
            workManager.cancelNotificationWork()
            recreate()
        }
    }

    private suspend fun updateUser(
        displayAdultContent: Boolean? = null,
        titleLanguage: UserTitleLanguage? = null,
        staffNameLanguage: UserStaffNameLanguage? = null,
        scoreFormat: ScoreFormat? = null,
        airingNotifications: Boolean? = null,
    ) = userRepository
        .updateUser(
            displayAdultContent = displayAdultContent,
            titleLanguage = titleLanguage,
            staffNameLanguage = staffNameLanguage,
            scoreFormat = scoreFormat,
            airingNotifications = airingNotifications,
        )
        .let { result ->
            mutableUiState.update {
                if (result is DataResult.Success) {
                    it.copy(
                        isLoading = false,
                        userSettings = result.data
                    )
                } else {
                    result.toUiState()
                }
            }
        }

    init {
        catalogMonitor.state.onEach { state ->
            mutableUiState.update { it.copy(catalogAutoEnabled = state.enabled, catalogAutoLoaded = state.loaded, catalogLastAttempt = state.lastAttempt, catalogOfferVersion = state.offerVersion, catalogMessage = state.error?.let { code -> catalogErrorMessage(CatalogFailureException(code)) } ?: it.catalogMessage) }
        }.launchIn(viewModelScope)
        viewModelScope.launch { catalogMonitor.initializeForUi() }
        catalog.state.onEach { state ->
            mutableUiState.update { it.copy(catalogVersion = state.status.version, catalogRecordCount = state.status.recordCount, catalogInstalled = state.status.installed, catalogMessage = state.status.error?.let(::catalogErrorMessage) ?: it.catalogMessage) }
        }.launchIn(viewModelScope)
        viewModelScope.launch { catalog.load() }
        isLoggedIn
            .onEach { value ->
                mutableUiState.update { it.copy(isLoggedIn = value) }
            }
            .filter { it }
            .flatMapLatest {
                userRepository.getViewerSettings()
            }
            .onEach { result ->
                mutableUiState.update {
                    if (result is DataResult.Success) {
                        it.copy(
                            isLoading = false,
                            userSettings = result.data
                        )
                    } else {
                        result.toUiState()
                    }
                }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.theme
            .onEach { value ->
                mutableUiState.update { it.copy(theme = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.useBlackColors
            .onEach { value ->
                mutableUiState.update { it.copy(useBlackColors = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.appColorMode
            .onEach { value ->
                mutableUiState.update { it.copy(appColorMode = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.appColor
            .onEach { value ->
                mutableUiState.update { it.copy(appColor = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.colorPalette
            .mapNotNull { it?.let(PaletteStyle::parseOrNull) }
            .onEach { value ->
                mutableUiState.update { it.copy(colorPaletteStyle = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.coloredMedia
            .onEach { value ->
                mutableUiState.update { it.copy(coloredMedia = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.blurAdult
            .onEach { value ->
                mutableUiState.update { it.copy(blurAdultContent = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.showLowPriority
            .onEach { value ->
                mutableUiState.update { it.copy(showLowPriority = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.defaultTab
            .filterNotNull()
            .onEach { value ->
                mutableUiState.update { it.copy(defaultTab = value) }
            }
            .launchIn(viewModelScope)

        listPreferencesRepository.useGeneralListStyle
            .onEach { value ->
                mutableUiState.update { it.copy(useGeneralListStyle = value) }
            }
            .launchIn(viewModelScope)

        listPreferencesRepository.generalListStyle
            .onEach { value ->
                mutableUiState.update { it.copy(generalListStyle = value) }
            }
            .launchIn(viewModelScope)

        listPreferencesRepository.gridItemsPerRow
            .onEach { value ->
                mutableUiState.update { it.copy(gridItemsPerRow = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.airingOnMyList
            .onEach { value ->
                mutableUiState.update { it.copy(airingOnMyList = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.scoreFormat
            .onEach { value ->
                mutableUiState.update { it.copy(scoreFormat = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.scoreSteps
            .onEach { value ->
                mutableUiState.update { it.copy(scoreStep = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.isNotificationsEnabled
            .onEach { value ->
                mutableUiState.update { it.copy(isNotificationsEnabled = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.notificationCheckInterval
            .onEach { value ->
                mutableUiState.update { it.copy(notificationCheckInterval = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.translatorApp
            .onEach { value ->
                mutableUiState.update { it.copy(translatorApp = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.hideScores
            .onEach { value ->
                mutableUiState.update { it.copy(hideScores = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.chineseDescriptions
            .onEach { value -> mutableUiState.update { it.copy(chineseDescriptions = value) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.chineseTitles
            .onEach { value -> mutableUiState.update { it.copy(chineseTitles = value) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.chinesePeople
            .onEach { value -> mutableUiState.update { it.copy(chinesePeople = value) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.chineseTags
            .onEach { value -> mutableUiState.update { it.copy(chineseTags = value) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.useFuzzySearch
            .filterNotNull()
            .onEach { value ->
                mutableUiState.update { it.copy(useFuzzySearch = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.separateNovelsAndManga
            .onEach { value ->
                mutableUiState.update { it.copy(separateNovelsAndManga = value) }
            }
            .launchIn(viewModelScope)
    }
}
