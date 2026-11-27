package com.runpro.core.recording

import com.runpro.core.model.PauseReason
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp

/**
 * Everything the recording engine reacts to, processed strictly in the order received.
 * Times come from the caller (monotonic clock); the engine has no clock of its own, so replay is deterministic.
 */
sealed interface EngineInput {
    /** Idle → Preparing: the runner opened the start screen; sensors and GPS warm up. */
    data class Prepare(val time: Timestamp) : EngineInput

    /** Preparing → Running: the run starts and gets its id. */
    data class Start(val time: Timestamp) : EngineInput

    /** Preparing or Stopped → Idle: the start was cancelled, or the stopped run was handed over. */
    data object Reset : EngineInput

    /** A sensor reading. Samples are used by their own timestamp, so late (batched) samples still count. */
    data class SampleReceived(val sample: Sample) : EngineInput

    /** An adapter status report, retained alongside samples while running or paused. */
    data class SensorReceived(val event: SensorEvent) : EngineInput

    /** The clock moved on (UI timer); updates elapsed and timer time without new samples. */
    data class Tick(val time: Timestamp) : EngineInput

    /** Device resources from the adapter, using the current boot clock. Null fields leave the last reading. */
    data class Resources(
        val time: Timestamp,
        val batteryPercent: Int? = null,
        val availableBytes: Long? = null
    ) : EngineInput {
        init {
            require(batteryPercent == null || batteryPercent in 0..FULL_BATTERY_PERCENT)
            require(availableBytes == null || availableBytes >= 0)
        }

        private companion object {
            const val FULL_BATTERY_PERCENT = 100
        }
    }

    /**
     * A command that adds an event to the running run. A command older than the last event is moved to the
     * time of that event, so the event list stays ordered and a stop is never lost.
     */
    sealed interface Command : EngineInput {
        val time: Timestamp
    }

    /** Running → Paused. */
    data class Pause(override val time: Timestamp, val reason: PauseReason = PauseReason.MANUAL) :
        Command

    /** Paused → Running. */
    data class Resume(override val time: Timestamp) : Command

    /** Running: closes the current lap and opens the next one. */
    data class MarkLap(override val time: Timestamp) : Command

    /** Running or Paused → Stopped: closes the last lap. */
    data class Stop(override val time: Timestamp, val reason: StopReason = StopReason.USER) :
        Command
}
