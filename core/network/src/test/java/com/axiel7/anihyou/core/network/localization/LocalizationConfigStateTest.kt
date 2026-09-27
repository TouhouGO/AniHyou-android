package com.axiel7.anihyou.core.network.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LocalizationConfigState].
 *
 * This state backs the refresh mechanism used by 18 ViewModels, so configVersion
 * monotonicity and the changed/not-changed contract have to hold exactly.
 */
class LocalizationConfigStateTest {

    private val allOff = LocalizationConfigValues(
        titleEnabled = false,
        tagEnabled = false,
        characterEnabled = false,
        descriptionEnabled = false
    )

    private val allOn = LocalizationConfigValues(
        titleEnabled = true,
        tagEnabled = true,
        characterEnabled = true,
        descriptionEnabled = true
    )

    // 1. initialize is idempotent: only the first call takes effect
    @Test
    fun initialize_isIdempotent() {
        val state = LocalizationConfigState()

        state.initialize(allOff)
        val afterFirst = state.snapshot.value

        // A second initialize with the opposite values must be ignored wholesale.
        state.initialize(allOn)
        val afterSecond = state.snapshot.value

        assertEquals(
            "second initialize must not flip the flags",
            afterFirst.isTitleEnabled,
            afterSecond.isTitleEnabled
        )
        assertEquals(afterFirst.isTagEnabled, afterSecond.isTagEnabled)
        assertEquals(afterFirst.isCharacterEnabled, afterSecond.isCharacterEnabled)
        assertEquals(afterFirst.isDescriptionEnabled, afterSecond.isDescriptionEnabled)
        assertEquals(
            "configVersion must not move on a redundant initialize",
            afterFirst.configVersion,
            afterSecond.configVersion
        )
        assertEquals(
            "configVersion must still be the initialized baseline",
            0L,
            afterSecond.configVersion
        )
    }

    // 2. initialize seeds the snapshot from the passed switches, version 0
    @Test
    fun initialize_seedsSnapshotFromConfigWithVersionZero() {
        val state = LocalizationConfigState()

        state.initialize(
            LocalizationConfigValues(
                titleEnabled = true,
                tagEnabled = false,
                characterEnabled = true,
                descriptionEnabled = false
            )
        )

        val snapshot = state.snapshot.value
        assertTrue(snapshot.isTitleEnabled)
        assertFalse(snapshot.isTagEnabled)
        assertTrue(snapshot.isCharacterEnabled)
        assertFalse(snapshot.isDescriptionEnabled)
        assertEquals("fresh state must start at version 0", 0L, snapshot.configVersion)
        assertEquals(
            "initialize reports CONFIG as the change reason",
            LocalizationChangeReason.CONFIG,
            snapshot.lastChangeReason
        )
    }

    // 3. publishing an identical config reports no change and leaves the version alone
    @Test
    fun publishConfig_returnsFalseAndKeepsVersionWhenUnchanged() {
        val state = LocalizationConfigState()
        state.initialize(allOn)

        val changed = state.publishConfig(allOn)

        assertFalse("an identical config must not count as a change", changed)
        assertEquals("version must not move on a no-op publish", 0L, state.snapshot.value.configVersion)
    }

    // 4. each individual switch flip is detected and bumps the version by exactly one
    @Test
    fun publishConfig_detectsEachSwitchIndividually() {
        val variants = listOf(
            "title" to LocalizationConfigValues(false, true, true, true),
            "tag" to LocalizationConfigValues(true, false, true, true),
            "character" to LocalizationConfigValues(true, true, false, true),
            "description" to LocalizationConfigValues(true, true, true, false)
        )

        for ((name, variant) in variants) {
            val state = LocalizationConfigState()
            state.initialize(allOn)

            val changed = state.publishConfig(variant)

            assertTrue("flipping $name must be reported as a change", changed)
            assertEquals(
                "flipping $name must bump the version to 1",
                1L,
                state.snapshot.value.configVersion
            )
            // And the new value must be what the snapshot now holds.
            assertEquals(
                "snapshot must reflect the new $name value",
                variant,
                LocalizationConfigValues(
                    titleEnabled = state.snapshot.value.isTitleEnabled,
                    tagEnabled = state.snapshot.value.isTagEnabled,
                    characterEnabled = state.snapshot.value.isCharacterEnabled,
                    descriptionEnabled = state.snapshot.value.isDescriptionEnabled
                )
            )
        }
    }

