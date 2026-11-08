package com.axiel7.anihyou.wear

import android.app.Application
import com.axiel7.anihyou.core.domain.dataStoreModule
import com.axiel7.anihyou.core.domain.databaseModule
import com.axiel7.anihyou.core.domain.repositoryModule
import com.axiel7.anihyou.core.network.apiModule
import com.axiel7.anihyou.core.network.networkModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            if (BuildConfig.DEBUG) {
                androidLogger()
            }
            androidContext(this@App)
            val coreModule = module { single(named("appVersion")) { BuildConfig.VERSION_NAME } }
            modules(
                coreModule,
                dataStoreModule,
                databaseModule,
                networkModule,
                apiModule,
                repositoryModule,
                viewModelModule,
            )
        }
    }
}