package com.axiel7.anihyou.ui.screens.main

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.core.net.toUri
import androidx.core.performance.DevicePerformance
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.ANIHYOU_AUTH_RESPONSE
import com.axiel7.anihyou.core.base.ANIHYOU_SCHEME
import com.axiel7.anihyou.core.base.ANIHYOU_WEAR_AUTH
import com.axiel7.anihyou.core.base.ANIHYOU_WEAR_CALLBACK_URL
import com.axiel7.anihyou.core.common.utils.ContextUtils.showToast
import com.axiel7.anihyou.core.common.utils.DeviceUtils
import com.axiel7.anihyou.core.domain.model.CustomLink
import com.axiel7.anihyou.core.domain.repository.CatalogUpdateMonitor
import com.axiel7.anihyou.core.domain.repository.CustomLinksRepository
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LocalizationCatalogRepository
import com.axiel7.anihyou.core.domain.repository.LoginRepository
import com.axiel7.anihyou.core.domain.repository.NotificationRepository
import com.axiel7.anihyou.core.model.DefaultTab
import com.axiel7.anihyou.core.network.NetworkVariables
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.ScoreFormat
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.startRemoteActivity
import com.axiel7.anihyou.widget.AiringWidget
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainViewModel(
    private val localizationCatalogRepository: LocalizationCatalogRepository,
    private val catalogMonitor: CatalogUpdateMonitor,
    private val networkVariables: NetworkVariables,
    private val loginRepository: LoginRepository,
    private val notificationRepository: NotificationRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val customLinksRepository: CustomLinksRepository,
    private val devicePerformance: DevicePerformance,
) : ViewModel(), MainEvent {

    val catalogMonitorState = catalogMonitor.state
    fun checkCatalogOnForeground() {
        viewModelScope.launch {
            try { catalogMonitor.check(manual = false) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The typed failure remains available in monitor state. */ }
        }
    }
    val titleCatalog = localizationCatalogRepository.snapshot
    val chineseTitles = defaultPreferencesRepository.chineseTitles
    val chinesePeople = defaultPreferencesRepository.chinesePeople
    val chineseTags = defaultPreferencesRepository.chineseTags

    init { viewModelScope.launch { localizationCatalogRepository.load() } }

    private var widgetRefreshStarted = false

    @OptIn(FlowPreview::class)
    fun observeWidgetTitles(context: Context) {
        if (widgetRefreshStarted) return
        widgetRefreshStarted = true
        val appContext = context.applicationContext
        viewModelScope.launch {
            combine(titleCatalog, chineseTitles) { snapshot, enabled -> snapshot to enabled }
                .distinctUntilChanged()
                .debounce(500)
                .collect {
                    try {
                        AiringWidget().updateAll(appContext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Widget title refresh failed", e)
                    }
                }
        }
    }

    val accessToken = defaultPreferencesRepository.accessToken

    val isLoggedIn = defaultPreferencesRepository.isLoggedIn

    val homeTab = defaultPreferencesRepository.defaultHomeTab

    val novelTab = defaultPreferencesRepository.defaultNovelTab

    val exploreTab = defaultPreferencesRepository.defaultExploreTab

    val theme = defaultPreferencesRepository.theme

    val useBlackColors = defaultPreferencesRepository.useBlackColors

    val appColor = defaultPreferencesRepository.appColor

    val appColorMode = defaultPreferencesRepository.appColorMode

    val paletteStyle = defaultPreferencesRepository.colorPalette.map { value ->
        value?.let { PaletteStyle.parseOrNull(it) } ?: PaletteStyle.TonalSpot
    }

    val blurAdultContent = defaultPreferencesRepository.blurAdult

    val scoreFormat = defaultPreferencesRepository.scoreFormat.map {
        it ?: ScoreFormat.POINT_10_DECIMAL
    }

    val hideScores = defaultPreferencesRepository.hideScores

    override fun saveLastTab(index: Int) {
        viewModelScope.launch {
            defaultPreferencesRepository.setLastTab(index)
        }
    }

    suspend fun getStartTab(): Int {
        val defaultTab = defaultPreferencesRepository.defaultTab.first()
        return if (defaultTab == null || defaultTab == DefaultTab.LAST_USED) {
            defaultPreferencesRepository.lastTab.first()
        } else {
            defaultTab.ordinal - 1
        }
    }

    fun setToken(token: String?) {
        networkVariables.accessToken = token
    }

    fun onIntentDataReceived(context: Context, data: Uri?) = viewModelScope.launch {
        if (data?.scheme == ANIHYOU_SCHEME) {
            when {
                data.toString().contains(ANIHYOU_AUTH_RESPONSE) -> loginRepository.parseRedirectUri(data)
                data.toString().contains(ANIHYOU_WEAR_AUTH) -> sendAuthTokenToWearable(context)
            }
        }
    }

    fun markNotificationsAsRead() = viewModelScope.launch {
        notificationRepository.resetNotificationCount()
    }

    private fun sendAuthTokenToWearable(context: Context) {
        viewModelScope.launch {
            val token = accessToken.first()
            if (token == null) {
                context.showToast(R.string.not_logged_text)
            } else {
                val data = "${ANIHYOU_WEAR_CALLBACK_URL}?access_token=$token".toUri()
                context.startRemoteActivity(data)
            }
        }
    }

    init {
        accessToken
            .onEach { setToken(it) }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            // initial fuzzy search setting based on device specs
            if (defaultPreferencesRepository.useFuzzySearch.first() == null) {
                defaultPreferencesRepository.setUseFuzzySearch(
                    value = DeviceUtils.isDevicePowerfulEnough(devicePerformance.mediaPerformanceClass)
                )
            }
        }

        // migrate legacy custom links to room db
        viewModelScope.launch {
            val animeLinks = defaultPreferencesRepository.animeCustomLinks.firstOrNull()
                ?.map { CustomLink.fromString(it, MediaType.ANIME) }
            if (!animeLinks.isNullOrEmpty()) {
                val rows = customLinksRepository.insertLinks(animeLinks)
                if (rows.size == animeLinks.size) {
                    defaultPreferencesRepository.setAnimeCustomLinks(emptySet())
                }
            }

            val mangaLinks = defaultPreferencesRepository.mangaCustomLinks.firstOrNull()
                ?.map { CustomLink.fromString(it, MediaType.MANGA) }
            if (!mangaLinks.isNullOrEmpty()) {
                val rows = customLinksRepository.insertLinks(mangaLinks)
                if (rows.size == mangaLinks.size) {
                    defaultPreferencesRepository.setMangaCustomLinks(emptySet())
                }
            }
        }
    }
}