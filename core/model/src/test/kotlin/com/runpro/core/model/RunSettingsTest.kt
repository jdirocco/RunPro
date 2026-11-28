package com.runpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class RunSettingsTest {
    @Test
    fun `defaults preserve existing runs and settings round trip`() {
        val old = WorkoutJson.json.decodeFromString<WorkoutSettings>("{}")
        assertEquals(RunSettings(), old.runSettings)
        assertFalse(old.runSettings.autoPause)
        assertFalse(old.runSettings.debugTrace)
        val settings = RunSettings(DisplayUnits.IMPERIAL, UserProfile(190, 50, 72.5), true, 5, true)
        assertEquals(
            settings,
            WorkoutJson.json.decodeFromString<RunSettings>(
                WorkoutJson.json.encodeToString(settings)
            )
        )
    }

    @Test
    fun `profile rejects impossible values and inverted heart rates`() {
        assertFailsWith<IllegalArgumentException> { UserProfile(maxHeartRate = 301) }
        assertFailsWith<IllegalArgumentException> { UserProfile(restingHeartRate = 0) }
        assertFailsWith<IllegalArgumentException> { UserProfile(100, 100) }
        assertFailsWith<IllegalArgumentException> { UserProfile(weightKg = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { UserProfile(weightKg = -1.0) }
        assertFailsWith<IllegalArgumentException> { RunSettings(countdownSeconds = -1) }
    }
}
