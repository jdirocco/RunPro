package com.runpro.core.model

import java.util.UUID
import kotlin.time.Duration
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class RunId(val value: String) {
    init {
        require(value.isNotBlank()) { "Run id cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun random(): RunId = RunId(UUID.randomUUID().toString())
    }
}

/**
 * A run as the ordered list of its [events]. Laps, start and stop are derived from it, so there is one
 * source of truth. Events keep the order in which they were recorded; their timestamps are not used for
 * ordering because the monotonic clock restarts when the watch reboots.
 *
 * Structural rules: the first event is the only [RunEvent.Started]; a [RunEvent.Stopped], if any, is last.
 */
@Serializable
data class Run(val id: RunId, val events: List<RunEvent>) {
    init {
        require(events.firstOrNull() is RunEvent.Started) {
            "A run must begin with a Started event"
        }
        val starts = events.count { it is RunEvent.Started }
        require(starts == 1) { "A run has exactly one Started event, found $starts" }
        val stopIndex = events.indexOfFirst { it is RunEvent.Stopped }
        require(stopIndex == -1 || stopIndex == events.lastIndex) { "No event can follow Stopped" }
    }

    val startedAt: Timestamp get() = events.first().time

    val stoppedAt: Timestamp? get() = (events.last() as? RunEvent.Stopped)?.time

    val isStopped: Boolean get() = stoppedAt != null

    /** Laps in order; the last one is open while the run is not stopped. */
    val laps: List<Lap>
        get() {
            val laps = mutableListOf<Lap>()
            var start = startedAt
            for (event in events) {
                val trigger = when (event) {
                    is RunEvent.LapMarked -> event.trigger
                    is RunEvent.Stopped -> LapTrigger.STOP
                    else -> null
                } ?: continue
                laps += Lap(laps.size, start, event.time, trigger)
                start = event.time
            }
            if (!isStopped) laps += Lap(laps.size, start)
            return laps
        }

    operator fun plus(event: RunEvent): Run = copy(events = events + event)
}

/** A lap from [start] to [end]; [end] and [trigger] are null while the lap is open. */
@Serializable
data class Lap(
    val index: Int,
    val start: Timestamp,
    val end: Timestamp? = null,
    val trigger: LapTrigger? = null
) {
    init {
        require(index >= 0) { "Lap index cannot be negative: $index" }
        require((end == null) == (trigger == null)) {
            "A closed lap needs both an end and a trigger"
        }
    }

    val isOpen: Boolean get() = end == null

    /** Monotonic time from start to end, pauses included; null while open. */
    val elapsed: Duration? get() = end?.elapsedSince(start)
}
