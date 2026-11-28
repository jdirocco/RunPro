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

    DataField.PACE_ZONE -> paceZone(state)?.toString()

    DataField.CADENCE -> state.current.cadence?.stepsPerMinute?.roundToInt()?.toString()

    DataField.POWER -> state.current.power?.watts?.toString()

    DataField.ELEVATION -> state.current.elevation?.let {
        val imperial = state.workoutSettings.runSettings.units == DisplayUnits.IMPERIAL
        (if (imperial) it.feet else it.meters).roundToInt().toString()
    }

    else -> null
}

/** Five HR zones from configured Max HR or LTHR; unknown profile or 0 bpm stays unknown. */
private fun hrZone(state: RunState): Int? {
    val heartRate = state.current.heartRate ?: return null
    return state.workoutSettings.runSettings.hrZones()?.zoneFor(heartRate)
}

/** Five pace zones from configured threshold pace; stopped or invalid pace stays unknown. */
private fun paceZone(state: RunState): Int? = state.current.pace
    ?.takeIf { it.secondsPerKilometer <= MAX_DISPLAY_PACE_SECONDS_PER_KM }
    ?.let { state.workoutSettings.runSettings.paceZones()?.zoneFor(it) }

private const val MAX_DISPLAY_PACE_SECONDS_PER_KM = 1800.0
private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
