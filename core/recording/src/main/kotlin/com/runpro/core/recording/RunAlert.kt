package com.runpro.core.recording

import com.runpro.core.model.HeartRate
import com.runpro.core.model.Pace
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp

/** Kinds of live runner alerts emitted while recording. */
enum class AlertKind {
    HR_HIGH,
    HR_LOW,
    PACE_TOO_FAST,
    PACE_TOO_SLOW,
    SENSOR_LOST,
    SENSOR_BACK,
    GPS_LOST,
    GPS_BACK
}

/**
 * One live alert emitted by [RecordingStateMachine] while [RunPhase.RUNNING].
 *
 * Alerts are transient feedback (vibration and banner) rather than persisted [RunEvent]s: they
 * never pause or stop recording (R4) and are not replayed from a journal during crash recovery.
 */
data class RunAlert(
    val id: Long,
    val time: Timestamp,
    val kind: AlertKind,
    val source: SourceId? = null,
    val heartRate: HeartRate? = null,
    val pace: Pace? = null
)
