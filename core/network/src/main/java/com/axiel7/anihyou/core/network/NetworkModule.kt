package com.axiel7.anihyou.core.network

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.axiel7.anihyou.core.base.ANILIST_GRAPHQL_URL
import com.axiel7.anihyou.core.base.MAL_CLIENT_ID
import com.axiel7.anihyou.core.base.X_MAL_CLIENT_ID
import com.axiel7.anihyou.core.network.cache.Cache.cache
import com.axiel7.anihyou.core.network.localization.BundleUpdateManager
import com.axiel7.anihyou.core.network.localization.ChineseCharacterProvider
import com.axiel7.anihyou.core.network.localization.ChineseConverter
import com.axiel7.anihyou.core.network.localization.ChineseDescriptionProvider
import com.axiel7.anihyou.core.network.localization.ChineseTagProvider
import com.axiel7.anihyou.core.network.localization.ChineseTitleInterceptor
import com.axiel7.anihyou.core.network.localization.ChineseTitleProvider
import com.axiel7.anihyou.core.network.localization.LocalizationBundleManager
import okhttp3.Interceptor
import com.apollographql.cache.normalized.apolloStore
import com.axiel7.anihyou.core.network.cache.ApolloCacheManager
import com.axiel7.anihyou.core.network.localization.LocalizationConfigState
import com.axiel7.anihyou.core.network.localization.LocalizationInvalidationCoordinator
import com.axiel7.anihyou.core.network.localization.LocalizationBundleService
import com.axiel7.anihyou.core.network.localization.EntityNameCache
import com.axiel7.anihyou.core.network.localization.WikidataEntityNameSource
import com.axiel7.anihyou.core.network.localization.ChineseEntityNameResolver
import okhttp3.OkHttpClient
import okhttp3.Response
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

val networkModule = module {
    single { NetworkVariables() }
    single { LocalizationConfigState() }
    single { LocalizationBundleManager() }
    single { LocalizationInvalidationCoordinator(get(), get(), get(), get(), get(), get()) }
    single { LocalizationBundleService(get(), get()) }
    single { BundleUpdateManager(get(), get()) }
    single { EntityNameCache() }
    single { WikidataEntityNameSource(get(), get()) }
    single { ChineseConverter(get()) }
    single { ChineseEntityNameResolver(get(), get(), get(), get(), get()) }
    single { ChineseTagProvider(get()) }
    single { ChineseCharacterProvider(get(), get(), entityNameResolver = get()) }
    single { ChineseTitleProvider(get()) }
    single { ChineseDescriptionProvider(get(), get()) }
    single { ChineseTitleInterceptor(get(), get(), get(), get(), get()) }
    single { provideAuthorizationInterceptor(get()) }
    single { provideApolloClient(get(), get()) }
    single<ApolloCacheManager> {
        val client: ApolloClient = get()
        object : ApolloCacheManager {
            override suspend fun clearCache() {
                client.apolloStore.clearAll()
            }
        }
    }
    single { provideOkHttpClient() }
}

private fun provideApolloClient(
    authorizationInterceptor: AuthorizationInterceptor,
    chineseTitleInterceptor: ChineseTitleInterceptor,
): ApolloClient {
    val cacheFactory = MemoryCacheFactory(maxSizeBytes = 10 * 1024 * 1024)

    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authorizationInterceptor)
        .addInterceptor(AniListRateLimitInterceptor())
        .addInterceptor(chineseTitleInterceptor)
        .build()

    return ApolloClient.Builder()
        .serverUrl(ANILIST_GRAPHQL_URL)
        .okHttpClient(okHttpClient)
        .cache(
            cacheFactory,
            keyScope = CacheKey.Scope.SERVICE
        )
        .httpExposeErrorBody(true)
        .build()
}

class AuthorizationInterceptor(
    private val networkVariables: NetworkVariables,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .apply {
                networkVariables.accessToken?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()
        return chain.proceed(request)
    }
}

fun provideAuthorizationInterceptor(
    networkVariables: NetworkVariables
): AuthorizationInterceptor {
    return AuthorizationInterceptor(networkVariables)
}

fun provideOkHttpClient(): OkHttpClient {
    return OkHttpClient()
        .newBuilder()
        .addInterceptor {
            it.proceed(
                it.request().newBuilder()
                    .addHeader(X_MAL_CLIENT_ID, MAL_CLIENT_ID)
                    .build()
            )
        }
        .build()
}
