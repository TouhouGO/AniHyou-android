package com.axiel7.anihyou

import android.app.Application
import android.os.Build.VERSION.SDK_INT
import androidx.core.performance.DefaultDevicePerformance
import androidx.core.performance.DevicePerformance
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.axiel7.anihyou.core.domain.dataStoreModule
import com.axiel7.anihyou.core.domain.repositoryModule
import com.axiel7.anihyou.core.network.apiModule
import com.axiel7.anihyou.core.network.networkModule
import com.axiel7.anihyou.feature.worker.workerModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import com.axiel7.anihyou.core.network.localization.LocalizationBundleManager
import org.koin.dsl.module

class App : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()

        val koinApp = startKoin {
            if (BuildConfig.DEBUG) {
                androidLogger()
            }
            androidContext(this@App)
            workManagerFactory()

            val coreModule = module {
                single<DevicePerformance> { DefaultDevicePerformance() }
            }

            modules(
                coreModule,
                dataStoreModule,
                networkModule,
                apiModule,
                repositoryModule,
                viewModelModule,
                workerModule,
            )
        }

        val bundleManager = koinApp.koin.get<LocalizationBundleManager>()
        bundleManager.setStorageDirectory(filesDir.resolve("localization"))

        val entityNameCache = koinApp.koin.get<com.axiel7.anihyou.core.network.localization.EntityNameCache>()
        val stateDir = filesDir.resolve("localization-state")
        val cacheFile = stateDir.resolve("entity_names_cache.json")
        val legacyCacheFile = filesDir.resolve("localization/entity_names_cache.json")
        entityNameCache.setStorageFile(cacheFile, legacyFile = legacyCacheFile)
    }

    override fun newImageLoader(context: PlatformContext) =
        ImageLoader.Builder(this)
            .components {
                if (SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.15)
                    .build()
            }
            .crossfade(true)
            .build()
}