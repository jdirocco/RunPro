package com.runpro.tools.replay

import com.runpro.core.model.Metric
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.Timestamp
import kotlin.time.Duration

/**
 * Deterministic faults applied at delivery time. Input records keep their file order, including late
 * batches. Boundary events and callbacks precede records due at the same time; ties between faults
 * follow declaration order (outage ends precede starts). Outages on the same source may abut but cannot
 * overlap.
 *
 * Boundaries after the last record still run (including recovery); an empty trace accepts only an empty
 * plan. Source faults must name a source present in the trace. This does not change the trace on disk.
 */
class ChaosPlan(vararg faults: ReplayFault) {
    val faults: List<ReplayFault> = faults.toList()
    private val outages = this.faults.filterIsInstance<ReplayFault.Outage>()
    private val jumps = this.faults.filterIsInstance<ReplayFault.ClockJump>()

    init {
        this.faults.forEach { fault ->
            requireOffset(fault.at)
            when (fault) {
                is ReplayFault.Outage -> {
                    require(fault.duration.isPositive()) { "Outages need a positive duration" }
                    requireOffset(fault.at + fault.duration)
                }

                is ReplayFault.ClockJump -> require(fault.by.isFinite()) {
                    "Clock jumps must be finite"
                }

                else -> Unit
            }
        }
        outages.groupBy { it.source }.values.forEach { sourceOutages ->
            sourceOutages.sortedBy { it.at }.zipWithNext().forEach { (a, b) ->
                require(a.at + a.duration <= b.at) { "Overlapping outages for ${a.source}" }
            }
        }
    }

    internal fun steps(trace: Trace, handler: ReplayFaultHandler?): List<ReplayStep> {
        require(trace.records.isNotEmpty() || faults.isEmpty()) {
            "Faults need a trace time origin"
        }
        require(handler != null || faults.none { it.needsHandler() }) {
            "Process death and storage full require a ReplayFaultHandler"
        }
        outages.forEach {
            require(it.source in trace.sources) { "Unknown fault source: ${it.source}" }
        }
        val origin = trace.records.firstOrNull()?.time ?: return emptyList()
        val boundaries = faults.flatMap { boundarySteps(it, origin) }.sortedWith(
            compareBy<ReplayStep> { it.offsetNanos }.thenBy {
                val event = (it.record as? TraceRecord.EventRecord)?.event
                if (event is SensorEvent.Connected || event is SensorEvent.GpsAcquired) 0 else 1
            }
        )
        val result = mutableListOf<ReplayStep>()
        var next = 0
        var offset = 0L
        for (record in trace.records) {
            offset = maxOf(offset, record.time.elapsedRealtimeNanos - origin.elapsedRealtimeNanos)
            while (next < boundaries.size && boundaries[next].offsetNanos <= offset) {
                result += boundaries[next++]
            }
            result += if (suppressed(record, offset)) {
                ReplayStep(offset) // A suppressed final record must still advance replay time.
            } else {
                ReplayStep(offset, record = record.withTime(adjusted(record.time, origin)))
            }
        }
        result += boundaries.drop(next)
        return result
    }

    private fun boundarySteps(fault: ReplayFault, origin: Timestamp): List<ReplayStep> =
        when (fault) {
            is ReplayFault.ProcessDeath, is ReplayFault.StorageFull -> listOf(
                ReplayStep(
                    fault.at.inWholeNanoseconds,
                    fault = fault,
                    time = adjusted(origin + fault.at, origin)
                )
            )

            is ReplayFault.BleDisconnect -> listOf(
                eventStep(
                    fault.at,
                    SensorEvent.Disconnected(origin + fault.at, fault.source, fault.gattStatus),
                    origin
                ),
                eventStep(
                    fault.at + fault.duration,
                    SensorEvent.Connected(origin + fault.at + fault.duration, fault.source),
                    origin
                )
            )

            is ReplayFault.GpsLoss -> listOf(
                eventStep(fault.at, SensorEvent.GpsLost(origin + fault.at, fault.source), origin),
                eventStep(
                    fault.at + fault.duration,
                    SensorEvent.GpsAcquired(origin + fault.at + fault.duration, fault.source),
                    origin
                )
            )

            is ReplayFault.SilentStall -> listOf(
                ReplayStep(fault.at.inWholeNanoseconds),
                ReplayStep((fault.at + fault.duration).inWholeNanoseconds)
            )

            is ReplayFault.ClockJump -> listOf(ReplayStep(fault.at.inWholeNanoseconds))
        }

    private fun eventStep(at: Duration, event: SensorEvent, origin: Timestamp): ReplayStep =
        ReplayStep(
            at.inWholeNanoseconds,
            TraceRecord.EventRecord(event).withTime(adjusted(event.time, origin))
        )

    private fun suppressed(record: TraceRecord, offset: Long): Boolean = outages.any { fault ->
        record.source == fault.source && offset >= fault.at.inWholeNanoseconds &&
            offset < (fault.at + fault.duration).inWholeNanoseconds && when (fault) {
                is ReplayFault.BleDisconnect -> true

                is ReplayFault.SilentStall -> record !is TraceRecord.EventRecord

                is ReplayFault.GpsLoss -> when (record) {
                    is TraceRecord.SampleRecord -> record.sample.metric in GPS_METRICS

                    is TraceRecord.EventRecord ->
                        record.event is SensorEvent.GpsAcquired ||
                            record.event is SensorEvent.GpsLost

                    is TraceRecord.ImuRecord -> false
                }
            }
    }

    // Use acquisition time for wall jumps: a batch captured before a jump keeps its original wall time.
    private fun adjusted(time: Timestamp, origin: Timestamp): Timestamp {
        val offset = time.elapsedSince(origin)
        val wall = jumps.filter { it.at <= offset }.fold(time.wallClockMillis) { value, jump ->
            Math.addExact(value, jump.by.inWholeMilliseconds)
        }
        return time.copy(wallClockMillis = wall)
    }

    private fun ReplayFault.needsHandler() =
        this is ReplayFault.ProcessDeath || this is ReplayFault.StorageFull

    private fun requireOffset(offset: Duration) {
        require(
            offset.isFinite() && offset >= Duration.ZERO &&
                offset.inWholeNanoseconds < Long.MAX_VALUE
        ) {
            "Fault offsets must be finite, nonnegative nanosecond durations"
        }
    }

    private companion object {
        val GPS_METRICS = setOf(Metric.LOCATION, Metric.DISTANCE, Metric.SPEED, Metric.ELEVATION)
    }
}

/** A scheduled record or external fault callback; offsets are independent of wall clock adjustments. */
internal data class ReplayStep(
    val offsetNanos: Long,
    val record: TraceRecord? = null,
    val fault: ReplayFault? = null,
    val time: Timestamp? = null
)
