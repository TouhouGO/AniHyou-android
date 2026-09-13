package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.cache.ApolloCacheManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

@Single
class LocalizationInvalidationCoordinator(
    private val apolloCacheManager: ApolloCacheManager,
    private val configState: LocalizationConfigState,
    private val titleProvider: ChineseTitleProvider,
    private val tagProvider: ChineseTagProvider,
    private val characterProvider: ChineseCharacterProvider,
    private val descriptionProvider: ChineseDescriptionProvider
) {
    private val mutex = Mutex()

    fun initialize(config: LocalizationConfigValues) {
        titleProvider.isEnabled = config.titleEnabled
        tagProvider.isEnabled = config.tagEnabled
        characterProvider.isEnabled = config.characterEnabled
        descriptionProvider.isEnabled = config.descriptionEnabled
        configState.initialize(config)
    }

    suspend fun publishConfig(config: LocalizationConfigValues): Boolean = mutex.withLock {
        // 1. Update provider flags immediately
        titleProvider.isEnabled = config.titleEnabled
        tagProvider.isEnabled = config.tagEnabled
        characterProvider.isEnabled = config.characterEnabled
        descriptionProvider.isEnabled = config.descriptionEnabled

        // 2. Check if this is an actual change
        val current = configState.snapshot.value
        val isDifferent = current.isTitleEnabled != config.titleEnabled ||
            current.isTagEnabled != config.tagEnabled ||
            current.isCharacterEnabled != config.characterEnabled ||
            current.isDescriptionEnabled != config.descriptionEnabled

        if (isDifferent) {
            // Clear Apollo cache once before publishing new generation
            apolloCacheManager.clearCache()
        }

        configState.publishConfig(config)
    }

    suspend fun publishResourcesChanged(reason: LocalizationChangeReason) = mutex.withLock {
        // Clear Apollo cache once before publishing new generation
        apolloCacheManager.clearCache()
        configState.publishResourcesChanged(reason)
    }
}
