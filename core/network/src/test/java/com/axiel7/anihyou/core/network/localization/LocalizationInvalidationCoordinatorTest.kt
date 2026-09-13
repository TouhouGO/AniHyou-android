package com.axiel7.anihyou.core.network.localization

import com.axiel7.anihyou.core.network.cache.ApolloCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalizationInvalidationCoordinatorTest {

    private lateinit var fakeCacheManager: FakeApolloCacheManager
    private lateinit var configState: LocalizationConfigState
    private lateinit var titleProvider: ChineseTitleProvider
    private lateinit var tagProvider: ChineseTagProvider
    private lateinit var characterProvider: ChineseCharacterProvider
    private lateinit var descriptionProvider: ChineseDescriptionProvider
    private lateinit var coordinator: LocalizationInvalidationCoordinator

    class FakeApolloCacheManager : ApolloCacheManager {
        var clearCount = 0
        override suspend fun clearCache() {
            clearCount++
        }
    }

    @Before
    fun setUp() {
        fakeCacheManager = FakeApolloCacheManager()
        configState = LocalizationConfigState()
        val bundleManager = LocalizationBundleManager()
        val converter = ChineseConverter(bundleManager)
        titleProvider = ChineseTitleProvider(bundleManager)
        tagProvider = ChineseTagProvider(bundleManager)
        characterProvider = ChineseCharacterProvider(bundleManager, converter)
        descriptionProvider = ChineseDescriptionProvider(converter)

        coordinator = LocalizationInvalidationCoordinator(
            apolloCacheManager = fakeCacheManager,
            configState = configState,
            titleProvider = titleProvider,
            tagProvider = tagProvider,
            characterProvider = characterProvider,
            descriptionProvider = descriptionProvider
        )
    }

    @Test
    fun testInitializationDoesNotClearCacheOrIncrementGeneration() {
        val initial = LocalizationConfigValues(
            titleEnabled = true,
            tagEnabled = false,
            characterEnabled = true,
            descriptionEnabled = false
        )

        coordinator.initialize(initial)

        assertEquals(0, fakeCacheManager.clearCount)
        assertEquals(0L, configState.snapshot.value.configVersion)
        assertTrue(titleProvider.isEnabled)
        assertFalse(tagProvider.isEnabled)
        assertTrue(characterProvider.isEnabled)
        assertFalse(descriptionProvider.isEnabled)
    }

    @Test
    fun testIdenticalConfigIgnoredWithoutCacheClearOrGenerationBump() = runBlocking {
        val initial = LocalizationConfigValues(
            titleEnabled = true,
            tagEnabled = true,
            characterEnabled = true,
            descriptionEnabled = true
        )
        coordinator.initialize(initial)

        val changed = coordinator.publishConfig(initial)

        assertFalse(changed)
        assertEquals(0, fakeCacheManager.clearCount)
        assertEquals(0L, configState.snapshot.value.configVersion)
    }

    @Test
    fun testRealConfigChangeClearsCacheOnceAndIncrementsGenerationOnce() = runBlocking {
        val initial = LocalizationConfigValues(
            titleEnabled = true,
            tagEnabled = true,
            characterEnabled = true,
            descriptionEnabled = true
        )
        coordinator.initialize(initial)

        val changed = coordinator.publishConfig(
            initial.copy(titleEnabled = false)
        )

        assertTrue(changed)
        assertEquals(1, fakeCacheManager.clearCount)
        assertEquals(1L, configState.snapshot.value.configVersion)
        assertFalse(titleProvider.isEnabled)
    }

    @Test
    fun testPublishResourcesChangedClearsCacheAndBumpsVersion() = runBlocking {
        coordinator.publishResourcesChanged(LocalizationChangeReason.BUNDLE_INSTALL)

        assertEquals(1, fakeCacheManager.clearCount)
        assertEquals(1L, configState.snapshot.value.configVersion)
        assertEquals(LocalizationChangeReason.BUNDLE_INSTALL, configState.snapshot.value.lastChangeReason)
    }

    @Test
    fun testRapidSequentialChangesSerializedByMutex() = runBlocking {
        val initial = LocalizationConfigValues(
            titleEnabled = false,
            tagEnabled = false,
            characterEnabled = false,
            descriptionEnabled = false
        )
        coordinator.initialize(initial)

        val jobs = (1..20).map { i ->
            async(Dispatchers.Default) {
                coordinator.publishConfig(
                    LocalizationConfigValues(
                        titleEnabled = (i % 2 == 0),
                        tagEnabled = true,
                        characterEnabled = true,
                        descriptionEnabled = true
                    )
                )
            }
        }
        jobs.awaitAll()

        assertTrue(configState.snapshot.value.configVersion > 0)
        assertEquals(fakeCacheManager.clearCount.toLong(), configState.snapshot.value.configVersion)
    }
}