    // 5. successive real publishes produce a strictly increasing version sequence
    @Test
    fun publishConfig_versionStrictlyIncreasesAcrossSuccessiveChanges() {
        val state = LocalizationConfigState()
        state.initialize(allOn)

        val sequence = listOf(
            LocalizationConfigValues(false, true, true, true),
            LocalizationConfigValues(false, false, true, true),
            LocalizationConfigValues(false, false, false, true),
            LocalizationConfigValues(false, false, false, false)
        )

        var previous = state.snapshot.value.configVersion
        for ((index, config) in sequence.withIndex()) {
            assertTrue("change #${index + 1} must be reported", state.publishConfig(config))
            val current = state.snapshot.value.configVersion
            assertTrue(
                "version must strictly increase (was $previous, now $current)",
                current > previous
            )
            assertEquals(
                "version must advance by exactly one per change",
                previous + 1,
                current
            )
            previous = current
        }

        assertEquals("four changes must land on version 4", 4L, previous)
    }

    // 6. resource-change notifications bump the version and record the reason
    @Test
    fun publishResourcesChanged_withBundleInstallBumpsVersionAndReason() {
        val state = LocalizationConfigState()
        state.initialize(allOn)

        state.publishResourcesChanged(LocalizationChangeReason.BUNDLE_INSTALL)

        assertEquals(1L, state.snapshot.value.configVersion)
        assertEquals(
            LocalizationChangeReason.BUNDLE_INSTALL,
            state.snapshot.value.lastChangeReason
        )
    }

    // 7. the reset reason behaves the same way
    @Test
    fun publishResourcesChanged_withBundleResetBumpsVersionAndReason() {
        val state = LocalizationConfigState()
        state.initialize(allOn)

        state.publishResourcesChanged(LocalizationChangeReason.BUNDLE_RESET)

        assertEquals(1L, state.snapshot.value.configVersion)
        assertEquals(
            LocalizationChangeReason.BUNDLE_RESET,
            state.snapshot.value.lastChangeReason
        )
    }

    // 8. lastChangeReason tracks the source of the most recent change
    @Test
    fun lastChangeReason_tracksMostRecentChangeSource() {
        val state = LocalizationConfigState()
        state.initialize(allOn)

        // A config publish reports CONFIG, even straight after a resource change.
        state.publishResourcesChanged(LocalizationChangeReason.BUNDLE_INSTALL)
        assertEquals(
            LocalizationChangeReason.BUNDLE_INSTALL,
            state.snapshot.value.lastChangeReason
        )

        assertTrue(state.publishConfig(LocalizationConfigValues(false, true, true, true)))
        assertEquals(
            "a config change must overwrite the previous reason with CONFIG",
            LocalizationChangeReason.CONFIG,
            state.snapshot.value.lastChangeReason
        )

        state.publishResourcesChanged(LocalizationChangeReason.BUNDLE_RESET)
        assertEquals(
            "a later resource change must overwrite CONFIG",
            LocalizationChangeReason.BUNDLE_RESET,
            state.snapshot.value.lastChangeReason
        )
    }

    // 9. the snapshot is a StateFlow exposing the current value synchronously
    @Test
    fun snapshot_isReadableAsStateFlowValue() {
        val state = LocalizationConfigState()

        // Defaults before any initialize call.
        assertEquals(0L, state.snapshot.value.configVersion)
        assertTrue("defaults are all enabled", state.snapshot.value.isTitleEnabled)

        state.initialize(allOff)

        assertFalse(state.snapshot.value.isTitleEnabled)
        assertFalse(state.snapshot.value.isTagEnabled)
        assertFalse(state.snapshot.value.isCharacterEnabled)
        assertFalse(state.snapshot.value.isDescriptionEnabled)

        // A value captured earlier is a plain immutable data class, so it must not
        // mutate underneath the caller when the StateFlow moves on.
        val captured = state.snapshot.value
        state.publishResourcesChanged(LocalizationChangeReason.BUNDLE_INSTALL)
        assertEquals(0L, captured.configVersion)
        assertEquals("the live StateFlow value moved on", 1L, state.snapshot.value.configVersion)
    }

    // 10. publishConfig before initialize applies the values but reports no change
    @Test
    fun publishConfig_beforeInitializeAppliesValuesButReportsNoChange() {
        val state = LocalizationConfigState()

        val changed = state.publishConfig(allOff)

        assertFalse(
            "the first publish acts as initialization and must not report a change",
            changed
        )
        val snapshot = state.snapshot.value
        assertFalse("values must still be applied", snapshot.isTitleEnabled)
        assertFalse(snapshot.isTagEnabled)
        assertFalse(snapshot.isCharacterEnabled)
        assertFalse(snapshot.isDescriptionEnabled)
        assertEquals("version stays at the baseline", 0L, state.snapshot.value.configVersion)
        assertEquals(
            LocalizationChangeReason.CONFIG,
            state.snapshot.value.lastChangeReason
        )

        // And because that call consumed the initialization, a real change now bumps normally.
        state.publishResourcesChanged(LocalizationChangeReason.BUNDLE_RESET)
        assertEquals(1L, state.snapshot.value.configVersion)
    }
}
