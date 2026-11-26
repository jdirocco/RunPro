package com.runpro.core.recording.service

import com.runpro.core.recording.service.BatteryAdvice.ALLOW_UNRESTRICTED_BATTERY
import com.runpro.core.recording.service.BatteryAdvice.REMOVE_FROM_SLEEPING_APPS
import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryGuidanceTest {
    @Test
    fun `an optimised app is told to allow unrestricted battery`() {
        assertEquals(
            listOf(ALLOW_UNRESTRICTED_BATTERY),
            BatteryGuidance.advice(
                ignoringOptimizations = false,
                manufacturer = "Google",
                sleepingAppsChecked = false
            )
        )
    }

    @Test
    fun `an exempt app on a non Samsung watch needs nothing`() {
        assertEquals(
            emptyList(),
            BatteryGuidance.advice(
                ignoringOptimizations = true,
                manufacturer = "Google",
                sleepingAppsChecked = false
            )
        )
    }

    @Test
    fun `on Samsung the sleeping apps list is checked by hand until the runner confirms it`() {
        // One UI has no API for "Sleeping apps": the exemption from battery optimisation does not cover it.
        assertEquals(
            listOf(REMOVE_FROM_SLEEPING_APPS),
            BatteryGuidance.advice(
                ignoringOptimizations = true,
                manufacturer = "samsung",
                sleepingAppsChecked = false
            )
        )
        assertEquals(
            listOf(ALLOW_UNRESTRICTED_BATTERY, REMOVE_FROM_SLEEPING_APPS),
            BatteryGuidance.advice(
                ignoringOptimizations = false,
                manufacturer = "SAMSUNG",
                sleepingAppsChecked = false
            )
        )
        assertEquals(
            emptyList(),
            BatteryGuidance.advice(
                ignoringOptimizations = true,
                manufacturer = "samsung",
                sleepingAppsChecked = true
            )
        )
    }
}
