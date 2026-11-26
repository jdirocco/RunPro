package com.runpro.core.fit

import com.runpro.core.model.LapTrigger
import com.runpro.core.model.RunEvent
import com.runpro.core.model.SensorEvent

// What FitDecoder reads back from a FIT activity file, in SI units and RunPro types, so ExportVerifier can
// compare it with the journal without knowing FIT scales and enums. Times are Unix epoch milliseconds,
// truncated to the whole second as FIT stores them.

/** A decoded FIT activity file. */
data class FitActivity(
    val fileType: String?,
    val timeCreatedMillis: Long?,
    val devices: List<FitDevice>,
    val events: List<FitEvent>,
    val records: List<FitRecord>,
    val laps: List<FitTotals>,
    val session: FitTotals?,
    val sessionCount: Int,
    val runId: String?,
    val activityTimerSeconds: Double?
)

/**
 * One `device_info` message.
 *
 * @property descriptor the RunPro source id for a sensor, null for the creator.
 */
data class FitDevice(val index: Int?, val descriptor: String?, val sourceType: String?)

/**
 * One `event` message.
 *
 * @property type the FIT `event` and `event_type`, e.g. `TIMER/START`.
 * @property runEvent the RunPro run event carried by the RunPro developer field, if any.
 * @property sensorEvent the RunPro sensor event carried by the RunPro developer field, if any.
 */
data class FitEvent(
    val timeMillis: Long?,
    val type: String,
    val timerTrigger: String?,
    val deviceIndex: Int?,
    val runEvent: RunEvent?,
    val sensorEvent: SensorEvent?
)

/** One `record` message. Null fields are absent in the file. */
data class FitRecord(
    val timeMillis: Long?,
    val heartRateBpm: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeMeters: Double?,
    val distanceMeters: Double?,
    val speedMetersPerSecond: Double?,
    val cadenceStepsPerMinute: Double?,
    val powerWatts: Int?
)

/** The totals of one `lap` or `session` message. */
data class FitTotals(
    val startMillis: Long?,
    val endMillis: Long?,
    val elapsedSeconds: Double?,
    val timerSeconds: Double?,
    val movingSeconds: Double?,
    val distanceMeters: Double?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val averageCadenceStepsPerMinute: Double?,
    val maxCadenceStepsPerMinute: Double?,
    val ascentMeters: Int?,
    val descentMeters: Int?,
    val averageSpeedMetersPerSecond: Double?,
    val sport: String?,
    val lapTrigger: LapTrigger? = null,
    val lapCount: Int? = null
)
