package com.runpro.wear

import com.runpro.core.model.Cadence
import com.runpro.core.model.DisplayUnits
import com.runpro.core.model.Distance
import com.runpro.core.model.Elevation
import com.runpro.core.model.HeartRate
import com.runpro.core.model.Lap
import com.runpro.core.model.Power
import com.runpro.core.model.RunSettings
import com.runpro.core.model.Speed
import com.runpro.core.model.Timestamp
import com.runpro.core.model.UserProfile
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.CurrentValues
import com.runpro.core.recording.LapTotals
import com.runpro.core.recording.ResourceState
import com.runpro.core.recording.RunState
import com.runpro.core.recording.Totals
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class DataFieldTest {
    private val state = RunState(
        totals = Totals(timer = 1500.seconds, distance = Distance(5000.0)),
        laps = listOf(
            LapTotals(
                Lap(0, Timestamp(0, 0)),
                Totals(timer = 120.seconds, distance = Distance(500.0))
            )
        ),
        current = CurrentValues(
            HeartRate(150),
            Cadence(174.0),
            Speed(1000.0 / 300),
            Elevation(100.0),
            Power(240)
        ),
        resources = ResourceState(batteryPercent = 81),
        workoutSettings = WorkoutSettings(runSettings = RunSettings(profile = UserProfile(200)))
    )

    @Test
    fun `catalogue formats current run and lap values independently`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val expected = listOf(
                "25:00", "2:00", "5.00", "5:00", "5:00", "4:00", "150", "3", "174", "240",
                "100", "13:04", "81%"
            )
            val now = Instant.parse("2026-11-28T12:04:00Z")
            assertEquals(
                expected,
                DataField.entries.map { fieldValue(it, state, now, ZoneId.of("Europe/Rome")) }
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `missing sensors laps profile and battery remain unknown`() {
        val empty = RunState()
        listOf(
            DataField.LAP_TIME, DataField.CURRENT_PACE, DataField.AVERAGE_PACE, DataField.LAP_PACE,
            DataField.HEART_RATE, DataField.HR_ZONE, DataField.CADENCE, DataField.POWER,
            DataField.ELEVATION, DataField.BATTERY
        ).forEach { assertEquals(NO_VALUE, fieldValue(it, empty)) }
        assertEquals(
            NO_VALUE,
            fieldValue(DataField.HR_ZONE, state.copy(workoutSettings = WorkoutSettings()))
        )
    }

    @Test
    fun `imperial units convert distance pace and elevation`() {
        val imperial = state.copy(
            workoutSettings = WorkoutSettings(runSettings = RunSettings(DisplayUnits.IMPERIAL))
        )
        assertEquals("8:03", fieldValue(DataField.CURRENT_PACE, imperial))
        assertEquals("328", fieldValue(DataField.ELEVATION, imperial))
    }

    @Test
    fun `zones use explicit maximum HR percentage boundaries`() {
        listOf(99 to "0", 100 to "1", 120 to "2", 140 to "3", 160 to "4", 180 to "5", 210 to "5")
            .forEach { (hr, zone) ->
                assertEquals(
                    zone,
                    fieldValue(
                        DataField.HR_ZONE,
                        state.copy(current = CurrentValues(HeartRate(hr)))
                    )
                )
            }
    }
}
