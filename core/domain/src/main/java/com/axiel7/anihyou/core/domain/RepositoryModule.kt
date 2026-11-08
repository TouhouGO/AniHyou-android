package com.axiel7.anihyou.core.domain

import com.axiel7.anihyou.core.domain.localization.ChineseDescriptionRepository
import com.axiel7.anihyou.core.domain.localization.ChineseSearchRepository
import com.axiel7.anihyou.core.domain.repository.AtomicCatalogStorage
import com.axiel7.anihyou.core.domain.repository.CatalogUpdater
import com.axiel7.anihyou.core.domain.repository.CatalogUpdateMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import com.axiel7.anihyou.core.domain.repository.ActivityRepository
import com.axiel7.anihyou.core.domain.repository.AnimeNotificationsRepository
import com.axiel7.anihyou.core.domain.repository.CharacterRepository
import com.axiel7.anihyou.core.domain.repository.CustomLinksRepository
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.FavoriteRepository
import com.axiel7.anihyou.core.domain.repository.LikeRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.LocalizationCatalogRepository
import com.axiel7.anihyou.core.domain.repository.LoginRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.domain.repository.NotificationRepository
import com.axiel7.anihyou.core.domain.repository.ReviewRepository
import com.axiel7.anihyou.core.domain.repository.SavedFiltersRepository
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.domain.repository.StaffRepository
import com.axiel7.anihyou.core.domain.repository.StudioRepository
import com.axiel7.anihyou.core.domain.repository.ThreadRepository
import com.axiel7.anihyou.core.domain.repository.UserRepository
import org.koin.dsl.onClose
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

val repositoryModule = module {
    single { ChineseDescriptionRepository(get()) }
    single { ChineseSearchRepository(get()) }
    single { LocalizationCatalogRepository({
        requireNotNull(LocalizationCatalogRepository::class.java.classLoader?.getResourceAsStream("localization/catalog.json")) { "Bundled localization catalog missing" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }, storage = AtomicCatalogStorage(java.io.File(androidContext().filesDir, "localization"))) }
    single { CatalogUpdater(get(), get()) }
    single { CatalogUpdateMonitor(get(), get(), get<DefaultPreferencesRepository>(), CoroutineScope(SupervisorJob() + Dispatchers.IO)) } onClose { it?.close() }
    single<ActivityRepository>()
    single<CharacterRepository>()
    single<DefaultPreferencesRepository>()
    single<FavoriteRepository>()
    single<LikeRepository>()
    single<ListPreferencesRepository>()
    single<LoginRepository>()
    single<MediaListRepository>()
    single<MediaRepository>()
    single<NotificationRepository>()
    single<ReviewRepository>()
    single<SearchRepository>()
    single<StaffRepository>()
    single<StudioRepository>()
    single<ThreadRepository>()
    single<UserRepository>()
    single<AnimeNotificationsRepository>()
    single<CustomLinksRepository>()
    single<SavedFiltersRepository>()
}