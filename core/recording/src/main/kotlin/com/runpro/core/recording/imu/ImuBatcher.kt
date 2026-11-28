package com.runpro.core.recording.imu

import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ImuReading
import com.runpro.core.model.ImuSensor
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import kotlin.time.Duration

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Groups the readings of one sensor into [ImuBatch]es that span less than [span]. Readings carry the
 * platform's monotonic time (Android `SensorEvent.timestamp`); the wall clock of a batch is derived from
 * [clock] when its first reading arrives. Readings older than the previous one, or not finite, are
 * dropped and counted in [dropped]. Not thread safe: one thread (the capture executor) feeds it.
 */
internal class ImuBatcher(
    private val source: SourceId,
    private val sensor: ImuSensor,
    private val span: Duration,
    private val clock: () -> Timestamp,
    private val emit: (ImuBatch) -> Unit
) {
    private var start: Timestamp? = null
    private val readings = mutableListOf<ImuReading>()
    private var last = Long.MIN_VALUE

    var dropped: Long = 0
        private set

    fun add(elapsedNanos: Long, x: Float, y: Float, z: Float) {
        val valid = elapsedNanos >= 0 && elapsedNanos >= last &&
            x.isFinite() && y.isFinite() && z.isFinite()
        if (!valid) {
            dropped++
            return
        }
        last = elapsedNanos
        var first = start
        if (first != null && elapsedNanos - first.elapsedRealtimeNanos >= span.inWholeNanoseconds) {
            flush()
            first = null
        }
        if (first == null) {
            val now = clock()
            val wall = now.wallClockMillis -
                Math.floorDiv(now.elapsedRealtimeNanos - elapsedNanos, NANOS_PER_MILLI)
            first = Timestamp(elapsedNanos, wall)
            start = first
        }
        readings += ImuReading(elapsedNanos - first.elapsedRealtimeNanos, x, y, z)
    }

    /** Emits the readings collected so far, if any. */
    fun flush() {
        val first = start ?: return
        val batch = ImuBatch(first, source, sensor, readings.toList())
        // Cleared before emitting: a failing write may flush every batcher again from inside emit.
        readings.clear()
        start = null
        emit(batch)
    }
}
