package com.runpro.tools.replay

import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import kotlin.time.Duration

/** Fault offsets are monotonic durations from the first record, independent of replay speed. */
sealed interface ReplayFault {
    val at: Duration

    /** The handler must destroy the consumer and replace it with one recovered from its journal. */
    data class ProcessDeath(override val at: Duration) : ReplayFault

    /** The handler makes the journal's storage port fail writes; it must not merely stop the engine. */
    data class StorageFull(override val at: Duration) : ReplayFault

    /** A persistent wall clock adjustment; monotonic timestamps and pacing remain unchanged. */
    data class ClockJump(override val at: Duration, val by: Duration) : ReplayFault

    /** Source outages cover [at, at + duration); samples resume at the end boundary. */
    sealed interface Outage : ReplayFault {
        val duration: Duration
        val source: SourceId
    }

    /** Disconnects and reconnects, suppressing all source records in between. Use status 133 for GATT_ERROR. */
    data class BleDisconnect(
        override val at: Duration,
        override val duration: Duration,
        override val source: SourceId,
        val gattStatus: Int? = null
    ) : Outage

    /** Suppresses notifications (samples), without manufacturing a disconnect or reconnect. */
    data class SilentStall(
        override val at: Duration,
        override val duration: Duration,
        override val source: SourceId
    ) : Outage

    /** Suppresses location, distance, speed and elevation; wrist HR, cadence and other sources continue. */
    data class GpsLoss(
        override val at: Duration,
        override val duration: Duration,
        override val source: SourceId = SourceId.HEALTH_SERVICES
    ) : Outage
}

/**
 * Called synchronously on the replay thread, before records due at the same offset. Only process death
 * and storage full use this port. Exceptions propagate: a failed recovery must fail the test.
 * A process-death handler can stop and restart replay sources to bind them to the replacement engine.
 */
fun interface ReplayFaultHandler {
    fun onFault(fault: ReplayFault, time: Timestamp)
}
