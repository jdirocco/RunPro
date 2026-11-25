package com.runpro.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Something that happened during a run. The ordered list of events is the history of a [Run]. */
@Serializable
sealed interface RunEvent {
    val time: Timestamp

    @Serializable
    @SerialName("started")
    data class Started(override val time: Timestamp) : RunEvent

    @Serializable
    @SerialName("paused")
    data class Paused(override val time: Timestamp, val reason: PauseReason = PauseReason.MANUAL) :
        RunEvent

    @Serializable
    @SerialName("resumed")
    data class Resumed(override val time: Timestamp) : RunEvent

    /** Closes the current lap and opens the next one. */
    @Serializable
    @SerialName("lap")
    data class LapMarked(override val time: Timestamp, val trigger: LapTrigger) : RunEvent

    /**
     * The active source for [metric] changed (R5). [from] is null when the metric had no source yet,
     * [to] is null when no source is left.
     */
    @Serializable
    @SerialName("source_switched")
    data class SourceSwitched(
        override val time: Timestamp,
        val metric: Metric,
        val from: SourceId?,
        val to: SourceId?,
        val reason: SwitchReason
    ) : RunEvent

    @Serializable
    @SerialName("sensor_connected")
    data class SensorConnected(override val time: Timestamp, val source: SourceId) : RunEvent

    @Serializable
    @SerialName("sensor_lost")
    data class SensorLost(
        override val time: Timestamp,
        val source: SourceId,
        val reason: SensorLossReason
    ) : RunEvent

    @Serializable
    @SerialName("stopped")
    data class Stopped(override val time: Timestamp, val reason: StopReason) : RunEvent
}

@Serializable
enum class PauseReason {
    @SerialName("manual")
    MANUAL,

    @SerialName("auto")
    AUTO
}

/** Why a lap ended. */
@Serializable
enum class LapTrigger {
    @SerialName("manual")
    MANUAL,

    @SerialName("distance")
    DISTANCE,

    @SerialName("time")
    TIME,

    /** The run stopped and closed the last lap. */
    @SerialName("stop")
    STOP
}

@Serializable
enum class SwitchReason {
    /** The active source sent no data within its timeout. */
    @SerialName("stale")
    STALE,

    /** A source with a higher priority came (back) online. */
    @SerialName("higher_priority")
    HIGHER_PRIORITY,

    /** The runner chose a source. */
    @SerialName("user")
    USER
}

@Serializable
enum class SensorLossReason {
    @SerialName("disconnected")
    DISCONNECTED,

    /** Still connected but silent (watchdog). */
    @SerialName("no_data")
    NO_DATA,

    @SerialName("error")
    ERROR
}

@Serializable
enum class StopReason {
    @SerialName("user")
    USER,

    @SerialName("low_battery")
    LOW_BATTERY,

    @SerialName("storage_full")
    STORAGE_FULL,

    /** Closed by crash recovery because the process died during the run. */
    @SerialName("recovery")
    RECOVERY
}
