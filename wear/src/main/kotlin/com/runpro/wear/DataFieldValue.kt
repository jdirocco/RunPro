package com.runpro.wear

import com.runpro.core.model.DisplayUnits
import com.runpro.core.recording.RunState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Presentation only: use admitted engine values, including source silence and lap resets. */
internal fun fieldValue(
    field: DataField,
    state: RunState,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val settings = state.workoutSettings.runSettings
    val lap = state.laps.lastOrNull()?.totals
    return when (field) {
        DataField.TIME -> clock(state.totals.timer)
        DataField.LAP_TIME -> lapTime(state)
        DataField.DISTANCE -> displayDistance(state.totals.distance, settings.units.distance)
        DataField.CURRENT_PACE -> pace(state.current.pace, settings.units.distance)
        DataField.AVERAGE_PACE -> pace(state.totals.averagePace, settings.units.distance)
        DataField.LAP_PACE -> pace(lap?.averagePace, settings.units.distance)
        DataField.CLOCK -> CLOCK_FORMAT.format(now.atZone(zone))
        DataField.BATTERY -> state.resources.batteryPercent?.let { "$it%" } ?: NO_VALUE
        else -> sensorValue(field, state) ?: NO_VALUE
    }
}

private fun lapTime(state: RunState): String =
    state.laps.lastOrNull()?.totals?.let { clock(it.timer) } ?: NO_VALUE

private fun sensorValue(field: DataField, state: RunState): String? = when (field) {
    DataField.HEART_RATE -> heartRate(state.current.heartRate)

    DataField.HR_ZONE -> hrZone(state)?.toString()

    DataField.CADENCE -> state.current.cadence?.stepsPerMinute?.roundToInt()?.toString()

    DataField.POWER -> state.current.power?.watts?.toString()

    DataField.ELEVATION -> state.current.elevation?.let {
        val imperial = state.workoutSettings.runSettings.units == DisplayUnits.IMPERIAL
        (if (imperial) it.feet else it.meters).roundToInt().toString()
    }

    else -> null
}

/** Five max-HR bands; zone 0 below 50%. Unknown maximum means unknown zone. */
private fun hrZone(state: RunState): Int? {
    val maximum = state.workoutSettings.runSettings.profile.maxHeartRate
    val heartRate = state.current.heartRate?.bpm?.takeIf { it > 0 }
    return if (maximum == null || heartRate == null) {
        null
    } else {
        val percent = heartRate * 100 / maximum
        HR_BOUNDARIES.count { percent >= it }
    }
}

private val HR_BOUNDARIES = listOf(50, 60, 70, 80, 90)
private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
