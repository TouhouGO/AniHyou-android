package com.axiel7.anihyou.core.network.localization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koin.core.annotation.Single

enum class LocalizationChangeReason { CONFIG, BUNDLE_INSTALL, BUNDLE_RESET }

data class LocalizationConfigValues(
    val titleEnabled: Boolean,
    val tagEnabled: Boolean,
    val characterEnabled: Boolean,
    val descriptionEnabled: Boolean
)

data class LocalizationConfigSnapshot(
    val isTitleEnabled: Boolean = true,
    val isTagEnabled: Boolean = true,
    val isCharacterEnabled: Boolean = true,
    val isDescriptionEnabled: Boolean = true,
    val configVersion: Long = 0L,
    val lastChangeReason: LocalizationChangeReason = LocalizationChangeReason.CONFIG
)

@Single
class LocalizationConfigState {
    private val _snapshot = MutableStateFlow(LocalizationConfigSnapshot())
    val snapshot: StateFlow<LocalizationConfigSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var isInitialized = false

    fun initialize(config: LocalizationConfigValues) {
        if (!isInitialized) {
            _snapshot.update { current ->
                current.copy(
                    isTitleEnabled = config.titleEnabled,
                    isTagEnabled = config.tagEnabled,
                    isCharacterEnabled = config.characterEnabled,
                    isDescriptionEnabled = config.descriptionEnabled,
                    configVersion = 0L,
                    lastChangeReason = LocalizationChangeReason.CONFIG
                )
            }
            isInitialized = true
        }
    }

    fun publishConfig(config: LocalizationConfigValues): Boolean {
        var changed = false
        _snapshot.update { current ->
            if (!isInitialized) {
                isInitialized = true
                changed = false
                current.copy(
                    isTitleEnabled = config.titleEnabled,
                    isTagEnabled = config.tagEnabled,
                    isCharacterEnabled = config.characterEnabled,
                    isDescriptionEnabled = config.descriptionEnabled,
                    configVersion = 0L,
                    lastChangeReason = LocalizationChangeReason.CONFIG
                )
            } else if (current.isTitleEnabled == config.titleEnabled &&
                current.isTagEnabled == config.tagEnabled &&
                current.isCharacterEnabled == config.characterEnabled &&
                current.isDescriptionEnabled == config.descriptionEnabled
            ) {
                current
            } else {
                changed = true
                current.copy(
                    isTitleEnabled = config.titleEnabled,
                    isTagEnabled = config.tagEnabled,
                    isCharacterEnabled = config.characterEnabled,
                    isDescriptionEnabled = config.descriptionEnabled,
                    configVersion = current.configVersion + 1,
                    lastChangeReason = LocalizationChangeReason.CONFIG
                )
            }
        }
        return changed
    }

    fun publishResourcesChanged(reason: LocalizationChangeReason) {
        _snapshot.update { current ->
            current.copy(
                configVersion = current.configVersion + 1,
                lastChangeReason = reason
            )
        }
    }
}
